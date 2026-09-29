package quest.montana.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import java.util.concurrent.Executors

class MainActivity : Activity() {
    /** The window's whole surface: the backdrop runs under the system bars (iOS .ignoresSafeArea()). */
    private lateinit var surface: FrameLayout
    private lateinit var backdrop: android.widget.ImageView
    /** The pages: inside the system bars and above the keyboard. */
    private lateinit var root: FrameLayout
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** What the system back gesture does on the current page; null — leave the app. */
    var back: (() -> Unit)? = null

    // The screen's state (iOS RootView @State)
    private var hasSeed = false
    private var termsAccepted = false
    private var showWelcome = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceVault.init(this)
        Prefs.init(this)
        Book.ctx = applicationContext
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        surface = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        backdrop = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
        root = FrameLayout(this)
        surface.addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))
        surface.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        // The page stands inside the system bars and above the keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(surface)
        hasSeed = MontanaSeed.hasSeed
        termsAccepted = Prefs.termsAccepted
        render()
        intent?.data?.let { openLink(it.toString()) }
    }

    /** AN INVITATION HANDED IN BY THE SYSTEM (iOS handleLink): opened now, or kept until the identity exists. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { openLink(it.toString()) }
    }
    fun openLink(link: String) {
        if (!hasSeed || !termsAccepted) {
            Prefs.setStr("pendingInvite", link)
            android.widget.Toast.makeText(this, R.string.invite_wait, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        openInvitation(this, link)
    }

    /**
     * The one question the first screen asks (iOS RootView): does this device hold a person.
     * The answer is the seed itself — no second marker that could disagree with it.
     *   no seed → the first launch · seed, no terms → the terms · just born → the sign · else → main
     */
    private fun render() {
        back = null
        setBackdrop(null)
        val page: View = when {
            !hasSeed -> Onboarding(this) {
                hasSeed = MontanaSeed.hasSeed
                showWelcome = hasSeed
                render()
            }.view
            !termsAccepted -> termsGate(this, onAgree = { Prefs.termsAccepted = true; termsAccepted = true; render() })
            showWelcome -> welcome(this) { showWelcome = false; render() }
            else -> mainScreen(this) {
                // «Forget this device» (iOS: Privacy): the seed and everything of the person leave.
                MontanaSeed.clear(); Prefs.forgetPerson(); SelfFace.clear(this); SavedMessages.forget(this)
                MontanaHomeNode.forget(this); MontanaBackup.forget(this); MontanaCard.forget(); Book.wipe()
                MontanaBackupID.withdrawOwn(this)   // this installation's record leaves the account; a twin's stands
                hasSeed = false; termsAccepted = false
                render()
            }
        }
        show(page)
        if (hasSeed) MontanaBackupID.publish(this)   // the account carries this device's words (iOS publish)
        if (hasSeed && termsAccepted && !showWelcome) askWhatIsUnasked()
        // THE DAILY ROAD to the person's own node, a little after the first screen stands (iOS tickSoon).
        if (hasSeed && termsAccepted) HomeNodeWatch.tickSoon(this)
        // The identity is born: the invitation that waited for it opens by itself (iOS replayPendingInvite).
        if (hasSeed && termsAccepted && !showWelcome) Prefs.str("pendingInvite", "").takeIf { it.isNotEmpty() }?.let { Prefs.remove("pendingInvite"); openInvitation(this, it) }
    }

    /**
     * iOS askWhatIsUnasked: nobody is asked anything before they hold an identity; then exactly ONE
     * question, and it is the system's own — notifications. Asked once; the answer is the system's.
     */
    private fun askWhatIsUnasked() {
        if (Prefs.notifyAsked) return
        Prefs.notifyAsked = true
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), ASK_NOTIFY)
        }
    }

    /** A picture under the whole window, system bars included; null — the plain black. */
    fun setBackdrop(res: Int?) {
        if (res == null) backdrop.setImageDrawable(null) else backdrop.setImageResource(res)
        ground?.let { surface.removeView(it) }; ground = null
    }

    /** A drawn ground under the whole window, system bars included (iOS MontanaCrestGround's .ignoresSafeArea()). */
    private var ground: View? = null
    fun setGround(v: View) {
        ground?.let { surface.removeView(it) }
        ground = v
        surface.addView(v, 1, FrameLayout.LayoutParams(MATCH, MATCH))   // over the backdrop, under the pages
    }

    /** Replaces the page with a short cross-fade. */
    private fun show(page: View) {
        page.alpha = 0f
        root.removeAllViews()
        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        page.animate().alpha(1f).setDuration(220).start()
    }

    /** A page over the current one, full screen; back or the returned close takes it away. */
    fun overlay(page: View): () -> Unit {
        val keepBack = back
        var open = true
        val close = { if (open) { open = false; surface.removeView(page); back = keepBack } }
        page.alpha = 0f
        // the page's marks stand inside the system bars, as every page's do
        page.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        surface.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        page.animate().alpha(1f).setDuration(180).start()
        back = close
        return close
    }

    /** The terms over the current page (iOS: a sheet from the doors' footer); back or its mark closes it. */
    fun showTermsSheet() {
        val keepBack = back
        lateinit var sheet: View
        val close = { root.removeView(sheet); back = keepBack }
        sheet = termsGate(this, onAgree = null, onClose = close)
        sheet.translationY = root.height.toFloat()
        root.addView(sheet, FrameLayout.LayoutParams(MATCH, MATCH))
        sheet.animate().translationY(0f).setDuration(280).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        back = close
    }

    fun showProfile() {
        show(Profile(this, onboarding = false) { render() }.view)
    }

    // ── THE BOX IS READ WHILE THE APP STANDS (iOS fetchBox on activation and by its kick): at once, then every eight seconds ──
    private val boxRound = object : Runnable {
        override fun run() {
            if (hasSeed && termsAccepted) Thread { Post.fetch() }.start()
            main.postDelayed(this, 8000)
        }
    }
    override fun onResume() { super.onResume(); main.removeCallbacks(boxRound); main.post(boxRound) }
    override fun onPause() { super.onPause(); main.removeCallbacks(boxRound) }

    @Deprecated("the platform's back for targetSdk 35 without predictive back")
    override fun onBackPressed() {
        val b = back
        if (b != null) b() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ── work off the main thread (the core's key derivation takes a moment) ──
    fun background(work: () -> Unit) { worker.execute(work) }
    fun onMain(work: () -> Unit) { main.post(work) }
    fun onMainAfter(ms: Long, work: () -> Unit) { main.postDelayed(work, ms) }

    // ── the system photo picker ──
    private var photoDone: ((Uri?) -> Unit)? = null
    fun pickPhoto(done: (Uri?) -> Unit) {
        photoDone = done
        val intent = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
                     else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        @Suppress("DEPRECATION") startActivityForResult(intent, PICK_PHOTO)
    }

    /** A picture OR a film from the system's own picker (iOS PhotosPicker .any(of: [.images, .videos])). */
    fun pickVisual(done: (Uri?) -> Unit) {
        photoDone = done
        val intent = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
                     else Intent(Intent.ACTION_GET_CONTENT).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        @Suppress("DEPRECATION") startActivityForResult(intent, PICK_PHOTO)
    }

    // ── the system's document picker: a backup handed to a place the person picks, and taken back from a file ──
    private var docDone: ((Uri?) -> Unit)? = null
    fun saveDocument(name: String, done: (Uri?) -> Unit) {
        docDone = done
        @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, name), DOCUMENT)
    }
    /** A folder lent to the music (iOS fileImporter .folder): the system's tree picker; the grant is taken by MusicFolders. */
    fun pickFolder(done: (Uri?) -> Unit) {
        docDone = done
        @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), DOCUMENT)
    }
    fun openDocument(done: (Uri?) -> Unit) {
        docDone = done
        @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*"), DOCUMENT)
    }

    @Deprecated("the platform's own result road")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_PHOTO) {
            photoDone?.invoke(if (resultCode == RESULT_OK) data?.data else null)
            photoDone = null
        } else if (requestCode == DOCUMENT) {
            docDone?.invoke(if (resultCode == RESULT_OK) data?.data else null)
            docDone = null
        }
    }

    /** The app's own language before Android 13 (from 13 the system keeps it: AppLanguage). */
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private companion object { const val PICK_PHOTO = 1; const val ASK_NOTIFY = 2; const val DOCUMENT = 3 }
}
