// MTYouTube.swift -- YouTube inside Montana. The author's words 17.10.2026 MSK: 16:3x-16:4x, a social network that plays the music
// of a YouTube link inside itself «breaks nothing -- do it so here», «search YouTube's music at once, and a link plays inside our
// app»; 18:0x, integrate it natively -- the mini player works, no extra windows in the chat, everything plays at once, nothing takes
// the sound before the person's own tap and nothing cuts another app's music; 18:1x, «YouTube in our app like picture in picture»,
// «the native picture in picture, the system's, when YouTube is turned on».
// A YouTube link in a letter, in a card or on a wall, and a row the music page's search found, play in YouTube's own player, which
// goes into the platform's own picture in picture the moment it plays: the person stays on the screen they were on, and the
// system's window -- its play, its skip, its way back -- is the mini player over our app and over every other one.
//
// THE PLAYER IS YOUTUBE'S OWN AND IT IS SEEN (YouTube API Services: Developer Policies and Required Minimum Functionality, read
// 17.10.2026). The embedded player loads in the platform's web view («WKWebView or SFSafariViewController») and names this app by
// the Referer -- «the domain name must be your application identifier». On the card it is at least 200 by 200 points («a viewport
// that is at least 200px by 200px») and nothing lies over it or clips it («You must not display overlays, frames, or other visual
// elements in front of any part of a YouTube embedded player»). It plays only once it is seen («must not initiate an automatic
// playback until the player is visible»): the card stands on the screen before the player loads. It is never a background player
// («a player that is not displayed in the page, tab, or screen that the user is viewing»): the system's picture in picture is a
// window on the very screen the person looks at, and a card nobody sees rests -- the app leaving the screen rests a video on the
// card. No byte of a video is kept («must not ... download ... cache, or store copies of YouTube audiovisual content») and its
// sound is never taken apart from its picture. The web view's store is the ephemeral one: no cookie outlives the player.
//
// THE SYSTEM'S WINDOW IS WEBKIT'S OWN (read 17.10.2026 in WebKit's source): the page's video enters picture in picture by its
// presentation mode, which HTMLVideoElement::setPresentationMode grants on a person's gesture (MediaElementSession::
// fullscreenPermitted) and MediaElementSession::allowsPictureInPicture grants by the configuration's
// allowsPictureInPictureMediaPlayback; WKWebView's evaluateJavaScript runs «forceUserGesture:YES» -- the tap that opened the
// player is the gesture.
//
// THE SOUND IS TAKEN BY THE TAP AND GIVEN BACK BY THE PAUSE: no web view exists before the tap; the player holds the sound by the
// name «youtube» while it plays (MontanaAudioSession), lets it go at its pause and its end, and the last of ours to let go lowers
// the session with the word that lets another app's music go on. A sound of ours that starts rests YouTube where it stands: one
// sound at a time.
//
// THE SEARCH GOES BY OUR OWN SERVICE (MTPhoneService.base): YouTube's key lives there, never in this open code, where anyone could
// spend its daily count; YouTube sees the service ask, never the person's address. The words are asked once they settle -- the
// count is a hundred searches a day for the whole app -- and a service that does not answer shows no YouTube row at all.

import SwiftUI
import UIKit
import WebKit

enum MTYouTube {
    /// One video YouTube's search found: its id, its title and its channel as YouTube gives them, its length in seconds.
    struct Found: Decodable, Equatable {
        let id: String
        let t: String
        let c: String
        let s: Int
    }
    private struct Answer: Decodable { let items: [Found] }

    /// The music page's rows of YouTube: the head that names the source, then a row for each video.
    static let headRow = "youtube:"
    static let rowPrefix = "youtube:v:"
    static func rows(_ list: [Found]) -> [Chat] {
        guard !list.isEmpty else { return [] }
        let head = Chat(name: headRow, lastMessage: "", time: "", unread: 0, status: "", convId: headRow)
        return [head] + list.map { f in Chat(name: rowPrefix + f.id, lastMessage: "", time: "", unread: 0, status: "", convId: rowPrefix + f.id) }
    }
    static func found(_ c: Chat, in list: [Found]) -> Found? {
        guard c.name.hasPrefix(rowPrefix) else { return nil }
        let id = String(c.name.dropFirst(rowPrefix.count))
        return list.first { f in f.id == id }
    }

