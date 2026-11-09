package quest.montana.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * THE FILE'S FACE (iOS MTFileIcon, MontanaFeeds.swift 2730-2808, the author's word 19.09: «a big, coloured, native icon for a PDF»):
 * the tile a document's bubble draws. The platform draws its picture -- a PDF's first page (PdfRenderer), a picture's own pixels, a
 * film's first frame -- and the type's word stands on the tile's corner in the type's colour (red for PDF and slides, green for
 * sheets, amber for archives, blue else). Until a picture lands, or when none can be drawn, the coloured plate carries the word
 * alone. Every picture is asked once and kept.
 */
internal class FileFace(c: Context, private val file: File?, name: String, private val wDp: Int, private val hDp: Int) : FrameLayout(c) {
    private val ext = (name.substringAfterLast('.', "").ifEmpty { file?.extension ?: "" }).lowercase()
    private val word = ext.uppercase()
    private val radius = minOf(wDp, hDp) * 0.18f * resources.displayMetrics.density
    private val clip = Path()
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = c.dp(0.5f).coerceAtLeast(1).toFloat(); color = Color.argb(46, 255, 255, 255) }
    private var picture: Bitmap? = null
    private val plate = c.text(word, wDp * 0.26f, Color.WHITE, bold = true, center = true).apply {   // USER-DATA: the file's own type word
        typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
        maxLines = 1
        setPadding(c.dp(4), 0, c.dp(4), 0)
        setAutoSizeTextTypeUniformWithConfiguration(maxOf(1, (wDp * 0.13f).toInt()), maxOf(2, (wDp * 0.26f).toInt()), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    private val badge = c.text(word, 10f, Color.WHITE, bold = true).apply {   // USER-DATA: the file's own type word
        typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
        maxLines = 1
        setPadding(c.dp(5), c.dp(2), c.dp(5), c.dp(2))
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colours(ext)).apply { cornerRadius = c.dp(100).toFloat() }
        visibility = GONE
    }

    init {
        setWillNotDraw(false)
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colours(ext)).apply { cornerRadius = radius }
        addView(plate, LayoutParams(MATCH, MATCH, Gravity.CENTER).apply { gravity = Gravity.CENTER })
        plate.gravity = Gravity.CENTER
        if (word.isNotEmpty()) addView(badge, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(c.dp(4), 0, 0, c.dp(4)) })
        if (word.isEmpty()) plate.visibility = GONE
        file?.let { f -> cache.get(f.path)?.let { wear(it) } ?: ask(f) }
    }

    private fun ask(f: File) {
        val w = context.dp(wDp); val h = context.dp(hDp)
        work.execute {
            val pic = runCatching { draw(f, ext, w, h) }.getOrNull() ?: return@execute
            cache.put(f.path, pic)
            main.post { wear(pic) }
        }
    }

    private fun wear(pic: Bitmap) {
        picture = pic
        background = null
        plate.visibility = GONE
        if (word.isNotEmpty()) badge.visibility = VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pic = picture ?: return super.onDraw(canvas)
        clip.reset(); clip.addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, Path.Direction.CW)
        canvas.save(); canvas.clipPath(clip)
        // scaled to fill (iOS .scaledToFill): the picture covers the tile, its middle kept
        val s = maxOf(width / pic.width.toFloat(), height / pic.height.toFloat())
        val dw = pic.width * s; val dh = pic.height * s
        canvas.drawBitmap(pic, null, RectF((width - dw) / 2, (height - dh) / 2, (width + dw) / 2, (height + dh) / 2), null)
        canvas.restore()
        val half = rim.strokeWidth / 2
        canvas.drawRoundRect(RectF(half, half, width - half, height - half), radius, radius, rim)
    }

    companion object {
        private val cache = LruCache<String, Bitmap>(24)
        private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "file-face").apply { isDaemon = true } }
        private val main = Handler(Looper.getMainLooper())

        /** The type's two colours, top to bottom (iOS MTFileIcon.colours). */
        fun colours(ext: String): IntArray = when (ext) {
            "pdf", "ppt", "pptx", "key" -> intArrayOf(Color.rgb(255, 135, 94), Color.rgb(255, 79, 105))
            "xls", "xlsx", "csv", "numbers" -> intArrayOf(Color.rgb(153, 222, 112), Color.rgb(94, 184, 79))
            "zip", "rar", "gz", "gzip", "7z", "ai" -> intArrayOf(Color.rgb(255, 163, 74), Color.rgb(237, 112, 92))
            else -> intArrayOf(Color.rgb(115, 214, 252), Color.rgb(41, 158, 242))
        }

        /** The platform's own picture of the file, near the tile's size; null when it draws none. */
        private fun draw(f: File, ext: String, w: Int, h: Int): Bitmap? = when (ext) {
            "pdf" -> android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                android.graphics.pdf.PdfRenderer(fd).use { r ->
                    if (r.pageCount == 0) return null
                    r.openPage(0).use { p ->
                        val s = maxOf(w / p.width.toFloat(), h / p.height.toFloat()) * 2f
                        val bmp = Bitmap.createBitmap(maxOf(1, (p.width * s).toInt()), maxOf(1, (p.height * s).toInt()), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)   // a page is paper: what it leaves transparent is white
                        p.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
            "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp" -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                var sample = 1
                while (o.outWidth / (sample * 2) >= w * 2 && o.outHeight / (sample * 2) >= h * 2) sample *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            "mp4", "mov", "m4v", "webm", "3gp", "mkv" -> android.media.MediaMetadataRetriever().let { r ->
                try { r.setDataSource(f.path); r.getFrameAtTime(0) } finally { r.release() }
            }
            else -> null
        }
    }
}

