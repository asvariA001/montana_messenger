package quest.montana.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * WHAT A POST SHOWS OF ITS FILES (iOS MTPostMeasure, MTBoardFrame, MTBoard.poster at 2155): a file's kind by its name, a picture's
 * frame -- its own shape whole, or its middle past the shapes a post takes -- the poster a visitor sees before a look brings the
 * file, and a track's or a film's length.
 */
object WallFiles {
    private const val NARROWEST = 9.0 / 16.0   // iOS MTPostMeasure.narrowest: from upright 9:16 to wide 16:9
    private const val WIDEST = 16.0 / 9.0
    private const val POSTER_SIDE = 640.0      // iOS MTBoard.posterSide, posterBudget (MontanaBoard 2229-2230)
    private const val POSTER_BUDGET = 52_000
    private val PICTURE = setOf("jpg", "jpeg", "png", "heic", "heif", "gif", "webp")
    private val FILM = setOf("mov", "mp4", "m4v")
    private val TRACK = setOf("mp3", "m4a", "aac", "wav", "flac", "aif", "aiff", "caf")   // iOS mtIsAudioName (MontanaMediaKit 34-37)

    /** iOS MTPostMeasure.kind(ofExtension:) (MontanaMediaKit 44-50). */
    fun kindOf(ext: String): String = ext.lowercase().let { e ->
        when (e) { in PICTURE -> "img"; in FILM -> "vid"; in TRACK -> "aud"; else -> "doc" }
    }

    /** The shapes a picture takes in a post (iOS MTPictureFrame.shapes), and a shape held to them (iOS held). */
    val SHAPES = listOf(1.0, 4.0 / 5.0, 3.0 / 4.0, 9.0 / 16.0, 4.0 / 3.0, 16.0 / 9.0)
    fun held(a: Double): Double = if (a.isFinite() && 0 < a) a.coerceIn(NARROWEST, WIDEST) else 1.0

    /** iOS MTPictureFrame.around: the largest part of a picture of this size in the shape, around a centre (0...1), inside the picture. */
    fun around(cx: Double, cy: Double, shape: Double, width: Int, height: Int): JSONObject {
        val a = held(shape)
        val own = if (0 < height) width.toDouble() / height else 1.0
        var w = 1.0
        var h = 1.0
        if (a < own) w = a / own else h = own / a
        return JSONObject().put("a", a).put("x", (cx - w / 2).coerceIn(0.0, 1 - w)).put("y", (cy - h / 2).coerceIn(0.0, 1 - h)).put("w", w).put("h", h)
    }
    /** iOS MTPictureFrame.own: a picture's own shape whole, or past the shapes a post takes, its middle. */
    fun ownFrame(width: Int, height: Int): JSONObject = around(0.5, 0.5, if (0 < height) width.toDouble() / height else 1.0, width, height)

    /**
     * A TILE'S PICTURE FROM A FILE ON THIS PHONE (iOS MTBoardImage.drawn): decoded so the part its frame shows has the tile's own pixels,
     * never more than 2400 on the picture's longest side, then cut to it; a post from before the frame, the whole picture. The decoder
     * halves by powers of two, so what is still far above the tile's pixels is brought down to them.
     */
    fun drawn(c: Context, f: File, kind: String, fr: JSONObject?, pw: Int, ph: Int): Bitmap? {
        val need = if (fr != null) maxOf(pw / maxOf(0.01, fr.optDouble("w", 1.0)), ph / maxOf(0.01, fr.optDouble("h", 1.0))) else maxOf(pw, ph) * 1.5
        val whole = Media.preview(c, f, kind, need.coerceIn(64.0, 2400.0).toInt()) ?: return null
        val part = fr?.let { cut(whole, it) } ?: whole
        val k = maxOf(pw.toDouble() / part.width, ph.toDouble() / part.height)
        return if (k < 0.66) Bitmap.createScaledBitmap(part, maxOf(1, (part.width * k).toInt()), maxOf(1, (part.height * k).toInt()), true) else part
    }