    /// A tap on a link: a YouTube video plays here; any other link is not this player's (false).
    static func play(_ url: URL, title: String? = nil) -> Bool {
        guard let tube = MTLinkPreviewBuilder.youtube(url) else { return false }
        play(tube.id, title: title)
        return true
    }
    /// A tap on a video: YouTube's own player over the screen the person is on, then the system's picture in picture.
    static func play(_ id: String, title: String? = nil, channel: String? = nil) {
        onMain { MTYouTubeStage.shared.play(id, title: title, channel: channel) }
    }
    /// YouTube sounds now: the sound's ledger asks the player itself (MontanaAudioSession).
    static var sounds = false
    /// A sound of ours starts: YouTube rests where it stands, its window stays -- one sound at a time.
    static func rest() {
        guard sounds else { return }
        onMain { MTYouTubeStage.shared.rest() }
    }
    /// The stage is the screen's and lives on the main thread; every caller is a tap or a player's start.
    private static func onMain(_ work: @escaping @MainActor () -> Void) {
        if Thread.isMainThread { MainActor.assumeIsolated { work() } }
        else { DispatchQueue.main.async { MainActor.assumeIsolated { work() } } }
    }

    /// YouTube's music for the words, found by our service; nil when the service does not answer.
    static func search(_ words: String) async -> [Found]? {
        var c = URLComponents(string: MTPhoneService.base + "/tube/search")
        c?.queryItems = [URLQueryItem(name: "q", value: words)]
        guard let u = c?.url else { return nil }
        var q = URLRequest(url: u, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)
        q.httpMethod = "GET"
        guard let got = try? await URLSession.shared.data(for: q), (got.1 as? HTTPURLResponse)?.statusCode == 200,
              let answer = try? JSONDecoder().decode(Answer.self, from: got.0) else { return nil }
        return answer.items.filter { f in f.id.count == 11 }
    }

    /// The Referer the player is named by: this app's own identifier as a web address (Required Minimum Functionality).
    static var referer: String { "https://" + (Bundle.main.bundleIdentifier ?? "montana").lowercased() }
    /// The embedded player's own page on the privacy-enhanced host: inline, and starting at once -- the person's tap asked for it.
    static func embed(_ id: String) -> URL? {
        var c = URLComponents(string: "https://www.youtube-nocookie.com/embed/" + id)
        c?.queryItems = [URLQueryItem(name: "playsinline", value: "1"), URLQueryItem(name: "autoplay", value: "1"),
                         URLQueryItem(name: "rel", value: "0")]
        return c?.url
    }
    /// The video on YouTube itself, for the mark that leaves for it.
    static func watch(_ id: String) -> URL? { URL(string: "https://www.youtube.com/watch?v=" + id) }

    /// The source named over its rows (Developer Policies: «make clear to the viewer that YouTube is the source»).
    static var head: some View {
        Label { Text("YouTube") } icon: { Image(systemName: "play.rectangle.fill") }
            .font(.footnote.weight(.semibold))
            .foregroundColor(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16).padding(.top, 14).padding(.bottom, 4)
    }
}

