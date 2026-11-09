package quest.montana.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * YOUTUBE INSIDE MONTANA (iOS MTYouTube.swift at ca615034, the author's words 17.10.2026 and 18.10.2026): a YouTube link in a
 * letter, in a card, on a wall or on a peer's page plays in YouTube's own player inside our mini player. The person stays on the
 * screen they were on, the window stands over it and moves under the finger, and the whole screen is one mark away. Every road
 * comes here (YouTube.play), so it behaves one way everywhere.
 *
 * THE PLAYER IS YOUTUBE'S OWN AND IT IS SEEN (YouTube API Services: Developer Policies and Required Minimum Functionality, as iOS
 * read them 17.10.2026): the embedded player loads in the platform's own web view and names this app by the Referer, the app's
 * identifier as a web address; it is at least 200 by 200 and nothing of ours lies over it; it plays only once it stands on the
 * screen; it is never a background player -- the app leaving the screen rests it. No byte of a video is kept: the page loads
 * without the cache, and the player's cookies, storage and cache are wiped when it ends (iOS: the ephemeral store).
 *
 * THE SOUND IS TAKEN BY THE PLAYING AND GIVEN BACK BY THE PAUSE: no web view exists before the tap; while it plays the player
 * holds the audio focus, our voice, track and round note stand down, and at its pause or end it lets the focus go. A sound of
 * ours that starts rests YouTube where it stands (rest): one sound at a time.
 *
 * THE WHOLE SCREEN: iOS asks WebKit for the system's full-screen player (a script under the tap's gesture). The platform's web
 * view gives a script no gesture, so our mark lays the same mini player over the whole screen, YouTube's player above and our bar
 * under it, nothing over the player; the mark again, or the system's back, brings the mini player back. YouTube's own full-screen
 * button is the web view's own custom view (onShowCustomView), shown over everything; the back leaves it.
 */
object YouTube {
    private const val TALL = 200   // YouTube's least viewport: «at least 200px by 200px»
    private const val BAR = 44     // a target the finger takes at once
    private const val EDGE = 12
    private const val CORNER = 12

    /** The player's own events, told to the diary by their names alone -- no video is ever named there (iOS listen). */
    private const val LISTEN = "(function(){if(window.__mtTube){return}window.__mtTube=1;function say(e){try{window.tube.postMessage(e)}catch(x){}}" +
        "['playing','pause','ended','error'].forEach(function(e){document.addEventListener(e,function(){say(e)},true)});" +
        "var v=document.querySelector('video');if(v&&!v.paused){say('playing')}})()"
    /** What the page shows, for the diary, naming no video: its size, the video's state, YouTube's own error words if any. */
    private const val PROBE = "(function(){var v=document.querySelector('video');var e=document.querySelector('.ytp-error-content-wrap-reason,.ytp-error');" +
        "return 'w='+innerWidth+' h='+innerHeight+' videos='+document.querySelectorAll('video').length+(v?(' ready='+v.readyState+' net='+v.networkState+" +
        "' paused='+v.paused+' err='+(v.error?v.error.code:0)):'')+' ui='+document.querySelectorAll('.ytp-chrome-bottom,.ytp-large-play-button').length+" +
        "(e?(' error='+(e.innerText||'').replace(/\\s+/g,' ').slice(0,120)):'')})()"
    private const val PAUSE = "document.querySelectorAll('video').forEach(function(v){v.pause()})"
    private const val RESUME = "document.querySelectorAll('video').forEach(function(v){v.play()})"

    /** YouTube sounds now (iOS MTYouTube.sounds). */
    var sounds = false; private set
    /** The player stands over the whole screen: ours enlarged or YouTube's own full screen. */
    val whole get() = big || custom != null

    private var id: String? = null
    private var act: Activity? = null
    private var web: WebView? = null
    private var stage: FrameLayout? = null
    private var strip: LinearLayout? = null
    private var mark: android.widget.ImageView? = null
    private var big = false
    private var custom: View? = null
    private var customDone: WebChromeClient.CustomViewCallback? = null
    private var focus: AudioFocusRequest? = null
    private var fit: View.OnLayoutChangeListener? = null

    /** The video a link names, played here; any other link is not this player's (false). iOS MTYouTube.play(url, title). */
    fun play(a: Activity?, url: String, title: String? = null): Boolean {
        val tube = LinkPreview.youtube(url) ?: return false
        if (a == null) return false
        play(a, tube.id)
        return true
    }