/** The transfer's ring over the face (iOS docBubble's overlay: black 0.45 behind, a ring of 30 at 3, pale 0.35, the done part white). */
private class FaceRing(c: Context) : View(c) {
    var share = 0f
        set(v) { if (field != v) { field = v; invalidate() } }
    private val line = c.dp(3).toFloat()
    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(115, 0, 0, 0) }
    private val pale = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = line; color = Color.argb(89, 255, 255, 255) }
    private val done = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = line; color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val box = RectF()
    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        cv.drawCircle(cx, cy, minOf(width, height) / 2f, back)
        val r = context.dp(15).toFloat()
        box.set(cx - r, cy - r, cx + r, cy + r)
        cv.drawOval(box, pale)
        cv.drawArc(box, -90f, 360f * share.coerceIn(0f, 1f), false, done)
    }
}

internal const val DOC_STAMP_TAG = "doc_stamp"

/**
 * A FILE IN ITS BUBBLE (iOS docBubble, MontanaBubble.swift 1347-1381): the file's face 60 by 74, and beside it at 12 the name on one
 * line, under it the size -- or while the file rides, its percent and word -- and the letter's stamp in the same row (docLay moves it
 * there). The ring of the transfer stands over the face. A file that waits for a tap wears the arrow down on its face.
 */
internal fun docBody(c: Context, m: Msg, man: JSONObject?, f: File?, kind: String, into: LinearLayout) {
    val tint = BubbleStyle.text(m.mine)
    val name = man?.optString("n")?.ifEmpty { null } ?: f?.name ?: ("file." + (man?.optString("e") ?: ""))
    val size = man?.optLong("sz")?.takeIf { 0 < it } ?: f?.length() ?: 0L
    val waits = f == null && Media.waiting(c, m)
    into.addView(c.hstack {
        gravity = Gravity.CENTER_VERTICAL
        val ring = FaceRing(c).apply { visibility = View.GONE }
        addView(FrameLayout(c).apply {
            addView(FileFace(c, f, name, 60, 74), FrameLayout.LayoutParams(MATCH, MATCH))
            addView(ring, FrameLayout.LayoutParams(c.dp(36), c.dp(36), Gravity.CENTER))
            if (waits) addView(c.icon(R.drawable.ic_arrow_circle_down, Color.WHITE).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(115, 0, 0, 0)) }
                setPadding(c.dp(6), c.dp(6), c.dp(6), c.dp(6))
            }, FrameLayout.LayoutParams(c.dp(36), c.dp(36), Gravity.CENTER))
        }, lp(c.dp(60), c.dp(74)).apply { marginEnd = c.dp(12) })
        addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(c.text(name, 16f, tint).apply { maxWidth = c.dp(158); singleLineEllipsis() }, lp(WRAP, WRAP))   // USER-DATA: the file's name
            val sizeWord = android.text.format.Formatter.formatShortFileSize(c, size)
            val line = c.text(sizeWord, 11f, MT.withAlpha(tint, 0.7f))
            addView(c.hstack {
                tag = DOC_STAMP_TAG
                gravity = Gravity.CENTER_VERTICAL
                addView(DocRide(c, m.mid, ring, line, sizeWord, tint), lp(WRAP, WRAP))
            }, lp(WRAP, WRAP).apply { topMargin = c.dp(2) })
        }, lp(WRAP, WRAP))
        if (f != null) setOnClickListener { if (!Transfers.riding(m.mid)) (c as? MainActivity)?.let { a -> openMedia(a, f, kind) } }
        else if (waits) setOnClickListener { Media.tapped(c, m) }
    }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = c.dp(4) })
}

/** The size, or while the file rides its percent and word (iOS «N% · status», caption2 semibold in the bubble's ink); the ring follows. */
private class DocRide(c: Context, private val mid: String, private val ring: FaceRing, private val line: TextView,
                      private val sizeWord: String, private val tint: Int) : FrameLayout(c) {
    private val ear: () -> Unit = { show() }
    init { addView(line, LayoutParams(WRAP, WRAP)); show() }
    private fun show() {
        val p = Transfers.progress(mid)
        ring.visibility = if (p == null) GONE else VISIBLE
        if (p == null) {
            line.text = sizeWord   // USER-DATA: a measured size
            line.setTextColor(MT.withAlpha(tint, 0.7f)); line.typeface = Typeface.DEFAULT
            return
        }
        ring.share = p
        line.text = "${(p * 100).toInt()}%" + (Transfers.word(mid)?.let { " · $it" } ?: "")   // USER-DATA: a measured number
        line.setTextColor(tint); line.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); Transfers.listen(mid, ear); show() }
    override fun onDetachedFromWindow() { Transfers.forget(mid, ear); super.onDetachedFromWindow() }
}

/** The stamp of a file letter stands in its size's row (iOS docBubble's metaLine inside the HStack), not under the bubble. */
internal fun docLay(col: LinearLayout) {
    val row = findTag(col, DOC_STAMP_TAG) as? LinearLayout ?: return
    val time = col.getChildAt(col.childCount - 1) ?: return
    if (time === row || findTag(time, DOC_STAMP_TAG) != null) return
    col.removeView(time)
    row.addView(time, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = col.context.dp(4) })
}

private fun findTag(v: View, tag: String): View? {
    if (v.tag == tag) return v
    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findTag(v.getChildAt(i), tag)?.let { return it }
    return null
}
