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
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // The page stands inside the system bars and above the keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)
        hasSeed = MontanaSeed.hasSeed
        termsAccepted = Prefs.termsAccepted
        render()
    }

    /**
     * The one question the first screen asks (iOS RootView): does this device hold a person.
     * The answer is the seed itself — no second marker that could disagree with it.
     *   no seed → the first launch · seed, no terms → the terms · just born → the sign · else → main
     */
    private fun render() {
        back = null
        val page: View = when {
            !hasSeed -> Onboarding(this) {
                hasSeed = MontanaSeed.hasSeed
                showWelcome = hasSeed
                render()
            }.view
            !termsAccepted -> termsGate(this) { Prefs.termsAccepted = true; termsAccepted = true; render() }
            showWelcome -> welcome(this) { showWelcome = false; render() }
            else -> mainScreen(this) {
                // «Forget this device» (iOS: Privacy): the seed and everything of the person leave.
                MontanaSeed.clear(); Prefs.forgetPerson(); SelfFace.clear(this)
                hasSeed = false; termsAccepted = false
                render()
            }
        }
        show(page)
    }

    /** Replaces the page with a short cross-fade. */
    private fun show(page: View) {
        page.alpha = 0f
        root.removeAllViews()
        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        page.animate().alpha(1f).setDuration(220).start()
    }

    fun showProfile() {
        show(Profile(this, onboarding = false) { render() }.view)
    }

    @Deprecated("the platform's back for targetSdk 35 without predictive back")
    override fun onBackPressed() {
        val b = back
        if (b != null) b() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ── work off the main thread (the core's key derivation takes a moment) ──
    fun background(work: () -> Unit) { worker.execute(work) }
    fun onMain(work: () -> Unit) { main.post(work) }

    // ── the system photo picker ──
    private var photoDone: ((Uri?) -> Unit)? = null
    fun pickPhoto(done: (Uri?) -> Unit) {
        photoDone = done
        val intent = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
                     else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        @Suppress("DEPRECATION") startActivityForResult(intent, PICK_PHOTO)
    }

    @Deprecated("the platform's own result road")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_PHOTO) {
            photoDone?.invoke(if (resultCode == RESULT_OK) data?.data else null)
            photoDone = null
        }
    }

    private companion object { const val PICK_PHOTO = 1 }
}