    private fun play(a: Activity, id: String) {
        if (CallSound.refused("YouTube")) return   // the call holds the sound (iOS MontanaAudioSession.refusedUnderCall)
        val w = web
        if (this.id == id && w != null && act === a) {
            // the same video asked again plays on where it stands
            Log.d("Montana", "youtube again whole=" + (if (whole) 1 else 0))
            show()
            w.evaluateJavascript(RESUME, null)
            return
        }
        end("another")
        val decor = a.window?.decorView as? FrameLayout ?: run { Log.d("Montana", "youtube refused -- no window"); return }
        this.id = id; act = a
        val v = webView(a)
        web = v
        val s = stage(a, v)
        stage = s
        decor.addView(s, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
        lay(decor, corner = null)
        // the window turns or the bars change: the player keeps its corner inside the screen
        fit = View.OnLayoutChangeListener { d, l, t, r, b, ol, ot, orr, ob -> if (r - l != orr - ol || b - t != ob - ot) d.post { lay(d as FrameLayout, corner = nearest(d)) } }
        decor.addOnLayoutChangeListener(fit)
        s.alpha = 0f
        s.animate().alpha(1f).setDuration(200).start()
        v.loadUrl(embed(id), mapOf("Referer" to referer(a)))
        Log.d("Montana", "youtube open")
    }

    /** A sound of ours starts, or the app leaves the screen: the video rests where it stands (iOS rest). */
    fun rest() {
        val w = web ?: return
        w.evaluateJavascript(PAUSE, null)
        quiet()
    }

    /** The system's back while the player stands over the whole screen: the mini player comes back; false -- nothing to do. */
    fun back(): Boolean {
        if (custom != null) { leaveCustom(); return true }
        if (big) { enlarge(false); return true }
        return false
    }

    /** The player ends: its sound let go, its page emptied, our mini player away, nothing of it kept (iOS end). */
    fun end(why: String) {
        val w = web ?: return
        Log.d("Montana", "youtube end " + why)
        quiet()
        if (custom != null) leaveCustom()
        val s = stage
        (act?.window?.decorView as? View)?.removeOnLayoutChangeListener(fit)
        fit = null
        web = null; stage = null; strip = null; mark = null; id = null; act = null; big = false
        runCatching {
            w.stopLoading()
            w.removeJavascriptInterface("tube")
            w.loadUrl("about:blank")
        }
        s?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
            (s.parent as? ViewGroup)?.removeView(s)
            runCatching { w.clearCache(true); w.clearHistory(); w.destroy() }
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }?.start()
    }

    // ── the sound ──

    private fun playing() {
        sounds = true
        if (VoicePlayer.playing != null && !VoicePlayer.paused) VoicePlayer.pause()   // one sound: a voice rests while YouTube plays
        if (MusicPlayer.playing) MusicPlayer.pause()   // and our own track
        NoteOpen.close?.invoke()   // and the round note's dock closes (iOS MontanaVideoDock.close)
        val a = act ?: return
        if (focus != null) return
        val am = a.getSystemService(AudioManager::class.java) ?: return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { kind ->
                // another sound took the focus -- ours or another app's: YouTube rests where it stands
                if (kind == AudioManager.AUDIOFOCUS_LOSS || kind == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) MainThread.post { rest() }
            }.build().also { am.requestAudioFocus(it) }
    }

    /** The player is silent: its hold on the sound goes, another app's music may go on (iOS quiet). */
    private fun quiet() {
        val f = focus
        focus = null
        if (f != null) act?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(f)
        sounds = false
    }

    private fun heard(said: String) {
        Log.d("Montana", "youtube player " + said)
        when (said) {
            "playing" -> playing()
            "pause", "ended", "error" -> quiet()
        }
    }

    // ── the window ──

    private fun show() {
        val s = stage ?: return
        s.bringToFront()   // in front of every page pushed since, never detached: the player plays on
        s.isEnabled = true
        s.visibility = View.VISIBLE
        s.animate().alpha(1f).setDuration(200).start()
    }

    private fun enlarge(on: Boolean) {
        val s = stage ?: return
        val decor = s.parent as? FrameLayout ?: return
        Log.d("Montana", "youtube full screen " + (if (on) "by the person" else "left"))
        big = on
        mark?.setImageResource(if (on) R.drawable.ic_small_screen else R.drawable.ic_full_screen)
        mark?.contentDescription = s.context.getString(if (on) R.string.yt_small_screen else R.string.yt_full_screen)
        show()
        lay(decor, corner = if (on) null else nearest(decor))
    }

