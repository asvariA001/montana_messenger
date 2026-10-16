import Foundation
import AVFoundation
import CoreImage
import CryptoKit
import SwiftUI
import WebRTC

// A reader of the call's existing media. It never opens a microphone, camera or audio session.
// The SDK owns processing; AVAssetWriter/AVAssetExportSession own encoding, timing and mixing.
// A call's identity survives native call UUID replacement on process restoration.
enum MTCallName {
    static func name(_ secret: Data) -> String {
        SHA256.hash(data: Data("montana.call.identity.v1:".utf8) + secret).map { String(format: "%02x", $0) }.joined()
    }
}

enum MTCallRecordingPolicy {
    static let key = "callRecordingEnabled"
    static let consentKey = "callRecordingConsent.v1"
    static var enabled: Bool { UserDefaults.standard.object(forKey: key) as? Bool ?? true }
    static var consented: Bool { UserDefaults.standard.bool(forKey: consentKey) }
}

// Only short memory operations under this lock. No SDK, disk or queue operation runs under it.
// A parked call has a file, but never owns the microphone of the next call.
enum MTCallRecordingGate {
    private static let lock = NSLock()
    private static var holder: UUID?
    private static var held = Set<UUID>()
    private static var muted = Set<UUID>()
    private static var tapes: [UUID: MTCallTape] = [:]
    static func select(_ id: UUID?) { lock.withLock { holder = id } }
    static func release(_ id: UUID) { lock.withLock { if holder == id { holder = nil }; held.remove(id); muted.remove(id) } }
    static func hold(_ id: UUID, _ on: Bool) { lock.withLock { if on { held.insert(id) } else { held.remove(id) } } }
    static func mute(_ id: UUID, _ on: Bool) { lock.withLock { if on { muted.insert(id) } else { muted.remove(id) } } }
    static func add(_ id: UUID, _ tape: MTCallTape) { lock.withLock { tapes[id] = tape } }
    static func remove(_ id: UUID) { lock.withLock { _ = tapes.removeValue(forKey: id) } }
    static func current(local: Bool) -> MTCallTape? {
        lock.withLock {
            guard let id = holder, !held.contains(id), !local || !muted.contains(id) else { return nil }
            return tapes[id]
        }
    }
    static func allows(_ id: UUID) -> Bool { lock.withLock { holder == id && !held.contains(id) && tapes[id] != nil } }
}

// The processing delegates are retained for the factory's lifetime. SDK buffers never escape their callback.
final class MTCallRecordingProcessor: NSObject, RTCAudioCustomProcessingDelegate {
    static let local = MTCallRecordingProcessor(local: true)
    static let remote = MTCallRecordingProcessor(local: false)
    private let local: Bool
    private var rate: Double = 0
    init(local: Bool) { self.local = local }
    func audioProcessingInitialize(sampleRate: Int, channels: Int) { rate = Double(sampleRate) }
    func audioProcessingRelease() { rate = 0 }
    func audioProcessingProcess(audioBuffer: RTCAudioBuffer) {
        guard rate > 0, (1...2).contains(audioBuffer.channels), (1...1920).contains(audioBuffer.frames), let tape = MTCallRecordingGate.current(local: local),
              tape.reserveAudio() else { return }
        guard let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: AVAudioChannelCount(audioBuffer.channels)),
              let copy = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(audioBuffer.frames)),
              let channels = copy.floatChannelData else { tape.releaseAudio(); return }
        copy.frameLength = copy.frameCapacity
        // The SDK's floating point samples use the Int16 amplitude range; AVFoundation uses -1...1.
        for channel in 0..<audioBuffer.channels {
            let input = audioBuffer.rawBuffer(forChannel: channel)
            for frame in 0..<audioBuffer.frames { channels[channel][frame] = input[frame] / 32768 }
        }
        tape.audio(copy, source: local ? "local" : "remote", at: ProcessInfo.processInfo.systemUptime)
    }
}

struct MTCallTapeManifest: Codable {
    var id: String
    var owner: String
    var chat: String
    var started: Double
    // Missing in older manifests: retain their files without guessing who may publish.
    var sendsToChat: Bool? = nil
    struct File: Codable { var name: String; var source: String }
    var files: [File] = []
    var partial = false
    var stopped = false
    var queued = false
    var output: String?
    var notice = false
    var failureMid: String?
    var unavailableReason: String?
    var localFrames = 0
    var remoteFrames = 0
}