/// THE ONE PLAYER: YouTube's embed goes straight into the system's picture in picture (the author's words 18.10.2026 00:2x MSK:
/// «at once a native picture in picture, without hand-made buttons before it, systemic and correct»). While its first frame loads
/// the player stands where the system's window will rest -- YouTube's own player and nothing of ours over it or around it, at the
/// least size YouTube's policies allow -- and the first «playing» lifts it into the system's window. The way back from the window
/// opens the system's own full-screen player, whose Done ends ours; the window closed ends it as well. A page that gives no
/// picture in picture plays in the system's full-screen player, and one that gives neither is opened by YouTube itself.
/// THE WINDOW'S SIZE IS THE SYSTEM'S (the author's word 00:3x: «the size as the video call's»): the video call's window is filled by
/// this app's own frames (MTFloatVideoView, sample buffers), and YouTube's picture never reaches the app as frames -- its policies
/// forbid taking the stream apart, and an app in the background may not draw -- so YouTube's window is the system's own window for
/// a video, sized by the system for the video's proportion and kept where the person pinches it.
@MainActor final class MTYouTubeStage: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
    static let shared = MTYouTubeStage()
    private static let hold = "youtube"   // the player's name in the sound's ledger (MontanaAudioSession)
    private static let channel = "tube"
    /// The player's own events, told to the diary by their names alone -- no video is ever named there -- and the page's
    /// presentation mode with whether the video plays at that moment: the system's window closed pauses it, its way back does not.
    private static let listen = "['playing','pause','ended','error'].forEach(function(e){document.addEventListener(e,function(){try{window.webkit.messageHandlers.tube.postMessage(e)}catch(x){}},true)});document.addEventListener('webkitpresentationmodechanged',function(ev){try{var v=ev.target;window.webkit.messageHandlers.tube.postMessage('mode '+v.webkitPresentationMode+(v.paused?' paused':' playing'))}catch(x){}},true)"
    private static let floatScript = "(function(){var v=document.querySelector('video');if(!v){return 'no video'}if(!v.webkitSupportsPresentationMode){return 'no presentation mode'}if(!v.webkitSupportsPresentationMode('picture-in-picture')){return 'not supported'}v.webkitSetPresentationMode('picture-in-picture');return 'asked'})()"
    private static let wholeScript = "(function(){var v=document.querySelector('video');if(!v){return 'no video'}if(v.webkitSupportsPresentationMode&&v.webkitSupportsPresentationMode('fullscreen')){v.webkitSetPresentationMode('fullscreen');return 'asked'}if(v.webkitEnterFullscreen){v.webkitEnterFullscreen();return 'entered'}return 'not supported'})()"
    private static let pauseScript = "document.querySelectorAll('video').forEach(function(v){v.pause()})"
    private static let resumeScript = "document.querySelectorAll('video').forEach(function(v){v.play()})"

    private(set) var id: String?
    private var web: WKWebView?
    private var stage: UIView?
    private var floating = false   // the video stands in the system's window: the stage is out of sight and out of the finger's way
    private var whole = false      // the video stands in the system's full-screen player
    private var floated = false    // the first play went to the system's window already
    private var leaving: NSObjectProtocol?

    override init() {
        super.init()
        // NEVER A BACKGROUND PLAYER: the app leaving the screen rests a video on the stage; the system's window is seen and plays on.
        leaving = NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification, object: nil,
                                                         queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, !self.floating else { return }
                self.rest()
            }
        }
    }

    func play(_ id: String, title: String?, channel: String?) {
        guard !MontanaAudioSession.refusedUnderCall("YouTube") else { return }
        if self.id == id, let w = web {
            // The same video asked again plays on where it stands -- in the system's window, its full-screen player, or the stage.
            MontanaTrace.mark("youtube", "again floating=\(floating ? 1 : 0) whole=\(whole ? 1 : 0)")
            if !floating, !whole { show() }
            w.evaluateJavaScript(Self.resumeScript)
            return
        }
        end("another")
        guard let window = Self.window(), let u = MTYouTube.embed(id) else {
            MontanaTrace.mark("youtube", "refused -- no window")
            return
        }
        self.id = id
        let w = Self.webView(self)
        web = w
        let s = stage(w, in: window)
        stage = s
        window.addSubview(s)
        s.alpha = 0
        UIView.animate(withDuration: 0.2) { s.alpha = 1 }
        var q = URLRequest(url: u)
        q.setValue(MTYouTube.referer, forHTTPHeaderField: "Referer")
        w.load(q)
        MontanaTrace.mark("youtube", "open")
    }

    /// The video goes into the system's picture in picture; the page says whether it went, and the diary keeps its word. A page that
    /// gives none plays in the system's full-screen player.
    func float() {
        web?.evaluateJavaScript(Self.floatScript) { [weak self] said, _ in
            let word = (said as? String) ?? "no answer"
            MontanaTrace.mark("youtube", "picture in picture " + word)
            guard word != "asked" else { return }
            self?.enlarge()
        }
    }
    /// The system's own full-screen player; a page that gives none either is opened by YouTube itself.
    private func enlarge() {
        web?.evaluateJavaScript(Self.wholeScript) { [weak self] said, _ in
            let word = (said as? String) ?? "no answer"
            MontanaTrace.mark("youtube", "full screen " + word)
            guard word != "asked", word != "entered" else { return }
            self?.leave()
        }
    }
    /// A sound of ours starts, or the app leaves the screen with the video on the stage: it rests where it stands.
    func rest() {
        guard let w = web else { return }
        w.evaluateJavaScript(Self.pauseScript)
        quiet()
    }
    /// The player is silent: its hold on the sound goes, and the last of ours to let go lowers the session -- another app plays on.
    private func quiet() {
        guard MTYouTube.sounds else { return }
        MTYouTube.sounds = false
        MontanaAudioSession.release(Self.hold)
    }
    /// The player ends: its sound let go, its page emptied (the system's window goes with its video), its stage away.
    func end(_ why: String) {
        guard let w = web else { return }
        MontanaTrace.mark("youtube", "end " + why)
        quiet()
        w.stopLoading()
        w.configuration.userContentController.removeScriptMessageHandler(forName: Self.channel)
        w.loadHTMLString("", baseURL: nil)
        web = nil
        id = nil
        floating = false
        whole = false
        floated = false
        let s = stage
        stage = nil
        UIView.animate(withDuration: 0.2, animations: { s?.alpha = 0 }) { _ in s?.removeFromSuperview() }
    }
    /// The video leaves for YouTube itself, and our player ends: the last way where the system gives the page no player of its own.
    private func leave() {
        guard let id, let u = MTYouTube.watch(id) else { return }
        MontanaTrace.mark("youtube", "leave to the service by=no-player")
        end("leave")
        UIApplication.shared.open(u)
    }

    private func show() {
        guard let s = stage else { return }
        s.superview?.bringSubviewToFront(s)
        s.isUserInteractionEnabled = true
        UIView.animate(withDuration: 0.2) { s.alpha = 1 }
    }
    private func tuck() {
        guard let s = stage else { return }
        s.isUserInteractionEnabled = false
        UIView.animate(withDuration: 0.2) { s.alpha = 0 }
    }

    func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let said = message.body as? String, message.webView === web else { return }
        MontanaTrace.mark("youtube", "player " + said)
        if said == "playing" {
            MTYouTube.sounds = true
            let v = VoicePlayer.shared
            if v.playingFile != nil, !v.paused { v.pause() }   // one sound: our own track rests while YouTube plays
            if MontanaVideoDock.shared.file != nil { MontanaVideoDock.shared.close() }
            MontanaAudioSession.activatePlayback(Self.hold)
            if !floating, !whole, !floated { floated = true; float() }   // the first play goes into the system's window at once
        } else if ["pause", "ended", "error"].contains(said) {
            quiet()
        } else if said.hasPrefix("mode picture-in-picture") {
            floating = true
            whole = false
            tuck()
        } else if said.hasPrefix("mode fullscreen") {
            whole = true
            floating = false
            tuck()
        } else if said.hasPrefix("mode inline") {
            if floating {
                // The system's window closed pauses its video and ends the player; its way back opens the system's full-screen player.
                floating = false
                if said.hasSuffix("paused") { end("the system's window closed") } else { enlarge() }
            } else if whole {
                whole = false
                end("the system's player closed")
            }
        }
    }
    /// A link inside the player (the title, the logo, «Watch on YouTube») goes to YouTube itself.
    func webView(_ w: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
        let away = action.targetFrame == nil ? true : action.navigationType == .linkActivated
        if away, let u = action.request.url, ["https", "http"].contains(u.scheme ?? "") {
            MontanaTrace.mark("youtube", "leave to the service by=player")
            UIApplication.shared.open(u)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    private static func window() -> UIWindow? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { s in s as? UIWindowScene }
        let live = scenes.first { s in s.activationState == .foregroundActive } ?? scenes.first
        return live?.windows.first { w in w.isKeyWindow } ?? live?.windows.first
    }
    private static func webView(_ watch: MTYouTubeStage) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []   // the person's tap was the action: the video starts at once
        cfg.allowsPictureInPictureMediaPlayback = true      // the system's own window (the author's word 18:1x)
        cfg.websiteDataStore = .nonPersistent()
        cfg.userContentController.add(watch, name: channel)
        cfg.userContentController.addUserScript(WKUserScript(source: listen, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let v = WKWebView(frame: .zero, configuration: cfg)
        v.isOpaque = false
        v.backgroundColor = .black
        v.scrollView.isScrollEnabled = false
        v.scrollView.contentInsetAdjustmentBehavior = .never
        v.navigationDelegate = watch
        return v
    }
    /// Where the system's window will rest while the first frame loads: YouTube's own player and nothing of ours -- 200 points tall,
    /// the least YouTube's policies allow, as wide as the video's proportion within the screen -- at the bottom corner above the home
    /// indicator, rounded as the system's window is. The player takes the finger by design: its own controls are YouTube's.
    private func stage(_ w: WKWebView, in window: UIWindow) -> UIView {
        let tall: CGFloat = 200
        let wide = min(window.bounds.width - 24, (tall * 16 / 9).rounded())
        let safe = window.bounds.inset(by: window.safeAreaInsets)
        let s = UIView(frame: CGRect(x: safe.maxX - wide - 12, y: safe.maxY - tall - 12, width: wide, height: tall))
        s.backgroundColor = .black
        s.layer.cornerRadius = 12
        s.layer.cornerCurve = .continuous
        s.clipsToBounds = true
        w.frame = s.bounds
        w.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        s.addSubview(w)
        return s
    }
}
