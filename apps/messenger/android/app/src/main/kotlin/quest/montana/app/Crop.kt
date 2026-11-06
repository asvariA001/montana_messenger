package quest.montana.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * THE ONE CROP OF THE APP (iOS MTFrameCropView): the picked photo under a frame -- pinch and pan set what the frame keeps, the photo always
 * covering it. The face is this crop with the round frame: «Done» renders exactly the frame's square onto 640x640 (a JPEG, black where
 * nothing is painted), what you see is what everyone gets. A post's picture is it with the tile's rim (tile) and a shape of its own, the
 * shapes standing under the frame (frameCropPage): the answer is the part kept, in the picture's own proportions (kept).
 */
class CropView(ctx: Context, private val image: Bitmap, private val tile: Boolean = false, private val start: JSONObject? = null) : View(ctx) {
    private var zoom = 1f            // 1…5, over the fill-scale
    private var panX = 0f
    private var panY = 0f
    private var fw = 0f              // the frame's sides on the screen
    private var fh = 0f
    private var lift = 0f            // how far the frame stands above the centre, so the shapes under it have their room
    private var base = 1f            // the fill-scale: the photo just covers the frame
    private var placed = false
    /** The frame's shape, width over height. */
    var shape = if (tile) WallFiles.held(start?.optDouble("a", Double.NaN) ?: (image.width.toDouble() / maxOf(1, image.height))) else 1.0
        private set
    private val draw = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dim = Paint().apply { color = Color.argb(153, 0, 0, 0) }        // black 0.6 outside the frame
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {                     // the rim: white 0.8, 1.5pt
        style = Paint.Style.STROKE; color = Color.argb(204, 255, 255, 255); strokeWidth = context.dp(1.5).toFloat().coerceAtLeast(1f)
    }
    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoom = (zoom * d.scaleFactor).coerceIn(1f, 5f); cover(); invalidate(); return true
        }
    })
    private var lastX = 0f
    private var lastY = 0f
    private var pointer = -1

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        dims()
        if (!placed) { placed = true; start?.let { place(it) } }
        cover()
    }
    private fun dp(v: Float) = context.dp(v)

    /** The frame's sides: the width less the margins, never so tall that it meets the bars or, under a tile, the shapes. */
    private fun dims() {
        val least = dp(120f).toFloat()
        if (tile) {
            val a = shape.toFloat()
            fw = maxOf(least, minOf((width - dp(48f)).toFloat(), (height - 2 * dp(56f) - dp(CHOOSER_ROOM)) * a))
            fh = fw / a
            lift = dp(CHOOSER_ROOM) / 2f
        } else {
            fw = minOf(width - dp(48f), height - 2 * dp(56f)).toFloat().coerceAtLeast(least)
            fh = fw
        }
        base = maxOf(fw / image.width, fh / image.height)
    }

    /** THE PHOTO COVERS THE FRAME, ALWAYS: the pan is bounded by how much of the picture lies beyond it. */
    private fun cover() {
        val mx = maxOf(0f, (image.width * base * zoom - fw) / 2)
        val my = maxOf(0f, (image.height * base * zoom - fh) / 2)
        panX = panX.coerceIn(-mx, mx); panY = panY.coerceIn(-my, my)
    }

    private fun imageRect(): RectF {
        val s = base * zoom
        val w = image.width * s; val h = image.height * s
        val cx = width / 2f + panX; val cy = height / 2f - lift + panY
        return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }
    private fun frameRect(): RectF {
        val cy = height / 2f - lift
        return RectF(width / 2f - fw / 2, cy - fh / 2, width / 2f + fw / 2, cy + fh / 2)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        c.drawBitmap(image, null, imageRect(), draw)
        // Everything outside the frame darkens; the frame itself stays clear.
        val f = frameRect()
        val r = dp(10f).toFloat()
        val hole = Path().apply {
            if (tile) addRoundRect(f, r, r, Path.Direction.CW) else addOval(f, Path.Direction.CW)
            fillType = Path.FillType.INVERSE_WINDING
        }
        c.drawPath(hole, dim)
        if (tile) c.drawRoundRect(f, r, r, rim) else c.drawOval(f, rim)
    }

    /** The part the frame keeps now, in the picture's own proportions (iOS kept). */
    fun kept(): JSONObject {
        val dw = (image.width * base * zoom).toDouble(); val dh = (image.height * base * zoom).toDouble()
        val w = minOf(1.0, fw / dw); val h = minOf(1.0, fh / dh)
        return JSONObject().put("a", shape).put("x", (0.5 - panX / dw - w / 2).coerceIn(0.0, 1 - w)).put("y", (0.5 - panY / dh - h / 2).coerceIn(0.0, 1 - h)).put("w", w).put("h", h)
    }
    /** The zoom and the pan that keep a given part: the inverse of kept (iOS place). */
    private fun place(f: JSONObject) {
        val x = f.optDouble("x", 0.0); val y = f.optDouble("y", 0.0); val w = f.optDouble("w", 1.0); val h = f.optDouble("h", 1.0)
        zoom = (fw / (maxOf(0.0001, w) * image.width * base)).toFloat().coerceIn(1f, 5f)
        val dw = image.width * base * zoom; val dh = image.height * base * zoom
        panX = ((0.5 - x - w / 2) * dw).toFloat(); panY = ((0.5 - y - h / 2) * dh).toFloat()
        cover()
    }
    /** THE SHAPE CHANGES, THE PLACE STAYS: the new frame stands around the middle of what the old one kept (iOS onChange(of: shape)). */
    fun choose(a: Double) {
        val was = kept()
        shape = a
        dims()
        place(WallFiles.around(was.optDouble("x") + was.optDouble("w") / 2, was.optDouble("y") + was.optDouble("h") / 2, a, image.width, image.height))
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pointer = e.getPointerId(0); lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress) {
                val i = e.findPointerIndex(pointer)
                if (i >= 0) {
                    panX += e.getX(i) - lastX; panY += e.getY(i) - lastY
                    lastX = e.getX(i); lastY = e.getY(i)
                    cover(); invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // the finger that stays leads the pan
                val gone = e.actionIndex
                if (e.getPointerId(gone) == pointer) {
                    val keep = if (gone == 0) 1 else 0
                    pointer = e.getPointerId(keep); lastX = e.getX(keep); lastY = e.getY(keep)
                }
            }
        }
        return true
    }

    /** Exactly what the frame's square holds, onto 640×640, as a JPEG (iOS MTAvatarCropView.render). */
    fun render(): ByteArray {
        val out = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)   // a JPEG has no transparency: what is not painted must be black, never white
        val f = frameRect(); val r = imageRect()
        val k = 640f / fw
        c.drawBitmap(image, null, RectF((r.left - f.left) * k, (r.top - f.top) * k, (r.right - f.left) * k, (r.bottom - f.top) * k), draw)
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }
}