enum MTCallTapeStore {
    static var ids: [String] {
        ((try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.pathExtension == "recording" }.map { $0.deletingPathExtension().lastPathComponent }
    }
    static func load(_ id: String) -> MTCallTapeManifest? {
        guard let data = try? Data(contentsOf: directory.appendingPathComponent(id + ".recording")),
              let plain = MontanaLocalVault.open(data, Data(id.utf8)) else { return nil }
        return try? JSONDecoder().decode(MTCallTapeManifest.self, from: plain)
    }
    @discardableResult static func save(_ item: MTCallTapeManifest) -> Bool {
        guard let data = try? JSONEncoder().encode(item), let sealed = MontanaLocalVault.seal(data, Data(item.id.utf8)) else { return false }
        do {
            try sealed.write(to: directory.appendingPathComponent(item.id + ".recording"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch { return false }
    }
    static var directory: URL {
        var url = MontanaMediaStore.dir.appendingPathComponent("CallRecordings", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return url
    }
}

// One serial file writer for a call; bounded inputs never hold the real-time audio callback behind disk I/O.
final class MTCallTape {
    private final class Part {
        let writer: AVAssetWriter
        let input: AVAssetWriterInput
        let adaptor: AVAssetWriterInputPixelBufferAdaptor?
        var end = CMTime.zero
        init(url: URL, video: Bool) throws {
            writer = try AVAssetWriter(outputURL: url, fileType: .mov)
            writer.movieFragmentInterval = CMTime(seconds: 2, preferredTimescale: 600)
            if video {
                input = AVAssetWriterInput(mediaType: .video, outputSettings: [AVVideoCodecKey: AVVideoCodecType.h264,
                    AVVideoWidthKey: 640, AVVideoHeightKey: 360,
                    AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 400_000, AVVideoMaxKeyFrameIntervalKey: 20]])
                adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
                    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                    kCVPixelBufferWidthKey as String: 640, kCVPixelBufferHeightKey as String: 360,
                    kCVPixelBufferIOSurfacePropertiesKey as String: [:]])
            } else {
                input = AVAssetWriterInput(mediaType: .audio, outputSettings: [AVFormatIDKey: kAudioFormatMPEG4AAC,
                    AVSampleRateKey: 48_000, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 48_000])
                adaptor = nil
            }
            input.expectsMediaDataInRealTime = true
            guard writer.canAdd(input) else { throw CocoaError(.fileWriteUnknown) }
            writer.add(input)
            guard writer.startWriting() else { throw writer.error ?? CocoaError(.fileWriteUnknown) }
            writer.startSession(atSourceTime: .zero)
        }
    }
    let id: UUID
    private let queue = DispatchQueue(label: "montana.call.recording", qos: .utility)
    // Up to 2.56 seconds of the two 10 ms streams: bounded headroom for a cold system encoder.
    private let audioSlots = DispatchSemaphore(value: 512)
    private let videoSlots = DispatchSemaphore(value: 4)
    private let origin = ProcessInfo.processInfo.systemUptime
    private let offset: Double
    private let pixels = MTCallFramePixels()
    private let ci = CIContext(options: [.cacheIntermediates: false])
    private var item: MTCallTapeManifest
    private var parts: [String: Part] = [:]
    private var refusedParts = Set<String>()
    private var latest: [String: (frame: RTCVideoFrame, at: Double)] = [:]   // the newest frame of each picture, as it came
    private var ending = false
    private var firstFailure = false
    private var receivedAudio = false
    private let faultLock = NSLock()
    private var faultQueued = false
    let timeline: MTCallTimeline?
    private let onFailure: () -> Void
    private let onAudio: () -> Void
    init(id: UUID, manifest: MTCallTapeManifest, timeline: MTCallTimeline? = nil,
         onAudio: @escaping () -> Void = {}, onFailure: @escaping () -> Void) {
        self.id = id; item = manifest; offset = max(0, Date().timeIntervalSince1970 - manifest.started)
        self.onFailure = onFailure; self.timeline = timeline
        self.onAudio = onAudio
    }
    func reserveAudio() -> Bool {
        guard audioSlots.wait(timeout: .now()) == .success else { failAsync(); return false }
        return true
    }
    func releaseAudio() { audioSlots.signal() }
    private func failAsync() {
        let first = faultLock.withLock { if faultQueued { return false }; faultQueued = true; return true }
        if first { queue.async { self.fail() } }
    }
    private func fail() {
        item.partial = true
        guard !firstFailure else { return }
        firstFailure = true; _ = MTCallTapeStore.save(item)
        DispatchQueue.main.async(execute: onFailure)
        timeline?.mark("record.partial")
        MontanaLog.event("CALL_RECORD partial")
    }
    private func part(_ source: String, video: Bool) throws -> Part {
        if let p = parts[source] { return p }
        guard !refusedParts.contains(source) else { throw CocoaError(.fileWriteUnknown) }
        let name = UUID().uuidString + ".mov"
        item.files.append(.init(name: name, source: source))
        guard MTCallTapeStore.save(item) else { throw CocoaError(.fileWriteUnknown) }
        timeline?.mark("record.codec.begin", "source=\(source)", operation: source)
        let p: Part
        do { p = try Part(url: MTCallTapeStore.directory.appendingPathComponent(name), video: video) }
        catch {
            refusedParts.insert(source)
            timeline?.mark("record.codec.fail", "source=\(source)", operation: source)
            throw error
        }
        timeline?.mark("record.codec.ok", "source=\(source)", operation: source)
        parts[source] = p
        return p
    }
    func audio(_ pcm: AVAudioPCMBuffer, source: String, at: Double) {
        queue.async {
            defer { self.audioSlots.signal() }
            guard !self.ending, !self.firstFailure else { return }
            do {
                let p = try self.part(source, video: false)
                let duration = Double(pcm.frameLength) / pcm.format.sampleRate
                let point = CMTime(seconds: max(0, self.offset + at - self.origin - duration), preferredTimescale: 48_000)
                let start = CMTimeMaximum(point, p.end)
                guard p.input.isReadyForMoreMediaData, let sample = Self.sample(pcm, at: start), p.input.append(sample) else {
                    self.fail(); return
                }
                self.timeline?.mark("record.audio.\(source).first", once: true)
                if !self.receivedAudio {
                    self.receivedAudio = true
                    DispatchQueue.main.async(execute: self.onAudio)
                }
                p.end = start + CMTime(seconds: duration, preferredTimescale: 48_000)
                if source == "local" { self.item.localFrames += Int(pcm.frameLength) }
                else { self.item.remoteFrames += Int(pcm.frameLength) }
            } catch { self.fail() }
        }
    }
    static func sample(_ pcm: AVAudioPCMBuffer, at: CMTime) -> CMSampleBuffer? {
        var description: CMAudioFormatDescription?
        guard CMAudioFormatDescriptionCreate(allocator: kCFAllocatorDefault, asbd: pcm.format.streamDescription,
            layoutSize: 0, layout: nil, magicCookieSize: 0, magicCookie: nil, extensions: nil,
            formatDescriptionOut: &description) == noErr, let description else { return nil }
        var timing = CMSampleTimingInfo(duration: CMTime(value: 1, timescale: CMTimeScale(pcm.format.sampleRate)),
            presentationTimeStamp: at, decodeTimeStamp: .invalid)
        var result: CMSampleBuffer?
        guard CMSampleBufferCreate(allocator: kCFAllocatorDefault, dataBuffer: nil, dataReady: false,
            makeDataReadyCallback: nil, refcon: nil, formatDescription: description, sampleCount: Int(pcm.frameLength),
            sampleTimingEntryCount: 1, sampleTimingArray: &timing, sampleSizeEntryCount: 0, sampleSizeArray: nil,
            sampleBufferOut: &result) == noErr, let result,
            CMSampleBufferSetDataBufferFromAudioBufferList(result, blockBufferAllocator: kCFAllocatorDefault,
                blockBufferMemoryAllocator: kCFAllocatorDefault, flags: 0, bufferList: pcm.audioBufferList) == noErr else { return nil }
        CMSampleBufferSetDataReady(result)
        return result
    }
    func video(_ frame: RTCVideoFrame, source: String, at: Double) {
        guard videoSlots.wait(timeout: .now()) == .success else { return } // video may shed frames; it never delays voice
        queue.async {
            defer { self.videoSlots.signal() }
            guard !self.ending, !self.firstFailure else { return }
            guard self.latest[source] != nil || self.latest.count < 14 else { return }
            // THE FRAME IS ADMITTED BEFORE IT COSTS ANYTHING (16.10.2026, No.70, the fleet's heat: on the days of their video
            // calls the phones' graphics ran up to 2.2 times their screen time). Every frame of every picture -- thirty a second
            // each -- was turned into pixels and drawn into a thumbnail, and the ten-a-second check after that work threw two of
            // three away: seventy drawings a second for ten frames written. A frame now only takes its source's place; the tenth
            // that is written draws once, from the newest frame of each source.
            self.latest[source] = (frame, at)
            // UNDER THE PHONE'S HEAT THE PICTURE SLOWS AND THE VOICE STAYS (the one owner of the heat's answer, MontanaPower):
            // under pressure one picture a second, at the critical state none -- the file keeps the call's voice whole.
            let floor = MontanaPower.floorNow
            guard floor < 4 else { return }
            let step = floor == 0 ? CMTime(value: 1, timescale: 10) : CMTime(value: 1, timescale: 1)
            do {
                let p = try self.part("video", video: true)
                let point = CMTime(seconds: max(0, self.offset + at - self.origin), preferredTimescale: 600)
                guard let adaptor = p.adaptor, let pool = adaptor.pixelBufferPool,
                      point - p.end >= step, p.input.isReadyForMoreMediaData else { return }
                // A stopped camera leaves the picture. The file cannot reveal its last frame through the next call.
                self.latest = self.latest.filter { at - $0.value.at < 1 }
                let ordered = self.latest.sorted { $0.key < $1.key }
                let columns = ordered.count < 2 ? 1 : Int(ceil(sqrt(Double(ordered.count))))
                let rows = Int(ceil(Double(ordered.count) / Double(columns)))
                let cellW = 640 / CGFloat(columns), cellH = 360 / CGFloat(max(1, rows))
                var canvas = CIImage(color: .black).cropped(to: CGRect(x: 0, y: 0, width: 640, height: 360))
                for (index, entry) in ordered.enumerated() {
                    // One native encoder for the whole call, including a room. A camera never opens another encoder.
                    guard let original = self.pixels.buffer(entry.value.frame) else { self.fail(); return }
                    let turn = entry.value.frame.rotation
                    let orientation: Int32 = turn == ._90 ? 6 : turn == ._180 ? 3 : turn == ._270 ? 8 : 1
                    let image = CIImage(cvPixelBuffer: original).oriented(forExifOrientation: orientation)
                    // The picture fits its source's 16:9 box and the box fits its cell -- as the thumbnail and the grid placed it.
                    let box = min(cellW / 640, cellH / 360)
                    let boxW = 640 * box, boxH = 360 * box
                    let fit = min(boxW / image.extent.width, boxH / image.extent.height)
                    let x = CGFloat(index % columns) * cellW + (cellW - boxW) / 2 + (boxW - image.extent.width * fit) / 2
                    let y = CGFloat(index / columns) * cellH + (cellH - boxH) / 2 + (boxH - image.extent.height * fit) / 2
                    let placed = image.transformed(by: CGAffineTransform(translationX: -image.extent.minX, y: -image.extent.minY)
                        .concatenating(CGAffineTransform(scaleX: fit, y: fit)).concatenating(CGAffineTransform(translationX: x, y: y)))
                    canvas = placed.composited(over: canvas)
                }
                var output: CVPixelBuffer?
                guard CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &output) == kCVReturnSuccess, let output else { self.fail(); return }
                self.ci.render(canvas, to: output)
                guard adaptor.append(output, withPresentationTime: point) else { self.fail(); return }
                p.end = point
            } catch { self.fail() }
        }
    }
    func finish(_ completion: @escaping (MTCallTapeManifest) -> Void) {
        queue.async {
            guard !self.ending else { return }
            self.ending = true
            self.timeline?.mark("record.finish.begin")
            let group = DispatchGroup()
            for p in self.parts.values {
                p.input.markAsFinished(); group.enter()
                p.writer.finishWriting { group.leave() }
            }
            group.notify(queue: self.queue) {
                if self.parts.values.contains(where: { $0.writer.status != .completed }) { self.fail() }
                self.item.stopped = true
                _ = MTCallTapeStore.save(self.item)
                self.timeline?.mark("record.finish.ok", "local_frames=\(self.item.localFrames) remote_frames=\(self.item.remoteFrames)")
                completion(self.item)
            }
        }
    }
}

final class MTCallRecordingVideo: NSObject, RTCVideoRenderer {
    let tape: MTCallTape
    let source: String
    init(_ tape: MTCallTape, source: String) { self.tape = tape; self.source = source }
    func setSize(_ size: CGSize) {}
    func renderFrame(_ frame: RTCVideoFrame?) {
        guard let frame, MTCallRecordingGate.allows(tape.id) else { return }
        tape.video(frame, source: source, at: ProcessInfo.processInfo.systemUptime)
    }
}

@MainActor final class MTCallRecording: ObservableObject {
    static let shared = MTCallRecording()
    @Published private(set) var recording = Set<UUID>()
    @Published private(set) var failures = Set<UUID>()
    @Published var askConsent = false
    private struct Active {
        var tape: MTCallTape
        var manifestID: String
        var token: UUID
        var videos: [(RTCVideoTrack, MTCallRecordingVideo)] = []
    }
    private struct Pending { var uuid: UUID; var id: String; var chat: String; var since: Date; var sendsToChat: Bool; var timeline: MTCallTimeline? }
    private var active: [UUID: Active] = [:]
    private var pending: [UUID: Pending] = [:]
    private var exporting = Set<String>()
    private var backlog: [MTCallTapeManifest] = []
    private var recovering = false
    private var closing: [UUID: Bool] = [:]
    private var timelines: [String: MTCallTimeline] = [:]
    private var closed: [UUID: MTCallTapeManifest] = [:]

