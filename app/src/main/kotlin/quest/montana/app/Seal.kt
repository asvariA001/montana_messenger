package quest.montana.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * THE SEAL BESIDE MONTANA'S NAME (iOS MTMontanaSealMark, 20f752ae; the author's word 19246, 11.11.2026: the blue scalloped seal
 * with a white check, checkmark.seal.fill, its blue read off his picture, 84, 160, 248). The room's one mark: the chats list (18),
 * the chat's head (16), the found rows. It stands where the crown waited; the name before it is shown without its leading crown
 * (iOS MontanaAvatar.spokenName drops a first emoji from the shown name, the kept name still wears it: Book.MONTANA_TITLE).
 * Android has no symbol font for it, so it is drawn: a disc whose rim waves in twelve scallops, the check stroked over it.
 */
class MontanaSealSpan(private val sizePx: Float, private val gapPx: Float) : ReplacementSpan() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(84, 160, 248); style = Paint.Style.FILL }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = sizePx * 0.11f
    }
    private val seal = Path().apply {
        val r = sizePx / 2f
        val steps = 144
        for (k in 0..steps) {
            val a = 2.0 * PI * k / steps
            val rr = r * (0.91f + 0.09f * cos(12.0 * a).toFloat())
            val x = r + rr * cos(a).toFloat(); val y = r + rr * sin(a).toFloat()
            if (k == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    private val check = Path().apply {
        moveTo(sizePx * 0.30f, sizePx * 0.52f)
        lineTo(sizePx * 0.44f, sizePx * 0.65f)
        lineTo(sizePx * 0.71f, sizePx * 0.37f)
    }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        (gapPx + sizePx + 0.5f).toInt()

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val fm = paint.fontMetrics
        val mid = y + (fm.ascent + fm.descent) / 2f   // the middle of the letters, not of the line's leading
        canvas.save()
        canvas.translate(x + gapPx, mid - sizePx / 2f)
        canvas.drawPath(seal, fill)
        canvas.drawPath(check, tick)
        canvas.restore()
    }
}

/** A first emoji (with what joins it) and the spaces after it leave the shown name (iOS MontanaAvatar.spokenName). */
private val LEADING_MARK = Regex("^[\\p{So}\\p{Sk}\\x{FE0F}\\x{200D}]+\\s*")

/**
 * The name a row or a head shows: for the Montana room the name without its crown and the seal after it, 6 apart (iOS HStack
 * spacing 6); every other chat's name stands as it is.
 */
fun Context.montanaNamed(name: CharSequence, ref: String, sizeDp: Int): CharSequence {
    if (!Book.isMontana(ref)) return name
    val shown = name.toString().replaceFirst(LEADING_MARK, "").ifBlank { Book.MONTANA }
    return SpannableStringBuilder(shown).append("⁠", MontanaSealSpan(dp(sizeDp).toFloat(), dp(6).toFloat()), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}