private const val CHOOSER_ROOM = 88f   // iOS MTFrameCropView.chooserRoom

/** A mark of the crop's bar, on its own circle (the bar's own places). */
private fun cropMark(c: Context, res: Int, gravity: Int, onTap: () -> Unit) = c.icon(res, Color.WHITE).apply {
    setPadding(dp(10), dp(10), dp(10), dp(10))
    background = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.argb(140, 28, 28, 30))
    }
    pressable(onTap)
    layoutParams = FrameLayout.LayoutParams(dp(44), dp(44), gravity).apply { setMargins(dp(12), dp(4), dp(12), 0) }
}

/** The crop page: the photo under the frame, the cross top left, the checkmark top right (the bar's own places). */
fun cropPage(act: MainActivity, image: Bitmap, onDone: (ByteArray?) -> Unit): View {
    val c = act
    val crop = CropView(c, image)
    return FrameLayout(c).apply {
        setBackgroundColor(Color.BLACK)
        addView(crop, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(cropMark(c, R.drawable.ic_close, Gravity.TOP or Gravity.START) { onDone(null) })
        addView(cropMark(c, R.drawable.ic_check, Gravity.TOP or Gravity.END) { onDone(crop.render()) })
    }
}

/**
 * A POST PICTURE'S FRAME (iOS MTFrameCropView with rim .tile and MTPictureFrame.shapes): the crop with the tile's rim and, under the frame,
 * the shapes a post takes, each a miniature of the picture itself in that shape, the picture's own first; the chosen one wears the white
 * rim (the rule of 22.09: a choice of a thing seen is shown by the thing). The answer is the part kept; null -- left.
 */
fun frameCropPage(act: MainActivity, image: Bitmap, start: JSONObject, onDone: (JSONObject?) -> Unit): View {
    val c = act
    val crop = CropView(c, image, tile = true, start = start)
    val own = WallFiles.held(image.width.toDouble() / maxOf(1, image.height))
    val options = ArrayList<Double>().apply { add(own); for (a in WallFiles.SHAPES) if (none { o -> kotlin.math.abs(o - a) < 0.001 }) add(a) }
    val marks = ArrayList<Pair<Double, View>>()
    fun dress() {
        for ((a, v) in marks) {
            val on = kotlin.math.abs(a - crop.shape) < 0.001
            v.isSelected = on
            (v as ImageView).foreground = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = c.dp(4).toFloat()
                setStroke(c.dp(if (on) 2 else 1), if (on) Color.WHITE else Color.argb(77, 255, 255, 255))
            }
        }
    }
    val row = c.hstack {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(16), 0)
        for ((k, a) in options.withIndex()) {
            val side = 44f
            val mini = ImageView(c).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(image)
                background = c.rounded(Color.BLACK, 4); outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; clipToOutline = true
            }
            marks.add(a to mini)
            addView(FrameLayout(c).apply {
                contentDescription = if (k == 0) c.getString(R.string.wall_frame_original) else ratio(a)
                addView(mini, FrameLayout.LayoutParams(c.dp(if (1 <= a) side else side * a.toFloat()), c.dp(if (1 <= a) side / a.toFloat() else side), Gravity.CENTER))
                pressable { crop.choose(a); dress() }
            }, lp(dp(56), dp(56)).apply { marginEnd = dp(8) })
        }
    }
    dress()
    return FrameLayout(c).apply {
        setBackgroundColor(Color.BLACK)
        addView(crop, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(cropMark(c, R.drawable.ic_close, Gravity.TOP or Gravity.START) { onDone(null) })
        addView(cropMark(c, R.drawable.ic_check, Gravity.TOP or Gravity.END) { onDone(crop.kept()) })
        // the shapes stand over the gestures, not inside them: a scroll of the row never pans the photo
        addView(android.widget.HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }, FrameLayout.LayoutParams(MATCH, c.dp(64), Gravity.BOTTOM).apply { bottomMargin = c.dp(12) })
    }
}

/** A shape's ratio in digits, for the voice: «4:5», «16:9». */
private fun ratio(a: Double): String {
    for ((w, h) in listOf(1 to 1, 4 to 5, 3 to 4, 9 to 16, 4 to 3, 16 to 9)) if (kotlin.math.abs(w.toDouble() / h - a) < 0.001) return "$w:$h"
    return "%.2f".format(java.util.Locale.ROOT, a)
}