    func isRecording(_ call: String) -> Bool {
        active.contains { recording.contains($0.key) && $0.value.manifestID == call }
    }

    func begin(uuid: UUID, id: String, chat: String, since: Date, sendsToChat: Bool, timeline: MTCallTimeline? = nil) {
        guard MTCallRecordingPolicy.enabled, active[uuid] == nil else { return }
        if closing[uuid] != nil {
            pending[uuid] = Pending(uuid: uuid, id: id, chat: chat, since: since, sendsToChat: sendsToChat, timeline: timeline); return
        }
        closed.removeValue(forKey: uuid)
        guard MTCallRecordingPolicy.consented else {
            pending[uuid] = Pending(uuid: uuid, id: id, chat: chat, since: since, sendsToChat: sendsToChat, timeline: timeline)
            askConsent = true; return
        }
        guard let owner = MontanaSeed.twin, !owner.isEmpty else { return }
        let previous = MTCallTapeStore.load(id)
        var item = previous ?? MTCallTapeManifest(id: id, owner: owner, chat: chat, started: Date().timeIntervalSince1970)
        guard item.owner == owner, !item.queued else { return }
        // Only the live call can supply a missing role. Recovery never promotes a recording.
        if item.sendsToChat == nil { item.sendsToChat = sendsToChat }
        if previous != nil && !item.stopped && !item.files.isEmpty { item.partial = true }
        item.stopped = false
        item.unavailableReason = nil
        if !item.notice {
            guard let store = ChatStore.live else { return }
            let text = item.sendsToChat == true
                ? String(localized: "This call is being recorded. The recording will be sent to this chat after the call.")
                : String(localized: "This phone is recording the call locally. Only the caller sends a recording to the chat.")
            if MTGroup.isKey(chat) {
                let row = MTGroup.shared.send(text, in: chat, replyText: nil, replyToId: nil, replyWireMid: nil, store: store)
                guard !row.text.isEmpty else { failures.insert(uuid); return }
            } else {
                let mid = ChatStore.mintMid().mid
                let row = Message(text: text, isFromMe: true, time: nowHHMM(), deliveryStatus: .sending, msgId: "mid:" + mid)
                guard store.append(chat, row) else { return }
                MontanaDeliveryEngine.shared.enqueue(to: chat, chat: chat, mid: mid, text: text, silent: false)
            }
            item.notice = true
        }
        guard MTCallTapeStore.save(item) else { failures.insert(uuid); return }
        let token = UUID()
        let tape = MTCallTape(id: uuid, manifest: item, timeline: timeline, onAudio: { [weak self] in
            guard let self, self.active[uuid]?.token == token else { return }
            MTCallMint.mintSecond()
            self.recording.insert(uuid)
        }) { [weak self] in
            guard let self, self.active[uuid]?.token == token else { return }
            MTCallMint.mintSecond()
            self.recording.remove(uuid)
            MTCallRecordingGate.remove(uuid)
            self.failures.insert(uuid)
        }
        timelines[id] = timeline
        timeline?.mark("record.start")
        active[uuid] = Active(tape: tape, manifestID: id, token: token)
        MTCallRecordingGate.add(uuid, tape)
        if item.partial { failures.insert(uuid) }
        MontanaLog.event("CALL_RECORD start call=\(MTCallTimeline.diagnosticToken(uuid.uuidString))")
    }
    func consent(_ allow: Bool) {
        UserDefaults.standard.set(allow, forKey: MTCallRecordingPolicy.consentKey)
        UserDefaults.standard.set(allow, forKey: MTCallRecordingPolicy.key)
        askConsent = false
        let waiting = pending.values; pending = [:]
        if allow {
            for p in waiting { begin(uuid: p.uuid, id: p.id, chat: p.chat, since: p.since, sendsToChat: p.sendsToChat, timeline: p.timeline) }
            MontanaCall.shared.refreshRecording()
            MTGroupRoom.shared.refreshRecording()
        }
    }
    func changed(_ enabled: Bool) {
        if enabled {
            if !MTCallRecordingPolicy.consented { askConsent = true }
            else { MontanaCall.shared.refreshRecording(); MTGroupRoom.shared.refreshRecording() }
        } else {
            pending = [:]; askConsent = false
            for id in Array(active.keys) { finish(id, deliver: false) }
        }
    }
    func video(_ track: RTCVideoTrack?, uuid: UUID, source: String) {
        guard var entry = active[uuid] else { return }
        if let track, entry.videos.contains(where: { $0.0 === track }) { return }
        let previous = entry.videos.filter { $0.1.source == source }
        entry.videos.removeAll { $0.1.source == source }
        for (old, sink) in previous { old.remove(sink) }
        guard let track else { active[uuid] = entry; return }
        let renderer = MTCallRecordingVideo(entry.tape, source: source)
        entry.videos.append((track, renderer)); active[uuid] = entry
        track.add(renderer)
    }
    func finish(_ uuid: UUID, deliver: Bool = true) {
        MTCallMint.mintSecond()
        pending.removeValue(forKey: uuid)
        if pending.isEmpty { askConsent = false }
        MTCallRecordingGate.remove(uuid)
        recording.remove(uuid); failures.remove(uuid)
        if closing[uuid] != nil { if deliver { closing[uuid] = true }; return }
        guard let entry = active.removeValue(forKey: uuid) else {
            if deliver, let item = closed.removeValue(forKey: uuid) { export(item) }
            return
        }
        closing[uuid] = deliver
        for (track, renderer) in entry.videos { track.remove(renderer) }
        entry.tape.finish { item in Task { @MainActor in
            let deliver = self.closing.removeValue(forKey: uuid) ?? true
            if deliver { self.export(item) }
            else {
                self.closed[uuid] = item
                if MTCallRecordingPolicy.enabled, let p = self.pending.removeValue(forKey: uuid) {
                    self.begin(uuid: p.uuid, id: p.id, chat: p.chat, since: p.since, sendsToChat: p.sendsToChat, timeline: p.timeline)
                    MontanaCall.shared.refreshRecording(); MTGroupRoom.shared.refreshRecording()
                }
            }
        } }
    }
    func recover() {
        guard !recovering, ChatStore.live?.historyLoaded == true, let owner = MontanaSeed.twin else { return }
        recovering = true
        Task {
            defer { recovering = false }
            let items = await Task.detached(priority: .utility) {
                MTCallTapeStore.ids.compactMap(MTCallTapeStore.load).filter { $0.owner == owner && $0.sendsToChat == true && !$0.queued && $0.unavailableReason == nil }
            }.value
            guard MontanaSeed.twin == owner else { return }
            let reserved = Set(active.values.map(\.manifestID)).union(MontanaCall.shared.recordingReservedIDs)
            for var item in items where !reserved.contains(item.id) {
                if !item.stopped { item.stopped = true; item.partial = true; _ = MTCallTapeStore.save(item) }
                export(item)
            }
        }
    }
    private func export(_ item: MTCallTapeManifest) {
        guard item.sendsToChat == true, !item.queued, item.unavailableReason == nil, !exporting.contains(item.id) else { return }
        if !exporting.isEmpty {
            if !backlog.contains(where: { $0.id == item.id }) { backlog.append(item) }
            return
        }
        guard MontanaSeed.twin == item.owner else { return }
        exporting.insert(item.id)
        Task {
            let timeline = timelines[item.id]
            timeline?.mark("record.export.begin")
            let assertion = MTSendAssertion("call-recording")
            defer {
                assertion.end(); exporting.remove(item.id); timelines.removeValue(forKey: item.id)
                backlog.removeAll { $0.owner != MontanaSeed.twin }
                if !backlog.isEmpty { let next = backlog.removeFirst(); export(next) }
            }
            do {
                let result = try await Task.detached(priority: .utility) { try await MTCallTapeExport.run(item) }.value
                guard MontanaSeed.twin == item.owner, let store = ChatStore.live else { return }
                // Identity is checked before the file and its row enter the live person's media store, with no await between.
                let file = result.file, chat = MTSamePair.root(item.chat)
                if result.url != MontanaMediaStore.url(file), !MontanaMediaStore.adopt(from: result.url, name: file) {
                    throw CocoaError(.fileWriteUnknown)
                }
                if result.video { MontanaVideoMark.mark(file) }
                let exists = (store.messages[chat] ?? []).contains { ChatStore.rowMediaFile($0) == file }
                if !exists {
                    let caption = result.partial ? String(localized: "Call recording — incomplete") : String(localized: "Call recording")
                    let row = Message(text: caption, isFromMe: true, time: nowHHMM(),
                        videoFile: result.video ? file : nil, audioFile: result.video ? nil : file, audioDuration: result.duration, deliveryStatus: .sending)
                    guard store.append(chat, row) else { return }
                }
                MontanaMediaSender(store: store).sendMediaOverE2E(fileName: file, kind: result.video ? "vid" : "aud",
                    caption: result.partial ? String(localized: "Call recording — incomplete") : String(localized: "Call recording"), peer: chat, chatKey: chat)
                var done = item; done.queued = true; done.output = file; done.partial = result.partial
                _ = MTCallTapeStore.save(done)
                timeline?.mark("record.export.ok", "video=\(result.video ? 1 : 0) partial=\(result.partial ? 1 : 0)")
                MontanaLog.event("CALL_RECORD queued video=\(result.video ? 1 : 0) partial=\(result.partial ? 1 : 0)")
            } catch {
                let empty = (error as? MTCallTapeExport.Failure) == .noSamples
                let error = error as NSError
                timeline?.mark("record.export.fail", "domain=\(error.domain) code=\(error.code) empty=\(empty ? 1 : 0)")
                MontanaLog.event("CALL_RECORD export.fail domain=\(error.domain) code=\(error.code) empty=\(empty ? 1 : 0)")
                // A failed export is visible in its own chat, once. The files stay local and foreground recovery retries.
                if MontanaSeed.twin == item.owner, let store = ChatStore.live {
                    var failed = MTCallTapeStore.load(item.id) ?? item
                    if empty, failed.unavailableReason == nil { failed.failureMid = nil }
                    if empty { failed.unavailableReason = "no-audio-samples" }
                    let mid = failed.failureMid ?? ChatStore.mintMid().mid
                    failed.failureMid = mid
                    if MTCallTapeStore.save(failed), !(store.messages[item.chat] ?? []).contains(where: { $0.msgId == "mid:" + mid }) {
                        let text = empty
                            ? String(localized: "This call has no recording because no audio was received.")
                            : String(localized: "The call recording could not be saved yet. Montana will try again when you return to the app.")
                        _ = store.append(item.chat, Message(text: text,
                            isFromMe: true, time: nowHHMM(), deliveryStatus: .sent, msgId: "mid:" + mid))
                    }
                }
            }
        }
    }
}

enum MTCallTapeExport {
    enum Failure: Error { case noSamples }
    struct Result { var file: String; var url: URL; var video: Bool; var duration: Double; var partial: Bool }
    static func run(_ item: MTCallTapeManifest) async throws -> Result {
        guard !item.files.isEmpty else { throw Failure.noSamples }
        let composition = AVMutableComposition()
        var videoTracks: [AVCompositionTrack] = []
        var audioParameters: [AVAudioMixInputParameters] = []
        var voiceTracks: [AVCompositionTrack] = []
        var partial = item.partial
        var joined: [String: AVMutableCompositionTrack] = [:]
        for part in item.files {
            let asset = AVURLAsset(url: MTCallTapeStore.directory.appendingPathComponent(part.name))
            do {
                let tracks = try await asset.load(.tracks)
                for original in tracks {
                    let range = try await original.load(.timeRange)
                    guard range.duration > .zero else { continue }
                    let key = part.source + ":" + original.mediaType.rawValue
                    let previous = joined[key]
                    guard let track = previous ?? composition.addMutableTrack(withMediaType: original.mediaType, preferredTrackID: kCMPersistentTrackID_Invalid) else { continue }
                    joined[key] = track
                    let start = previous == nil ? range.start : CMTimeMaximum(range.start, track.timeRange.end)
                    guard start < range.end else { partial = true; continue }
                    let remaining = CMTimeRange(start: start, end: range.end)
                    try track.insertTimeRange(remaining, of: original, at: start)
                    if previous == nil {
                        if original.mediaType == .video { videoTracks.append(track) }
                        else if original.mediaType == .audio {
                            let volume = AVMutableAudioMixInputParameters(track: track)
                            volume.setVolume(0.5, at: .zero); audioParameters.append(volume); voiceTracks.append(track)
                        }
                    }
                }
            } catch { partial = true }
        }
        guard composition.duration.isNumeric, composition.duration > .zero, !audioParameters.isEmpty else { throw CocoaError(.fileReadCorruptFile) }
        let video = !videoTracks.isEmpty
        let file = "call_" + item.id + (video ? ".mp4" : ".m4a")
        let destination = MontanaMediaStore.url(file)
        if FileManager.default.fileExists(atPath: destination.path) {
            let existing = AVURLAsset(url: destination)
            let duration = try await existing.load(.duration)
            guard duration > .zero else { throw CocoaError(.fileReadCorruptFile) }
            return Result(file: file, url: destination, video: video, duration: duration.seconds, partial: partial)
        }
        // THE PICTURE IS NOT ENCODED TWICE (16.10.2026, No.70, the fleet's heat): the call's one video track was written once,
        // at 640x360, by the live writer, and the export drew it again into 960x540 and encoded it a second time on the
        // caller's phone, the whole length of the call, after the call. One track is now copied as it was written and only the
        // voices are mixed and encoded; a copy the system refuses (a resumed part in another format) takes the drawing road.
        if video, videoTracks.count == 1 {
            let copy = MTCallTapeStore.directory.appendingPathComponent(UUID().uuidString + ".mp4")
            if (try? await copyPicture(composition, picture: videoTracks[0], voices: voiceTracks, levels: audioParameters, to: copy)) == true {
                return Result(file: file, url: copy, video: true, duration: composition.duration.seconds, partial: partial)
            }
            try? FileManager.default.removeItem(at: copy)
        }
        guard let export = AVAssetExportSession(asset: composition, presetName: video ? AVAssetExportPreset960x540 : AVAssetExportPresetAppleM4A) else { throw CocoaError(.fileWriteUnknown) }
        let audio = AVMutableAudioMix(); audio.inputParameters = audioParameters; export.audioMix = audio
        if video {
            let instructions = AVMutableVideoCompositionInstruction()
            instructions.timeRange = CMTimeRange(start: .zero, duration: composition.duration)
            instructions.backgroundColor = CGColor(gray: 0, alpha: 1)
            let columns = videoTracks.count == 1 ? 1 : 2
            let rows = Int(ceil(Double(videoTracks.count) / Double(columns)))
            let cellW = 960 / CGFloat(columns), cellH = 540 / CGFloat(rows)
            instructions.layerInstructions = videoTracks.enumerated().map { index, track in
                let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: track)
                let scale = min(cellW / 640, cellH / 360)
                let x = CGFloat(index % columns) * cellW + (cellW - 640 * scale) / 2
                let y = CGFloat(index / columns) * cellH + (cellH - 360 * scale) / 2
                layer.setTransform(CGAffineTransform(scaleX: scale, y: scale).concatenating(CGAffineTransform(translationX: x, y: y)), at: .zero)
                return layer
            }
            let videoComposition = AVMutableVideoComposition()
            videoComposition.renderSize = CGSize(width: 960, height: 540)
            videoComposition.frameDuration = CMTime(value: 1, timescale: 10)
            videoComposition.instructions = [instructions]; export.videoComposition = videoComposition
        }
        // A fresh path on each attempt: a failed native export never overwrites a retained source or finished file.
        let temporary = MTCallTapeStore.directory.appendingPathComponent(UUID().uuidString + (video ? ".mp4" : ".m4a"))
        export.outputURL = temporary; export.outputFileType = video ? .mp4 : .m4a
        await export.export()
        guard export.status == .completed else { throw export.error ?? CocoaError(.fileWriteUnknown) }
        return Result(file: file, url: temporary, video: video, duration: composition.duration.seconds, partial: partial)
    }
    /// The one video track copied sample for sample; the voices mixed once at their levels and encoded once.
    private static func copyPicture(_ composition: AVComposition, picture: AVCompositionTrack, voices: [AVCompositionTrack],
                                    levels: [AVAudioMixInputParameters], to url: URL) async throws -> Bool {
        guard let hint = try await picture.load(.formatDescriptions).first, !voices.isEmpty else { return false }
        let reader = try AVAssetReader(asset: composition)
        let frames = AVAssetReaderTrackOutput(track: picture, outputSettings: nil)
        frames.alwaysCopiesSampleData = false
        let mix = AVMutableAudioMix(); mix.inputParameters = levels
        let sound = AVAssetReaderAudioMixOutput(audioTracks: voices, audioSettings: [AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: 48_000, AVNumberOfChannelsKey: 1, AVLinearPCMBitDepthKey: 32, AVLinearPCMIsFloatKey: true,
            AVLinearPCMIsNonInterleaved: false, AVLinearPCMIsBigEndianKey: false])
        sound.audioMix = mix
        guard reader.canAdd(frames), reader.canAdd(sound) else { return false }
        reader.add(frames); reader.add(sound)
        let copyWriter = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let framesIn = AVAssetWriterInput(mediaType: .video, outputSettings: nil, sourceFormatHint: hint)
        let soundIn = AVAssetWriterInput(mediaType: .audio, outputSettings: [AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 48_000, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 64_000])
        guard copyWriter.canAdd(framesIn), copyWriter.canAdd(soundIn) else { return false }
        copyWriter.add(framesIn); copyWriter.add(soundIn)
        guard reader.startReading(), copyWriter.startWriting() else { return false }
        copyWriter.startSession(atSourceTime: .zero)
        // A lane is touched only on the one serial pump below -- the order the reader and the writer require of these objects.
        final class Lane: @unchecked Sendable {
            let input: AVAssetWriterInput, output: AVAssetReaderOutput
            var finished = false
            init(_ input: AVAssetWriterInput, _ output: AVAssetReaderOutput) { self.input = input; self.output = output }
        }
        let pump = DispatchQueue(label: "montana.call.recording.copy")
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            let group = DispatchGroup()
            for lane in [Lane(framesIn, frames), Lane(soundIn, sound)] {
                group.enter()
                lane.input.requestMediaDataWhenReady(on: pump) {
                    while !lane.finished, lane.input.isReadyForMoreMediaData {
                        guard let sample = lane.output.copyNextSampleBuffer(), lane.input.append(sample) else {
                            lane.finished = true; lane.input.markAsFinished(); group.leave(); return
                        }
                    }
                }
            }
            group.notify(queue: pump) { done.resume() }
        }
        guard reader.status == .completed else { copyWriter.cancelWriting(); return false }
        await copyWriter.finishWriting()
        return copyWriter.status == .completed
    }
}

struct MTCallRecordingConsent: ViewModifier {
    @ObservedObject private var recorder = MTCallRecording.shared
    func body(content: Content) -> some View {
        content.alert("Record calls automatically?", isPresented: $recorder.askConsent) {
            Button("Record calls") { recorder.consent(true) }
            Button("Do not record", role: .cancel) { recorder.consent(false) }
        } message: {
            Text("Audio and video are saved on this phone. For a one-to-one call, only the caller sends the recording to the chat. Room participants send their own recordings. Participants are notified. You can turn this off in Privacy at any time.")
        }
    }
}

struct MTCallRecordingStatus: View {
    @ObservedObject private var recorder = MTCallRecording.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var dimmed = false
    var body: some View {
        if !recorder.recording.isEmpty {
            Circle().fill(.red).frame(width: 8, height: 8)
                .opacity(reduceMotion || !dimmed ? 1 : 0.25)
                .animation(reduceMotion ? nil : .easeInOut(duration: 0.65).repeatForever(autoreverses: true), value: dimmed)
                .onAppear { dimmed = true }
                .accessibilityLabel(Text(recorder.failures.isEmpty ? "Recording" : "Recording interrupted"))
        }
    }
}