    /** Lays the stage: the whole screen inside the system's bars, or the mini player in a corner (null: the bottom right). */
    private fun lay(decor: FrameLayout, corner: Pair<Boolean, Boolean>?) {
        val s = stage ?: return
        val w = web ?: return
        val bar = strip ?: return
        val c = decor.context
        val safe = safe(decor)
        val tall = c.dp(TALL); val barH = c.dp(BAR); val edge = c.dp(EDGE)
        if (big) {
            s.layoutParams = (s.layoutParams as FrameLayout.LayoutParams).apply { width = decor.width; height = decor.height }
            s.translationX = 0f; s.translationY = 0f
            s.setBackgroundColor(Color.BLACK); s.clipToOutline = false
            w.layoutParams = (w.layoutParams as FrameLayout.LayoutParams).apply { width = safe.width(); height = safe.height() - barH; leftMargin = safe.left; topMargin = safe.top }
            bar.layoutParams = (bar.layoutParams as FrameLayout.LayoutParams).apply { width = safe.width(); height = barH; leftMargin = safe.left; topMargin = safe.bottom - barH }
            s.requestLayout()
            return
        }
        // 200 tall, as wide as the video's proportion within the screen (iOS stage)
        val wide = minOf(decor.width - 2 * edge, Math.round(TALL * 16f / 9f).let { c.dp(it) })
        s.layoutParams = (s.layoutParams as FrameLayout.LayoutParams).apply { width = wide; height = tall + barH }
        s.background = c.rounded(Color.BLACK, CORNER); s.clipToOutline = true
        w.layoutParams = (w.layoutParams as FrameLayout.LayoutParams).apply { width = wide; height = tall; leftMargin = 0; topMargin = 0 }
        bar.layoutParams = (bar.layoutParams as FrameLayout.LayoutParams).apply { width = wide; height = barH; leftMargin = 0; topMargin = tall }
        val (left, top) = corner ?: (false to false)
        s.translationX = (if (left) safe.left + edge else safe.right - edge - wide).toFloat()
        s.translationY = (if (top) safe.top + edge else safe.bottom - edge - tall - barH).toFloat()
        s.requestLayout()
    }

    /** The corner the window's middle is nearest to: (left, top). */
    private fun nearest(decor: View): Pair<Boolean, Boolean> {
        val s = stage ?: return false to false
        val cx = s.translationX + s.width / 2f
        val cy = s.translationY + s.height / 2f
        return (cx < decor.width / 2f) to (cy < decor.height / 2f)
    }

    private fun safe(decor: View): Rect {
        val r = Rect(0, 0, decor.width, decor.height)
        val ins = decor.rootWindowInsets ?: return r
        if (Build.VERSION.SDK_INT >= 30) {
            val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            r.set(i.left, i.top, decor.width - i.right, decor.height - i.bottom)
        } else {
            @Suppress("DEPRECATION")
            r.set(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, decor.width - ins.systemWindowInsetRight, decor.height - ins.systemWindowInsetBottom)
        }
        return r
    }

