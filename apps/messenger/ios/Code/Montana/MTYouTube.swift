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

/// THE ONE PLAYER: YouTube's embed on a card over the screen the person is on while it loads, then in the system's picture in
/// picture. The card stays the player's place where the system gives no picture in picture (a setting, a page that refuses it)
/// and when the system's window brings the video back to the app; its cross ends the player, and so does the system's window
/// closed. The card answers the finger with its bar alone: the bar carries it, and the player under the bar is YouTube's.
@MainActor final class MTYouTubeStage: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
    static let shared = MTYouTubeStage()
    private static let hold = "youtube"   // the player's name in the sound's ledger (MontanaAudioSession)
    private static let channel = "tube"
    /// The player's own events, told to the diary by their names alone -- no video is ever named there -- and the page's
    /// presentation mode with whether the video plays at that moment: the system's window closed pauses it, its way back does not.
    private static let listen = "['playing','pause','ended','error'].forEach(function(e){document.addEventListener(e,function(){try{window.webkit.messageHandlers.tube.postMessage(e)}catch(x){}},true)});document.addEventListener('webkitpresentationmodechanged',function(ev){try{var v=ev.target;window.webkit.messageHandlers.tube.postMessage('mode '+v.webkitPresentationMode+(v.paused?' paused':' playing'))}catch(x){}},true)"
    private static let floatScript = "(function(){var v=document.querySelector('video');if(!v){return 'no video'}if(!v.webkitSupportsPresentationMode){return 'no presentation mode'}if(!v.webkitSupportsPresentationMode('picture-in-picture')){return 'not supported'}v.webkitSetPresentationMode('picture-in-picture');return 'asked'})()"
    private static let pauseScript = "document.querySelectorAll('video').forEach(function(v){v.pause()})"
    private static let resumeScript = "document.querySelectorAll('video').forEach(function(v){v.play()})"

    private(set) var id: String?
    private var web: WKWebView?
    private var card: UIView?
    private weak var name: UILabel?
    private var floating = false   // the video stands in the system's window: the card is out of sight and out of the finger's way
    private var floated = false    // the first play went to the system's window; a video brought back from it stays on the card
    private var leaving: NSObjectProtocol?

    override init() {
        super.init()
        // NEVER A BACKGROUND PLAYER: the app leaving the screen rests a video on the card; the system's window is seen and plays on.
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
            // The same video asked again plays on where it stands -- on the card, or in the system's window.
            MontanaTrace.mark("youtube", "again floating=\(floating ? 1 : 0)")
            if !floating { show() }
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
        let c = card(w, in: window)
        card = c
        window.addSubview(c)
        c.center = spot(near: CGPoint(x: window.bounds.maxX, y: 0), in: window)
        c.alpha = 0
        c.transform = CGAffineTransform(scaleX: 0.92, y: 0.92)
        UIView.animate(withDuration: 0.25) { c.alpha = 1; c.transform = .identity }
        var q = URLRequest(url: u)
        q.setValue(MTYouTube.referer, forHTTPHeaderField: "Referer")
        w.load(q)
        MontanaTrace.mark("youtube", "open")
        // USER-DATA: the video's own title and channel, as YouTube names them
        name?.text = title ?? channel
        guard title == nil, let page = MTYouTube.watch(id) else { return }
        // A link opened from words alone carries no name: the video's name is asked the way a card asks it (oEmbed).
        Task { [weak self] in
            let said = await MTLinkPreviewBuilder.youtubeWords(page)
            guard let self, self.id == id else { return }
            // USER-DATA: the video's own title, as YouTube names it
            self.name?.text = said.0 ?? said.1
        }
    }

    /// The video goes into the system's picture in picture; the page says whether it went, and the diary keeps its word.
    func float() {
        web?.evaluateJavaScript(Self.floatScript) { said, _ in
            MontanaTrace.mark("youtube", "picture in picture " + ((said as? String) ?? "no answer"))
        }
    }
    /// A sound of ours starts, or the app leaves the screen with the video on the card: it rests where it stands.
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
    /// The player ends: its sound let go, its page emptied (the system's window goes with its video), its card away.
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
        floated = false
        let c = card
        card = nil
        UIView.animate(withDuration: 0.2, animations: { c?.alpha = 0 }) { _ in c?.removeFromSuperview() }
    }
    /// The mark at the right: the video leaves for YouTube itself, and our player ends.
    private func leave() {
        guard let id, let u = MTYouTube.watch(id) else { return }
        MontanaTrace.mark("youtube", "leave to the service by=mark")
        end("leave")
        UIApplication.shared.open(u)
    }

    private func show() {
        guard let c = card else { return }
        c.superview?.bringSubviewToFront(c)
        c.isUserInteractionEnabled = true
        if let window = c.superview { c.center = spot(near: c.center, in: window) }
        UIView.animate(withDuration: 0.25) { c.alpha = 1; c.transform = .identity }
    }
    private func tuck() {
        guard let c = card else { return }
        c.isUserInteractionEnabled = false
        UIView.animate(withDuration: 0.25) { c.alpha = 0; c.transform = CGAffineTransform(scaleX: 0.92, y: 0.92) }
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
            if !floating, !floated { floated = true; float() }   // the first play goes into the system's window at once
        } else if ["pause", "ended", "error"].contains(said) {
            quiet()
        } else if said.hasPrefix("mode picture-in-picture") {
            floating = true
            tuck()
        } else if said.hasPrefix("mode inline"), floating {
            // The system's window closed pauses its video and ends the player; its way back to the app plays on, on the card.
            floating = false
            if said.hasSuffix("paused") { end("the system's window closed") } else { show() }
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
    /// The card: a bar of the platform's marks over YouTube's player -- the cross at the left, the system's window and YouTube
    /// itself at the right, the video's name between -- on the platform's thick material; the player under the bar, 200 points
    /// tall at least, its corners its own.
    private func card(_ w: WKWebView, in window: UIWindow) -> UIView {
        let width = min(window.bounds.width - 24, 480)
        let inset: CGFloat = 6
        let bar: CGFloat = 44
        let playerWidth = width - 2 * inset
        let tall = max(200, (playerWidth * 9 / 16).rounded())
        let c = UIView(frame: CGRect(x: 0, y: 0, width: width, height: bar + tall + inset))
        c.layer.shadowColor = UIColor.black.cgColor
        c.layer.shadowOpacity = 0.22
        c.layer.shadowRadius = 18
        c.layer.shadowOffset = CGSize(width: 0, height: 8)
        let ground = UIVisualEffectView(effect: UIBlurEffect(style: .systemThickMaterial))
        ground.frame = c.bounds
        ground.layer.cornerRadius = 18
        ground.layer.cornerCurve = .continuous
        ground.clipsToBounds = true
        c.addSubview(ground)
        let grip = UIView(frame: CGRect(x: 0, y: 0, width: width, height: bar))
        grip.addGestureRecognizer(UIPanGestureRecognizer(target: self, action: #selector(carried(_:))))
        ground.contentView.addSubview(grip)
        let label = UILabel(frame: CGRect(x: bar + 4, y: 0, width: width - 3 * bar - 8, height: bar))
        label.font = .preferredFont(forTextStyle: .footnote)
        label.textColor = .secondaryLabel
        label.lineBreakMode = .byTruncatingTail
        ground.contentView.addSubview(label)
        name = label
        let close = mark("xmark", String(localized: "Close", bundle: MTLanguage.bundle)) { [weak self] in self?.end("cross") }
        close.frame = CGRect(x: 0, y: 0, width: bar, height: bar)
        let lift = mark("pip.enter", String(localized: "Picture in Picture", bundle: MTLanguage.bundle)) { [weak self] in self?.float() }
        lift.frame = CGRect(x: width - 2 * bar, y: 0, width: bar, height: bar)
        let out = mark("arrow.up.right.square", String(localized: "Open in YouTube", bundle: MTLanguage.bundle)) { [weak self] in self?.leave() }
        out.frame = CGRect(x: width - bar, y: 0, width: bar, height: bar)
        for b in [close, lift, out] { ground.contentView.addSubview(b) }
        w.frame = CGRect(x: inset, y: bar, width: playerWidth, height: tall)
        ground.contentView.addSubview(w)
        return c
    }
    /// A glyph of the platform on 44 points, its name for VoiceOver.
    private func mark(_ glyph: String, _ said: String, _ act: @escaping () -> Void) -> UIButton {
        var look = UIButton.Configuration.plain()
        look.image = UIImage(systemName: glyph, withConfiguration: UIImage.SymbolConfiguration(textStyle: .body))
        let b = UIButton(configuration: look, primaryAction: UIAction { _ in act() })
        b.tintColor = .label
        b.accessibilityLabel = said
        return b
    }
    /// The card goes where the finger throws it and settles at the nearest edge of the screen, as the system's own window does.
    @objc private func carried(_ g: UIPanGestureRecognizer) {
        guard let c = card, let window = c.superview else { return }
        let t = g.translation(in: window)
        c.center = CGPoint(x: c.center.x + t.x, y: c.center.y + t.y)
        g.setTranslation(.zero, in: window)
        guard [.ended, .cancelled].contains(g.state) else { return }
        let v = g.velocity(in: window)
        let to = spot(near: CGPoint(x: c.center.x + v.x / 5, y: c.center.y + v.y / 5), in: window)
        UIView.animate(withDuration: 0.4, delay: 0, usingSpringWithDamping: 0.85, initialSpringVelocity: 0,
                       options: [.allowUserInteraction, .beginFromCurrentState]) { c.center = to }
    }
    /// The card's resting place nearest a point, inside the safe area: a corner where the screen is wide enough for it to stand
    /// aside, the top or the bottom across the middle where it is not.
    private func spot(near p: CGPoint, in window: UIView) -> CGPoint {
        guard let c = card else { return p }
        let safe = window.bounds.inset(by: window.safeAreaInsets).insetBy(dx: 12, dy: 8)
        let halfW = c.bounds.width / 2
        let halfH = c.bounds.height / 2
        let left = safe.minX + halfW
        let right = safe.maxX - halfW
        let x = right - left < halfW ? window.bounds.midX : (p.x < window.bounds.midX ? left : right)
        let y = p.y < window.bounds.midY ? safe.minY + halfH : safe.maxY - halfH
        return CGPoint(x: x, y: y)
    }
}
