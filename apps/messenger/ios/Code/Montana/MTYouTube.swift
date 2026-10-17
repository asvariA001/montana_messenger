// MTYouTube.swift -- YouTube inside Montana (the author's words 17.10.2026 16:3x-16:4x MSK: a social network that plays the music of
// a YouTube link inside itself «breaks nothing -- do it so here»; «search YouTube's music at once, and a link plays inside our app»).
// A YouTube link in a letter, in a card or on a wall plays in YouTube's own player on a sheet of ours; the music page's search
// finds YouTube's music under the person's own tracks, and a found row plays the same way.
//
// THE PLAYER IS YOUTUBE'S OWN AND IT IS SEEN (YouTube API Services: Developer Policies and Required Minimum Functionality, read
// 17.10.2026). The embedded player loads in the platform's web view («WKWebView or SFSafariViewController») and names this app by
// the Referer -- «the domain name must be your application identifier»; it is at least 200 by 200 points and nothing is drawn over
// it. It is never a background player («a player that is not displayed in the page, tab, or screen that the user is viewing»): it
// rests when the app leaves the screen and ends with its sheet, and picture in picture is off for the same reason. No byte of a
// video is kept («must not ... download ... cache, or store copies of YouTube audiovisual content») and its sound is never taken
// apart from its picture. The web view's store is the ephemeral one: no cookie outlives the sheet.
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

    private final class Hold { weak var host: UIViewController? }
    /// The player's sheet: the video at its top, its name under it. The app's own music rests while YouTube plays -- one sound.
    static func play(_ id: String, title: String? = nil, channel: String? = nil) {
        if !VoicePlayer.shared.paused { VoicePlayer.shared.pause() }
        let hold = Hold()
        let page = MTYouTubePage(id: id, title: title, channel: channel)
            .environment(\.montanaClose, { if let v = hold.host { MTTop.dismiss(v, kind: "youtube") } })
        let h = MontanaHost.make(page)
        hold.host = h
        h.modalPresentationStyle = .pageSheet
        if let s = h.sheetPresentationController { s.detents = [.medium(), .large()]; s.prefersGrabberVisible = true }
        MontanaTrace.mark("youtube", "open")
        MTTop.present(h, kind: "youtube")
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
    /// The embedded player's own page on the privacy-enhanced host: inline, and starting at once -- the person asked for it.
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

/// The player's page: YouTube's own player across the sheet, the video's name and channel under it, the source in the title; the
/// cross closes it, the mark at the right opens the video in YouTube itself.
struct MTYouTubePage: View {
    let id: String
    let title: String?
    let channel: String?
    @Environment(\.montanaClose) private var montanaClose
    @State private var said: (String?, String?) = (nil, nil)
    var body: some View {
        NavigationStack {
            GeometryReader { g in
                let w = g.size.width
                VStack(alignment: .leading, spacing: 4) {
                    MTYouTubeView(id: id)
                        .frame(width: w, height: max(200, (w * 9 / 16).rounded()))
                    if let t = title ?? said.0, !t.isEmpty {
                        // USER-DATA: the video's own title, as YouTube names it
                        Text(verbatim: t).font(.headline).lineLimit(3)
                            .padding(.horizontal, 16).padding(.top, 10)
                    }
                    if let by = channel ?? said.1, !by.isEmpty {
                        // USER-DATA: the channel's own name, as YouTube names it
                        Text(verbatim: by).font(.subheadline).foregroundColor(.secondary).lineLimit(1)
                            .padding(.horizontal, 16)
                    }
                    Spacer(minLength: 0)
                }
            }
            .navigationTitle(Text("YouTube"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { MontanaCloseMark { montanaClose?() } }
                ToolbarItem(placement: .confirmationAction) {
                    MontanaBarMark(glyph: "arrow.up.right.square", label: "Open in YouTube") {
                        guard let u = MTYouTube.watch(id) else { return }
                        MontanaTrace.mark("youtube", "leave to the service by=mark")
                        montanaClose?()
                        UIApplication.shared.open(u)
                    }
                }
            }
        }
        .task {
            // A link opened from words alone carries no name: the video's name is asked the way a card asks it (oEmbed).
            guard title == nil, let u = MTYouTube.watch(id) else { return }
            said = await MTLinkPreviewBuilder.youtubeWords(u)
        }
    }
}

/// YouTube's embedded player in the platform's web view. It takes the finger by design -- the player's controls are YouTube's own
/// -- and nothing of ours lies over it. A link inside it (the title, the logo, «Watch on YouTube») goes to YouTube itself.
struct MTYouTubeView: UIViewRepresentable {
    let id: String
    func makeCoordinator() -> Watch { Watch() }
    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []
        cfg.allowsPictureInPictureMediaPlayback = false   // a player off the screen would be a background player
        cfg.websiteDataStore = .nonPersistent()
        cfg.userContentController.add(context.coordinator, name: Watch.channel)
        cfg.userContentController.addUserScript(WKUserScript(source: Watch.listen, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let v = WKWebView(frame: .zero, configuration: cfg)
        v.isOpaque = false
        v.backgroundColor = .black
        v.scrollView.isScrollEnabled = false
        v.scrollView.contentInsetAdjustmentBehavior = .never
        v.navigationDelegate = context.coordinator
        context.coordinator.view = v
        if let u = MTYouTube.embed(id) {
            var q = URLRequest(url: u)
            q.setValue(MTYouTube.referer, forHTTPHeaderField: "Referer")
            v.load(q)
        }
        return v
    }
    func updateUIView(_ v: WKWebView, context: Context) {}
    static func dismantleUIView(_ v: WKWebView, coordinator: Watch) {
        v.stopLoading()
        v.configuration.userContentController.removeScriptMessageHandler(forName: Watch.channel)
        v.loadHTMLString("", baseURL: nil)
        coordinator.end()
    }

    @MainActor final class Watch: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        static let channel = "tube"
        /// The player's own events, told to the diary by their names alone -- no video is ever named there.
        static let listen = "['playing','pause','ended','error'].forEach(function(e){document.addEventListener(e,function(){try{window.webkit.messageHandlers.tube.postMessage(e)}catch(x){}},true)})"
        weak var view: WKWebView?
        private var leaving: NSObjectProtocol?
        override init() {
            super.init()
            // NEVER A BACKGROUND PLAYER: the app leaving the screen rests the video where it stands.
            leaving = NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification, object: nil,
                                                             queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.rest() }
            }
        }
        func rest() {
            view?.evaluateJavaScript("document.querySelectorAll('video').forEach(function(v){v.pause()})")
        }
        func end() {
            if let l = leaving { NotificationCenter.default.removeObserver(l) }
            leaving = nil
        }
        func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let said = message.body as? String else { return }
            MontanaTrace.mark("youtube", "player " + said)
            if said == "playing", !VoicePlayer.shared.paused { VoicePlayer.shared.pause() }
        }
        func webView(_ w: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
            let away = action.targetFrame == nil || action.navigationType == .linkActivated
            if away, let u = action.request.url, u.scheme == "https" || u.scheme == "http" {
                MontanaTrace.mark("youtube", "leave to the service by=player")
                UIApplication.shared.open(u)
                decisionHandler(.cancel)
                return
            }
            decisionHandler(.allow)
        }
    }
}