    /** What a picked file brings to its post (iOS MTBoardDrafts.file, MTBoardComposer.load): a picture's own frame, a track's or a film's length. */
    fun dress(c: Context, a: MyWall.Attachment) {
        if ((a.kind == "img" || a.kind == "vid") && a.frame == null) Media.preview(c, a.file, a.kind, 256)?.let { b -> a.frame = ownFrame(b.width, b.height) }
        if ((a.kind == "vid" || a.kind == "aud") && a.dur == null) a.dur = duration(a.file)
    }

    /**
     * THE POSTER A POST CARRIES (iOS MTBoard.poster, MontanaBoard 2231-2250): the picture upright, cut to its frame, its longest side
     * 640 pixels, JPEG at 0.6, shrunk by a fifth until its words fit the budget; nothing for a track or a document.
     */
    fun poster(c: Context, f: File, kind: String, fr: JSONObject?): String? {
        if (kind != "img" && kind != "vid") return null
        val reach = fr?.let { POSTER_SIDE / maxOf(0.05, minOf(it.optDouble("w", 1.0), it.optDouble("h", 1.0))) } ?: POSTER_SIDE
        val whole = Media.preview(c, f, kind, minOf(reach, 2400.0).toInt()) ?: return null
        val img = fr?.let { cut(whole, it) } ?: whole
        if (img.width <= 0 || img.height <= 0) return null
        var side = POSTER_SIDE
        while (true) {
            val scale = minOf(1.0, side / maxOf(img.width, img.height))
            val small = Bitmap.createScaledBitmap(img, maxOf(1, Math.round(img.width * scale).toInt()), maxOf(1, Math.round(img.height * scale).toInt()), true)
            val b64 = ByteArrayOutputStream().use { o -> small.compress(Bitmap.CompressFormat.JPEG, 60, o); Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP) }
            if (b64.length <= POSTER_BUDGET || side <= 120.0) return b64
            side = Math.round(side * 0.8).toDouble()
        }
    }
    /** The part of the picture its frame names, in the picture's own proportions (iOS MTBoardImage.cut). */
    private fun cut(b: Bitmap, fr: JSONObject): Bitmap {
        val x = (fr.optDouble("x", 0.0) * b.width).toInt().coerceIn(0, b.width - 1)
        val y = (fr.optDouble("y", 0.0) * b.height).toInt().coerceIn(0, b.height - 1)
        val w = (fr.optDouble("w", 1.0) * b.width).toInt().coerceIn(1, b.width - x)
        val h = (fr.optDouble("h", 1.0) * b.height).toInt().coerceIn(1, b.height - y)
        return Bitmap.createBitmap(b, x, y, w, h)
    }

    /** A track's or a film's length in seconds (iOS MontanaAudioDuration.of). */
    fun duration(f: File): Double? = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(f.path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it / 1000.0 }
        }
    }.getOrNull()

    /**
     * A PICKED FILE COMES INTO THE WALL'S OWN FOLDER AT ONCE (iOS MTBoardDrafts.take, MontanaBoardViews 1907-1915): streamed, under a
     * name of its own, its kind by its extension -- the picker's grant does not outlive the page.
     */
    fun take(c: Context, uri: Uri): MyWall.Attachment? = runCatching {
        val cr = c.contentResolver
        var name = ""
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { q -> if (q.moveToFirst()) name = q.getString(0) ?: "" }
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && it.length <= 8 && it.all(Char::isLetterOrDigit) }
            ?: android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(cr.getType(uri) ?: "")?.lowercase() ?: ""
        val dir = File(File(c.filesDir, "board"), "picked").apply { mkdirs() }
        val dest = File(dir, java.util.UUID.randomUUID().toString().lowercase() + (if (ext.isEmpty()) "" else "." + ext))
        val copied = cr.openInputStream(uri)?.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        if (copied == null) null else MyWall.Attachment(dest, kindOf(ext), name.ifEmpty { dest.name }, ext)
    }.getOrNull()
}