    /** Our mini player: YouTube's own player and nothing of ours over it, our bar under it -- the whole-screen mark and the close mark. */
    @SuppressLint("ClickableViewAccessibility")
    private fun stage(a: Activity, w: WebView): FrameLayout {
        val c = a
        val s = FrameLayout(c).apply {
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = c.dp(8).toFloat()
        }
        s.addView(w, FrameLayout.LayoutParams(1, 1))
        val full = c.icon(R.drawable.ic_full_screen, Color.WHITE, 20).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER
            contentDescription = c.getString(R.string.yt_full_screen)
            pressable { enlarge(!big) }
        }
        val close = c.icon(R.drawable.ic_close, Color.WHITE, 22).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER
            contentDescription = c.getString(R.string.close)
            pressable { end("closed by the person") }
        }
        mark = full
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(GLASS_TONE)
            setPadding(c.dp(4), 0, c.dp(4), 0)
            addView(full, LinearLayout.LayoutParams(c.dp(BAR), c.dp(BAR)))
            addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
            addView(close, LinearLayout.LayoutParams(c.dp(BAR), c.dp(BAR)))
        }
        // THE BAR CARRIES THE WINDOW (iOS dragged): it moves under the finger, the release sets it into the nearest corner
        var lastX = 0f; var lastY = 0f
        bar.setOnTouchListener { _, e ->
            if (big) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.rawX; lastY = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    s.translationX += e.rawX - lastX; s.translationY += e.rawY - lastY
                    lastX = e.rawX; lastY = e.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> settle()
            }
            true
        }
        strip = bar
        s.addView(bar, FrameLayout.LayoutParams(1, 1))
        return s
    }

    private fun settle() {
        val s = stage ?: return
        val decor = s.parent as? FrameLayout ?: return
        val (left, top) = nearest(decor)
        val safe = safe(decor)
        val edge = decor.context.dp(EDGE)
        val x = (if (left) safe.left + edge else safe.right - edge - s.width).toFloat()
        val y = (if (top) safe.top + edge else safe.bottom - edge - s.height).toFloat()
        s.animate().translationX(x).translationY(y).setDuration(250).setInterpolator(android.view.animation.OvershootInterpolator(0.6f)).start()
    }

    // ── YouTube's own full screen: the web view's custom view ──

    private fun showCustom(v: View, done: WebChromeClient.CustomViewCallback) {
        val decor = act?.window?.decorView as? FrameLayout ?: run { done.onCustomViewHidden(); return }
        Log.d("Montana", "youtube full screen by the player")
        custom = v; customDone = done
        v.setBackgroundColor(Color.BLACK)
        decor.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stage?.visibility = View.INVISIBLE
    }

    private fun hideCustom() {
        val v = custom ?: return
        custom = null; customDone = null
        (v.parent as? ViewGroup)?.removeView(v)
        Log.d("Montana", "youtube full screen left by the player")
        show()
    }

    private fun leaveCustom() {
        val done = customDone
        hideCustom()
        done?.onCustomViewHidden()
    }

    // ── the page ──

    /** The Referer the player is named by: this app's own identifier as a web address (Required Minimum Functionality). */
    private fun referer(a: Activity) = "https://" + a.packageName.lowercase()

    /** The embedded player's own page on the privacy-enhanced host: inline, starting at once -- the person's tap asked for it. */
    private fun embed(id: String) = "https://www.youtube-nocookie.com/embed/$id?playsinline=1&autoplay=1&rel=0"

    @SuppressLint("SetJavaScriptEnabled")
    private fun webView(a: Activity): WebView {
        val v = WebView(a)
        v.setBackgroundColor(Color.BLACK)
        v.isVerticalScrollBarEnabled = false; v.isHorizontalScrollBarEnabled = false
        v.overScrollMode = View.OVER_SCROLL_NEVER
        v.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false   // the person's tap was the gesture: the video starts at once
            cacheMode = WebSettings.LOAD_NO_CACHE   // no byte of a video is kept
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, false)
        v.addJavascriptInterface(object {
            @JavascriptInterface fun postMessage(said: String) { MainThread.post { if (web === v) heard(said) } }
        }, "tube")
        v.webViewClient = object : WebViewClient() {
            // a link inside the player (the title, the logo, «Watch on YouTube») goes to YouTube itself (iOS decidePolicyFor)
            override fun shouldOverrideUrlLoading(view: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                if (r.isForMainFrame && r.hasGesture() && (u.scheme == "https" || u.scheme == "http")) {
                    Log.d("Montana", "youtube leave to the service by=player")
                    runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    return true
                }
                return false
            }
            override fun onPageCommitVisible(view: WebView, url: String?) { view.evaluateJavascript(LISTEN, null) }
            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(LISTEN, null)
                Log.d("Montana", "youtube page loaded")
                for (ms in longArrayOf(4000, 12000)) view.postDelayed({ if (web === view) view.evaluateJavascript(PROBE) { said -> Log.d("Montana", "youtube page " + said.trim('"')) } }, ms)
            }
            // the page's own failures, by their codes alone (no address is told)
            override fun onReceivedError(view: WebView, r: WebResourceRequest, e: android.webkit.WebResourceError) {
                if (r.isForMainFrame) Log.d("Montana", "youtube page error " + e.errorCode)
            }
            override fun onReceivedHttpError(view: WebView, r: WebResourceRequest, e: android.webkit.WebResourceResponse) {
                if (r.isForMainFrame) Log.d("Montana", "youtube page http " + e.statusCode)
            }
        }
        v.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) { if (web === v) showCustom(view, callback) else callback.onCustomViewHidden() }
            override fun onHideCustomView() { hideCustom() }
            // a script's failure in the player's page, its words without any address
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR)
                    Log.d("Montana", "youtube console " + m.message().replace(Regex("https?://\\S+"), "<link>").take(160))
                return true
            }
        }
        return v
    }

}
