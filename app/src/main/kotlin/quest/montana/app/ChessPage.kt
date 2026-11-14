package quest.montana.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID

// ─────────────────────────── chess between two phones (iOS MTChessScreen: MTChessSend, MTChessCoins, MTChessAnchor, MTChessWords, MTChessPage, MTChessGameScreen) ───────────────────────────

/**
 * THE GAME'S LETTERS RIDE THE PAIR'S OWN ROAD (iOS MTChessSend): a letter of a game is an ordinary letter of the pair; only the
 * invitation and the closing letter ring, every other step rides a silent push and stands in the feed for the board alone.
 * NO COINS IN CHESS (iOS 480cd898, the author's word 29.10.2026 19:31 UTC: «in chess there must be no crediting of coins»): a
 * letter names no pot, no stake and no move's coin; coins are born only by the core, one coin a second.
 */
object ChessSend {
    const val CHAT_CLOCK = 600
    fun now(): Long = System.currentTimeMillis()
    /** One presence life (iOS MTChessSend.meetingLife = ChatStore.presenceLife, 45 s): how fresh an invitation the board follows. */
    const val MEETING_LIFE = 45_000L

    /** The game's letter a row is in its own right: a forward, a reply, an edited row or a file is none. */
    fun letterOf(m: Msg): ChessLetter? = if (m.qt != null || m.edited || m.file != null) null else ChessLetter.parse(m.text)
    fun entries(ref: String): List<ChessEntry> = Book.chat(ref)?.msgs?.mapNotNull { m -> letterOf(m)?.let { ChessEntry(it, m.mine) } } ?: emptyList()
    /** A game as the replay reads it now, against the pair's one game: a game a newer invitation replaced reads as over. */
    fun state(ref: String, game: String): ChessGame? {
        val all = entries(ref)
        return ChessGame.of(all.filter { it.letter.game == game }, game)?.replaced(ChessGame.current(all))
    }
    fun canPlay(ref: String): Boolean = CoinSend.canPay(ref)

    fun send(c: Context, ref: String, packet: ChessLetter): Boolean {
        // A REFUSED LETTER SAYS WHY (iOS aabfac62, 03.10: T1 held both invitations and not one chess letter left it, in silence)
        val why = if (Groups.isKey(ref)) "group" else if (Book.chat(ref) == null) "no-chat" else if (Book.secret(ref) == null) "no-pipe"
            else if (PeerSafety.isBlocked(ref)) "refused" else ""
        if (why.isNotEmpty() || !canPlay(ref)) { Log.i("Montana", "chess_refused kind=" + packet.kind + " why=" + why.ifEmpty { "pair" } + " conv=" + ref.take(10)); return false }
        val text = packet.text(c.getString(R.string.chess_title))
        val mid = Marks.mintMid()
        Book.edit(ref) { it.msgs.add(Msg(mid, text, true, Marks.birthMs(mid) ?: now())) }
        Post.send(ref, mid, text)
        Log.i("Montana", "chess_tx kind=" + packet.kind + " game=" + packet.game.take(8) + " conv=" + ref.take(10))
        return true
    }

    /** A step of theirs landed (Post): kept as a row for the board, read at once, receipted; the closing letter rings. */
    fun landed(ref: String, mid: String, text: String) {
        val letter = ChessLetter.parse(text) ?: return
        var fresh = false
        Book.edit(ref) { c ->
            if (c.msgs.none { it.mid == mid }) {
                c.msgs.add(Msg(mid, text, false, Marks.birthMs(mid) ?: now(), state = 3, statusAt = now()))
                c.msgs.sortBy { it.at }
                fresh = true
            }
        }
        Post.receiptFor(ref, mid)
        if (fresh && letter.rings && shownGame != letter.game) Notify.letter(ref, text)
    }

    fun invite(c: Context, ref: String, seconds: Int): String? {
        val id = UUID.randomUUID().toString().lowercase()
        val packet = ChessLetter(id, id, "", ChessLetter.INVITE, now(), seconds = seconds)
        return if (send(c, ref, packet)) id else null
    }
    /** ENTERING IS THE PERSON'S PRESS (iOS join): an unanswered invitation of theirs, accepted by the replay's own action. */
    fun join(c: Context, ref: String, game: String): String? {
        val now = state(ref, game)
        val event = now?.takeIf { it.status == ChessGame.Status.INVITED && !it.whiteIsMine }?.action(ChessLetter.ACCEPT, now())
        if (event == null) {
            // AN ACCEPTANCE THAT CANNOT BE MADE SAYS THE GAME'S STATE (iOS aabfac62 chess_join)
            Log.i("Montana", "chess_join game=" + game.take(8) + " sent=0 rows=" + (Book.chat(ref)?.msgs?.size ?: -1) + " status=" + (now?.status ?: "none") +
                " mine=" + (if (now?.whiteIsMine == true) 1 else 0) + " waiting=" + (if (now?.waitingForHistory == true) 1 else 0))
            return null
        }
        return if (send(c, ref, event)) event.id else null
    }
    fun awaitsMe(ref: String, game: String): Boolean = state(ref, game)?.let { it.status == ChessGame.Status.INVITED && !it.whiteIsMine } ?: false

    /** The game on the screen: its own letters raise no banner (iOS MTChessGameScreen.shownGame). */
    @Volatile var shownGame: String? = null

}

/**
 * WHEN THE RUNNING CLOCK BEGAN, ON THIS PHONE (iOS MTChessAnchor): my turn from the moment the correspondent's letter landed here,
 * theirs from the moment their phone confirmed mine — a letter on the road is nobody's thinking; a letter whose moment this phone
 * never saw runs from its own stamp.
 */
class ChessAnchor private constructor(private val kind: Int, private val at: Long) {
    val runs: Boolean get() = kind != 0
    fun elapsed(now: Long = System.currentTimeMillis()): Long = if (kind == 0) 0L else maxOf(0L, now - at)
    companion object {
        val STANDING = ChessAnchor(0, 0)
        fun of(g: ChessGame, ref: String): ChessAnchor {
            val side = g.running ?: return STANDING
            val row = Book.chat(ref)?.msgs?.lastOrNull { ChessSend.letterOf(it)?.id == g.last.id } ?: return ChessAnchor(2, g.last.at)
            if (side == g.mySide) return if (0L < row.statusAt) ChessAnchor(1, row.statusAt) else ChessAnchor(2, g.last.at)
            return if (row.state == 2 || row.state == 3) ChessAnchor(1, row.statusMoment) else STANDING
        }
    }
}

/** The words of a game (iOS MTChessWords). */
object ChessWords {
    fun status(c: Context, g: ChessGame, computer: Boolean = false): String {
        if (g.waitingForHistory) return c.getString(R.string.chess_waiting_history)
        return c.getString(when (g.status) {
            ChessGame.Status.INVITED -> if (g.whiteIsMine) R.string.chess_waiting_accept else R.string.chess_invitation
            ChessGame.Status.PLAYING -> if (g.position.checked(g.position.turn)) R.string.chess_check
                else if (g.position.turn == g.mySide) R.string.chess_your_move
                else if (computer) R.string.chess_computer_thinking else R.string.chess_their_move
            ChessGame.Status.DECLINED -> R.string.chess_declined
            ChessGame.Status.WHITE_WON -> R.string.chess_white_wins
            ChessGame.Status.BLACK_WON -> R.string.chess_black_wins
            ChessGame.Status.DRAWN -> R.string.chess_draw
            ChessGame.Status.ENDED -> R.string.chess_ended
            ChessGame.Status.CONFLICT -> R.string.chess_conflict
        })
    }
    fun control(c: Context, seconds: Int): String = if (seconds == 0) c.getString(R.string.chess_no_clock) else (seconds / 60).toString() + ":00"
    /** The invitation's seat and clock — and nothing of coins (iOS 480cd898). */
    fun seat(c: Context, l: ChessLetter, mine: Boolean): String =
        c.getString(if (mine) R.string.chess_you_white else R.string.chess_you_black) + " · " + control(c, l.seconds ?: 0)
    fun clock(ms: Long): String {
        val s = maxOf(0L, (ms + 999) / 1000)
        return (s / 60).toString() + ":" + (s % 60).toString().padStart(2, '0')
    }
    fun computer(c: Context, level: Int): String = c.getString(R.string.chess_computer_level, level)
    fun score(c: Context, s: ChessScore): String = c.getString(R.string.chess_score, s.mine, s.theirs, s.drawn)
}

/**
 * THE TAKEN PIECES (iOS MTChessCaptured, 480cd898, the author's word 29.10.2026 19:43 UTC: «the captured pieces all shown large,
 * normally -- some show normal and some small»): the board's own artwork, not the font's letters (a black pawn came as an emoji,
 * the rest as text), every piece in one square -- 24, else 18, else 14 to fit the width -- a kind's pieces overlapping, the kinds
 * apart, the advantage in points after them.
 */
class ChessCapturedView(c: Context) : View(c) {
    private var piles: List<List<ChessPiece>> = emptyList()
    private var advantage = 0
    private var side = 0f
    private val art = HashMap<Int, Bitmap>()
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MT.gray; isFakeBoldText = true }
    private val src = Rect()
    private val box = RectF()
    fun set(pieces: List<ChessPiece>, advantage: Int) {
        piles = pieces.groupBy { it.kind }.toList().sortedWith(compareBy({ it.first.points }, { it.first.raw })).map { it.second }
        this.advantage = advantage
        // USER-DATA: the taken pieces' names, each from the catalogue, and the advantage in points
        contentDescription = pieces.joinToString(", ") { context.getString(it.word) } + (if (0 < advantage) " +$advantage" else "")
        requestLayout(); invalidate()
    }
    private val plus: String get() = "+$advantage"
    private fun width(s: Float): Float {
        var w = 0f
        for ((i, pile) in piles.withIndex()) { if (0 < i) w += s * 0.12f; w += s + (pile.size - 1) * s * 0.45f }
        if (0 < advantage) { ink.textSize = s * 0.7f; if (piles.isNotEmpty()) w += s * 0.12f; w += ink.measureText(plus) }
        return w
    }
    override fun onMeasure(w: Int, h: Int) {
        val avail = MeasureSpec.getSize(w).toFloat()
        val d = resources.displayMetrics.density
        side = listOf(24f, 18f, 14f).map { it * d }.firstOrNull { MeasureSpec.getMode(w) == MeasureSpec.UNSPECIFIED || width(it) <= avail } ?: 14f * d
        setMeasuredDimension(MeasureSpec.getSize(w), (24f * d).toInt())
    }
    private fun bitmap(res: Int): Bitmap = art.getOrPut(res) { BitmapFactory.decodeResource(resources, res) }
    override fun onDraw(canvas: Canvas) {
        val s = side; val top = (height - s) / 2f
        var x = 0f
        for ((i, pile) in piles.withIndex()) {
            if (0 < i) x += s * 0.12f
            for ((j, p) in pile.withIndex()) {
                val b = bitmap(p.art); src.set(0, 0, b.width, b.height)
                box.set(x + j * s * 0.45f, top, x + j * s * 0.45f + s, top + s)
                canvas.drawBitmap(b, src, box, null)
            }
            x += s + (pile.size - 1) * s * 0.45f
        }
        if (0 < advantage) {
            ink.textSize = s * 0.7f
            if (piles.isNotEmpty()) x += s * 0.12f
            canvas.drawText(plus, x, height / 2f - (ink.ascent() + ink.descent()) / 2f, ink)
        }
    }
}

/**
 * THE BOARD (iOS MTChessBoardCanvas): the supplied board's two greens sampled in sRGB, the last move and the selected square lit,
 * the king in check on red, the author's pieces drawn into their squares, the destinations as dots and rings, the files and ranks
 * in the corner ink. A tap names its square; the board takes no finger while no move can be made.
 */
class ChessBoardView(c: Context) : View(c) {
    var position: ChessPosition = ChessPosition.start()
    var facing = ChessSide.WHITE
    var selected: Int? = null
    var destinations: Set<Int> = emptySet()
    var lastMove: ChessMove? = null
    var enabledMoves = false
    var onSquare: ((Int) -> Unit)? = null
    private val art = HashMap<Int, Bitmap>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
    private val src = Rect()
    private val box = RectF()
    companion object {
        val LIGHT = Color.rgb(238, 238, 214)
        val DARK = Color.rgb(124, 149, 92)
        val LAST_LIGHT = Color.rgb(246, 246, 146)
        val LAST_DARK = Color.rgb(186, 202, 68)
    }
    private fun bitmap(res: Int): Bitmap = art.getOrPut(res) { BitmapFactory.decodeResource(resources, res) }
    private fun square(row: Int, col: Int): Int = if (facing == ChessSide.WHITE) (7 - row) * 8 + col else row * 8 + 7 - col
    fun redraw() { invalidate(); contentDescription = context.getString(R.string.chess_title) }
    override fun onMeasure(w: Int, h: Int) { val s = MeasureSpec.getSize(w); setMeasuredDimension(s, s) }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!enabledMoves) return false
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val cell = width / 8f
            if (0f < cell) onSquare?.invoke(square(minOf(7, (e.y / cell).toInt()), minOf(7, (e.x / cell).toInt())))
            performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    // THE BOARD SAYS EVERY SQUARE (iOS MTChessBoard accessibilityElements 100-120): sixty-four elements in the board's order, each its
    // square and the piece on it, a double tap on one presses it as a finger would -- the platform's own virtual views, no library
    private var spoken = View.NO_ID
    private fun place(sq: Int): Pair<Int, Int> = if (facing == ChessSide.WHITE) (7 - sq / 8) to sq % 8 else sq / 8 to 7 - sq % 8
    private fun squareWord(sq: Int): String = ChessPosition.square(sq) + (position.piece(sq)?.let { ", " + context.getString(it.word) } ?: "")
    private fun announce(sq: Int, type: Int) {
        if (!isAttachedToWindow) return
        val e = android.view.accessibility.AccessibilityEvent.obtain(type)
        e.packageName = context.packageName; e.className = "android.widget.Button"; e.setSource(this, sq)
        e.contentDescription = squareWord(sq)
        parent?.requestSendAccessibilityEvent(this, e)
    }
    private val squares = object : android.view.accessibility.AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(id: Int): android.view.accessibility.AccessibilityNodeInfo? {
            val me = this@ChessBoardView
            if (id == View.NO_ID) {
                val info = android.view.accessibility.AccessibilityNodeInfo.obtain(me)
                onInitializeAccessibilityNodeInfo(info)
                for (row in 0 until 8) for (col in 0 until 8) info.addChild(me, square(row, col))
                return info
            }
            if (id !in 0 until 64) return null
            val info = android.view.accessibility.AccessibilityNodeInfo.obtain(me, id)
            val (row, col) = place(id); val cell = width / 8
            val r = Rect(col * cell, row * cell, (col + 1) * cell, (row + 1) * cell)
            @Suppress("DEPRECATION") info.setBoundsInParent(r)
            val at = IntArray(2); getLocationOnScreen(at); r.offset(at[0], at[1]); info.setBoundsInScreen(r)
            info.setParent(me); info.packageName = context.packageName; info.className = "android.widget.Button"
            info.contentDescription = squareWord(id)
            info.isEnabled = true; info.isVisibleToUser = true; info.isFocusable = true; info.isSelected = selected == id
            info.isAccessibilityFocused = spoken == id
            info.addAction(if (spoken == id) android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                else android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
            if (enabledMoves) { info.isClickable = true; info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK) }
            return info
        }
        override fun performAction(id: Int, action: Int, args: android.os.Bundle?): Boolean {
            if (id == View.NO_ID) return performAccessibilityAction(action, args)
            when (action) {
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (spoken == id) return false
                    spoken = id; invalidate(); announce(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED); return true
                }
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (spoken != id) return false
                    spoken = View.NO_ID; invalidate(); announce(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED); return true
                }
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK -> {
                    if (!enabledMoves) return false
                    onSquare?.invoke(id); announce(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED); return true
                }
            }
            return false
        }
    }
    override fun getAccessibilityNodeProvider(): android.view.accessibility.AccessibilityNodeProvider = squares
    private var hovered = View.NO_ID
    override fun dispatchHoverEvent(e: MotionEvent): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
        if (am == null || !am.isTouchExplorationEnabled) return super.dispatchHoverEvent(e)
        val cell = width / 8f
        val sq = if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT || cell <= 0f || e.x < 0 || e.y < 0 || width <= e.x || height <= e.y) View.NO_ID
            else square(minOf(7, (e.y / cell).toInt()), minOf(7, (e.x / cell).toInt()))
        if (sq != hovered) {
            if (sq != View.NO_ID) announce(sq, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            if (hovered != View.NO_ID) announce(hovered, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
            hovered = sq
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val cell = width / 8f
        val check = ChessSide.values().filter { position.checked(it) }.toSet()
        for (row in 0 until 8) for (col in 0 until 8) {
            val sq = square(row, col)
            val light = (sq / 8 + sq % 8) % 2 == 1
            box.set(col * cell, row * cell, (col + 1) * cell, (row + 1) * cell)
            val lit = lastMove?.from == sq || lastMove?.to == sq || selected == sq
            fill.style = Paint.Style.FILL
            fill.color = if (lit) (if (light) LAST_LIGHT else LAST_DARK) else (if (light) LIGHT else DARK)
            canvas.drawRect(box, fill)
            val p = position.piece(sq)
            if (p != null) {
                if (p.kind == ChessKind.KING && p.side in check) { fill.color = Color.argb(140, 255, 69, 58); canvas.drawRect(box, fill) }
                val b = bitmap(p.art)
                src.set(0, 0, b.width, b.height)
                canvas.drawBitmap(b, src, box, null)
            }
            if (sq in destinations) {
                if (p == null) {
                    fill.color = Color.argb(51, 0, 0, 0)
                    canvas.drawOval(RectF(box.left + cell * 0.36f, box.top + cell * 0.36f, box.right - cell * 0.36f, box.bottom - cell * 0.36f), fill)
                } else {
                    fill.style = Paint.Style.STROKE; fill.strokeWidth = cell * 0.07f; fill.color = Color.argb(64, 0, 0, 0)
                    canvas.drawOval(RectF(box.left + cell * 0.055f, box.top + cell * 0.055f, box.right - cell * 0.055f, box.bottom - cell * 0.055f), fill)
                    fill.style = Paint.Style.FILL
                }
            }
            ink.color = if (light) DARK else LIGHT
            ink.textSize = maxOf(10f * resources.displayMetrics.density, cell * 0.16f)
            if (col == 0) canvas.drawText((sq / 8 + 1).toString(), box.left + 3f, box.top + ink.textSize, ink)
            if (row == 7) canvas.drawText(ChessPosition.square(sq).take(1), box.right - cell * 0.18f, box.bottom - cell * 0.06f, ink)
        }
    }
}

/** The game's state on its own plate over the board: the app's glass while no move can be made (iOS MTChessStatePlate). */
private fun Context.statePlate(build: LinearLayout.() -> Unit) = vstack {
    background = rounded(Color.argb(235, 28, 28, 30), 22)
    setPadding(dp(22), dp(10), dp(22), dp(10))
    minimumWidth = dp(180)
    build()
}

/** The computer's face (iOS MTChessComputerFace): the processor's glyph on a quiet round plate, as a person's face stands. */
fun Context.computerFace(size: Int): View = FrameLayout(this).apply {
    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.argb(20, 255, 255, 255)) }
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    addView(icon(R.drawable.ic_cpu, Color.WHITE), FrameLayout.LayoutParams(dp((size * 0.45f).toInt()), dp((size * 0.45f).toInt()), Gravity.CENTER))
}

/** A round mark of the bar: the glyph alone, the word for the screen reader (iOS MontanaBarMark). */
private fun Context.barMark(glyph: Int, word: Int, tint: Int = Color.WHITE, onTap: () -> Unit) = icon(glyph, tint).apply {
    setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = getString(word); pressable(onTap)
}

/**
 * THE GAME'S SCREEN (iOS MTChessGameScreen): the move strip, the correspondent above and me below with our faces, the pieces each
 * has taken and the clocks, the board between, the game's state on its plate over the board while no move can be made, the
 * status, the delivery of my last letter, and the bar: the moves, the chat, the move before and after. The menu holds the
 * flip, the live position, the draws, the resignation, the end and the next game under the pair's score.
 */
fun chessBoardPage(act: MainActivity, ref0: String, game0: String, inChat: Boolean, onBack: () -> Unit): View {
    val c: Context = act
    // THE GAME LIVES IN THE PAIR'S CONVERSATION (iOS MTChessSend.conversation, 8a627c90): a pipe folded into another reads and sends
    // the game where its letters now land, so a fold never strands a game
    val ref = if (ref0.isEmpty()) "" else SamePair.root(ref0)
    // THE TRAINING GAME'S SEAT (iOS MTChessGameScreen seat .computer): no pair, the phone's own engine across the board (ChessComputer)
    val computer = ref.isEmpty()
    var gameId = game0
    var game: ChessGame? = null
    var anchor = ChessAnchor.STANDING
    var browsing: Int? = null
    var selected: Int? = null
    var flipped = false
    var offerDraw = false
    var claimWithMove = false
    var pending: String? = null
    var pairOver = true
    var sawLive = false
    var boardSaid = ""                  // the board's last diary line (chess_board), said again only when it changes
    var timeoutAsked: String? = null   // the clock's end is asked once a turn, never again at every tick
    var score = ChessScore(emptyList())
    val main = Handler(Looper.getMainLooper())

    val board = ChessBoardView(c)
    val plateHolder = FrameLayout(c)
    val strip = c.hstack { setPadding(dp(12), 0, dp(12), 0) }
    val stripScroll = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(strip) }
    val topRow = c.hstack { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
    val bottomRow = c.hstack { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
    val status = c.text("", 15f)
    val drawMark = c.barMark(R.drawable.ic_equal, R.string.chess_accept_draw) { }
    val notes = c.text("", 12f, MT.gray, center = true)
    val delivery = c.hstack { gravity = Gravity.CENTER }
    val clocks = HashMap<ChessSide, TextView>()

    fun chat() = Book.chat(ref)
    fun allowed() = computer || ChessSend.canPlay(ref)
    fun canSend() = pending == null && allowed() && game?.waitingForHistory == false && !(computer && ChessComputer.thinking)
    fun live() = browsing == null && canSend()
    fun failed() { AlertDialog.Builder(act).setTitle(R.string.chess_move_failed).setPositiveButton(R.string.ok, null).show() }

    lateinit var draw: () -> Unit
    lateinit var reload: () -> Unit

    fun send(kind: String, move: String? = null, drawing: Boolean = false) {
        val g = game ?: return
        if (computer) {
            if (pending != null || ChessComputer.thinking) return
            if (!(kind == ChessLetter.END || browsing == null)) return
            selected = null
            if (!ChessComputer.play(kind, move)) failed()
            reload(); return
        }
        val free = pending == null && allowed()
        if (!(if (kind == ChessLetter.END) free else canSend())) return
        if (!(kind == ChessLetter.END || kind == ChessLetter.TIMEOUT || browsing == null)) return
        selected = null
        // the action is judged against the letters as they stand now, never against an older board on the screen
        val current = ChessSend.state(ref, gameId)
        if (current == null || current.last.id != g.last.id) { failed(); return }
        val spent = if (current.running == current.mySide) ChessAnchor.of(current, ref).elapsed() else null
        val event = current.action(kind, ChessSend.now(), move, drawing, spent)
        if (event == null) { failed(); return }
        pending = event.id
        if (!ChessSend.send(c, ref, event)) { pending = null; failed() }
        reload()
    }
    fun play(m: ChessMove) {
        if (claimWithMove) { send(ChessLetter.CLAIM, m.uci); claimWithMove = false }
        else { send(ChessLetter.MOVE, m.uci, offerDraw); offerDraw = false }
    }
    fun promote(moves: List<ChessMove>) {
        val g = game ?: return
        lateinit var dialog: AlertDialog
        val row = c.hstack {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(16), dp(12), dp(8))
            for (m in moves) {
                val kind = m.promotion ?: continue
                addView(ImageView(c).apply {
                    setImageResource(ChessPiece(g.mySide, kind).art); setPadding(dp(4), dp(4), dp(4), dp(4)); contentDescription = c.getString(ChessPiece(g.mySide, kind).word)
                    pressable { dialog.dismiss(); play(m) }
                }, lp(dp(60), dp(60)).apply { marginEnd = dp(10) })
            }
        }
        dialog = AlertDialog.Builder(act).setTitle(R.string.chess_promote).setView(row).setNegativeButton(R.string.chess_cancel, null).create()
        dialog.show()
    }
    fun press(sq: Int) {
        val g = game ?: return
        if (!live() || g.status != ChessGame.Status.PLAYING || g.mySide != g.position.turn) return
        val sel = selected
        if (sel != null) {
            val moves = g.position.legalMoves(sel).filter { it.to == sq }
            if (1 < moves.size) { promote(moves); return }
            if (moves.isNotEmpty()) { play(moves[0]); return }
        }
        selected = if (g.position.piece(sq)?.side == g.mySide && sel != sq) sq else null
        draw()
    }
    fun browse(delta: Int) {
        val g = game ?: return
        val n = maxOf(0, minOf(g.moves.size, (browsing ?: g.moves.size) + delta))
        browsing = if (n == g.moves.size) null else n; selected = null; draw()
    }
    fun again(g: ChessGame) {
        browsing = null; selected = null
        if (computer) { ChessComputer.start(ChessComputer.level, g.whiteIsMine); reload(); return }
        val id = ChessSend.invite(c, ref, g.seconds)
        if (id == null) failed() else { gameId = id; ChessSend.shownGame = id; reload() }
    }
    fun captured(g: ChessGame, side: ChessSide): Pair<List<ChessPiece>, Int> {
        val step = minOf(browsing ?: g.moves.size, g.moves.size)
        val remaining = g.positions[step].board.filterNotNull()
        val taken = ArrayList<ChessPiece>()
        for (i in 0 until step) {
            val m = g.moves[i]; val before = g.positions[i]
            if (before.turn != side) continue
            val mover = before.piece(m.from) ?: continue
            val piece = before.piece(m.to)
            if (piece != null) taken.add(piece) else if (mover.kind == ChessKind.PAWN && m.from % 8 != m.to % 8) taken.add(ChessPiece(side.other, ChessKind.PAWN))
        }
        val advantage = remaining.filter { it.side == side }.sumOf { it.kind.points } - remaining.filter { it.side != side }.sumOf { it.kind.points }
        return taken.sortedBy { it.kind.points } to maxOf(0, advantage)
    }
    fun player(row: LinearLayout, g: ChessGame, side: ChessSide) {
        val mine = side == g.mySide
        row.removeAllViews()
        val name = if (mine) Prefs.userName.ifBlank { c.getString(R.string.coin_you) }
            else if (computer) ChessWords.computer(c, ChessComputer.level) else (chat()?.shown?.ifBlank { null } ?: c.getString(R.string.peer))
        row.addView(if (mine) c.avatar(SelfFace.load(c), name, 42) else if (computer) c.computerFace(42) else c.peerFace(ref, name, 42), lp(c.dp(42), c.dp(42)))
        row.gap(10)
        row.addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(c.text(name, 17f, Color.WHITE, bold = true).apply { singleLineEllipsis() })   // USER-DATA: a player's name
            addView(ChessCapturedView(c).apply { val (p, a) = captured(g, side); set(p, a) }, lp(MATCH, WRAP).apply { topMargin = dp(4) })
        }, lp(0, WRAP, 1f))
        // the training game has no clock: the engine's side shows its thinking (iOS ProgressView, 44 square)
        if (computer) {
            if (!mine && ChessComputer.thinking) row.addView(android.widget.ProgressBar(c).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE); setPadding(dp(10), dp(10), dp(10), dp(10))
            }, lp(c.dp(44), c.dp(44)))
            return
        }
        val clock = c.text("", 20f, Color.WHITE, bold = true, center = true).apply {
            setPadding(dp(12), 0, dp(12), 0); minWidth = dp(94); minHeight = dp(44); gravity = Gravity.CENTER
            contentDescription = c.getString(if (side == ChessSide.WHITE) R.string.chess_white_clock else R.string.chess_black_clock)
        }
        clocks[side] = clock
        row.addView(clock, lp(WRAP, WRAP))
    }
    fun tick() {
        val g = game ?: return
        for ((side, clock) in clocks) {
            val active = g.running == side && anchor.runs
            val ms = g.remaining(side, if (active) anchor.elapsed() else 0L)
            clock.text = if (g.seconds == 0) "∞" else ChessWords.clock(ms)   // USER-DATA: a clock
            clock.setTextColor(if (active) Color.BLACK else Color.WHITE)
            clock.background = c.rounded(if (active) Color.WHITE else Color.argb(18, 255, 255, 255), 8)
            if (active && side == g.mySide && 0 < g.seconds && ms == 0L && pending == null && timeoutAsked != g.last.id) {
                timeoutAsked = g.last.id
                send(ChessLetter.TIMEOUT)
            }
        }
    }
    val ticker = object : Runnable { override fun run() { tick(); main.postDelayed(this, 250) } }

    draw = {
        val g = game
        if (g != null) {
            val step = minOf(browsing ?: g.moves.size, g.moves.size)
            val position = g.positions[step]
            val facing = if (flipped) g.mySide.other else g.mySide
            board.position = position; board.facing = facing; board.selected = selected
            board.destinations = selected?.let { s -> position.legalMoves(s).map { it.to }.toSet() } ?: emptySet()
            board.lastMove = if (0 < step) g.moves[step - 1] else null
            board.enabledMoves = live() && g.status == ChessGame.Status.PLAYING && g.position.turn == g.mySide
            board.redraw()
            strip.removeAllViews()
            g.notation.forEachIndexed { i, word ->
                strip.addView(c.text((if (i % 2 == 0) (i / 2 + 1).toString() + ". " else "") + word, 15f, Color.WHITE, bold = (browsing ?: g.moves.size) == i + 1).apply {
                    typeface = android.graphics.Typeface.MONOSPACE; gravity = Gravity.CENTER; minHeight = c.dp(44); minWidth = c.dp(44)
                    pressable { browsing = if (i + 1 == g.moves.size) null else i + 1; selected = null; draw() }
                }, lp(WRAP, WRAP).apply { marginEnd = c.dp(16) })
            }
            if (browsing == null) stripScroll.post { stripScroll.fullScroll(View.FOCUS_RIGHT) }
            player(topRow, g, facing.other); player(bottomRow, g, facing)
            plateHolder.removeAllViews()
            if (browsing == null && (g.waitingForHistory || g.status != ChessGame.Status.PLAYING)) {
                plateHolder.addView(c.statePlate {
                    addView(c.text(ChessWords.status(c, g, computer), 17f, Color.WHITE, bold = true, center = true).apply { maxLines = 3 }, lp(WRAP, WRAP))
                    addView(c.hstack {
                        gravity = Gravity.CENTER
                        if (g.status == ChessGame.Status.INVITED) {
                            if (!g.whiteIsMine) addView(c.barMark(R.drawable.ic_check, R.string.chess_accept_invitation) { send(ChessLetter.ACCEPT) }, lp(c.dp(44), c.dp(44)))
                            addView(c.barMark(R.drawable.ic_close, if (g.whiteIsMine) R.string.chess_withdraw else R.string.chess_decline_invitation) { send(ChessLetter.DECLINE) }, lp(c.dp(44), c.dp(44)))
                        } else if (g.status.finished && (computer || pairOver)) addView(c.barMark(R.drawable.ic_plus, R.string.chess_new_game) { again(g) }, lp(c.dp(44), c.dp(44)))
                        alpha = if (live()) 1f else 0.4f
                    }, lp(WRAP, WRAP))
                }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            }
            status.text = if (g.status == ChessGame.Status.PLAYING && !g.waitingForHistory) ChessWords.status(c, g, computer) else ""
            drawMark.visibility = if (g.drawOfferedBy == g.mySide.other && g.status == ChessGame.Status.PLAYING) View.VISIBLE else View.GONE
            notes.text = listOfNotNull(if (!allowed()) c.getString(R.string.chess_unavailable) else null,
                if (claimWithMove) c.getString(R.string.chess_claim_hint) else null).joinToString("\n")
            delivery.removeAllViews()
            val row = chat()?.msgs?.lastOrNull { ChessSend.letterOf(it)?.id == g.last.id }
            if (row != null && row.mine) delivery.addView(c.text(c.getString(when (row.state) {
                -1 -> R.string.chess_not_sent; 0 -> R.string.chess_sending; 1 -> R.string.chess_sent; 2 -> R.string.chess_delivered; else -> R.string.chess_read
            }), 12f, MT.gray))
            tick()
        }
    }
    reload = {
        if (computer) {
            val next = ChessComputer.game
            if (game?.last?.id != next?.last?.id) selected = null
            game = next; anchor = ChessAnchor.STANDING; pairOver = true; pending = null
            draw()
        } else {
        val all = ChessSend.entries(ref)
        val current = ChessGame.current(all)
        val games = all.groupBy { it.letter.game }.mapNotNull { (id, list) -> ChessGame.of(list, id)?.replaced(current) }
        var next = games.firstOrNull { it.id == gameId }
        // BOTH AT THE BOARD, THE NEXT GAME BEGINS AT ONCE (iOS reload «switched», the author's word 30.09): a board on the screen whose
        // game is over follows the correspondent's fresh invitation onto its game, as the maker's own board moved onto it (again);
        // the acceptance stays the person's press on the plate (06.10)
        if (current != null && current != gameId && next?.status?.finished == true && ChessSend.shownGame == gameId && CallScreen.front != null &&
            games.firstOrNull { it.id == current }?.meets(ChessSend.now(), ChessSend.MEETING_LIFE) == true) {
            gameId = current; ChessSend.shownGame = current; browsing = null; sawLive = false
            next = games.firstOrNull { it.id == current }
        }
        if (game?.last?.id != next?.last?.id || game?.status != next?.status) selected = null
        game = next
        // THE BOARD SAYS WHAT IT HOLDS (iOS aabfac62 chess_board, markChanged): which game, in what state, whether it may send -- once a change
        val said = "game=" + gameId.take(8) + " status=" + (next?.status ?: "none") + " mine=" + (if (next?.whiteIsMine == true) 1 else 0) +
            " waiting=" + (if (next?.waitingForHistory == true) 1 else 0) + " allowed=" + (if (allowed()) 1 else 0) +
            " current=" + (if (current == gameId) 1 else 0) + " pending=" + (if (pending == null) 0 else 1)
        if (said != boardSaid) { boardSaid = said; Log.i("Montana", "chess_board $said") }
        anchor = next?.let { ChessAnchor.of(it, ref) } ?: ChessAnchor.STANDING
        score = ChessScore(games)
        pairOver = games.firstOrNull { it.id == current }?.status?.finished ?: true
        val waiting = pending
        if (waiting != null && (next?.acceptedIDs?.contains(waiting) == true || next?.status?.finished == true)) pending = null
        // THE GAME THAT ENDS ON THE SCREEN LEAVES IT: a moment on the final position and its plate, then the conversation
        if (next != null) {
            if (!next.status.finished) sawLive = true
            else if (sawLive) {
                sawLive = false
                val id = gameId
                main.postDelayed({ if (ChessSend.shownGame == id) { onBack(); if (!inChat) act.push { back -> conversationPage(act, ref, back) } } }, 1200)
            }
        }
        draw()
        }
    }
    drawMark.setOnClickListener { send(ChessLetter.DRAW) }

    val menu = c.barMark(R.drawable.ic_more_horiz, R.string.chess_actions) { }
    menu.setOnClickListener {
        val g = game
        val pm = PopupMenu(c, menu)
        val m = pm.menu
        if (!computer) m.add(0, 9, 0, ChessWords.score(c, score)).isEnabled = false
        m.add(0, 1, 1, R.string.chess_flip)
        m.add(0, 2, 2, R.string.chess_live)
        if (g != null && g.status == ChessGame.Status.PLAYING) {
            if (!computer) {
                m.add(0, 3, 3, R.string.chess_offer_draw).apply { isCheckable = true; isChecked = offerDraw }
                m.add(0, 4, 4, R.string.chess_claim_with_move).apply { isCheckable = true; isChecked = claimWithMove; isEnabled = live() && g.position.turn == g.mySide }
            }
            m.add(0, 5, 5, R.string.chess_claim).isEnabled = live() && g.position.turn == g.mySide && g.canClaimDraw
            if (computer) m.add(0, 10, 5, R.string.chess_take_back).isEnabled = ChessComputer.canTakeBack
            m.add(0, 6, 6, R.string.chess_resign).isEnabled = live()
        }
        if (g != null && !g.status.finished) m.add(0, 7, 7, R.string.chess_end).isEnabled = pending == null && allowed() && !(computer && ChessComputer.thinking)
        if (g != null) m.add(0, 8, 8, R.string.chess_new_game).isEnabled = (computer || pairOver) && pending == null && allowed()
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { flipped = !flipped; draw() }
                2 -> { browsing = null; selected = null; draw() }
                3 -> offerDraw = !offerDraw
                4 -> { claimWithMove = !claimWithMove; draw() }
                5 -> send(ChessLetter.CLAIM)
                6 -> AlertDialog.Builder(act).setTitle(R.string.chess_resign_q).setPositiveButton(R.string.chess_resign) { _, _ -> send(ChessLetter.RESIGN) }.setNegativeButton(R.string.chess_cancel, null).show()
                7 -> AlertDialog.Builder(act).setTitle(R.string.chess_end_q).setPositiveButton(R.string.chess_end) { _, _ -> send(ChessLetter.END) }.setNegativeButton(R.string.chess_cancel, null).show()
                8 -> game?.let { again(it) }
                10 -> { ChessComputer.takeBack(); browsing = null; reload() }
            }
            true
        }
        pm.show()
    }
    board.onSquare = { press(it) }

    val bar = FrameLayout(c).apply {
        setPadding(c.dp(8), 0, c.dp(8), 0)
        addView(c.barMark(R.drawable.ic_arrow_back_ios_new, R.string.coin_close) { onBack() }, FrameLayout.LayoutParams(c.dp(44), c.dp(44), Gravity.CENTER_VERTICAL or Gravity.START))
        addView(c.text(c.getString(R.string.chess_title), 17f, Color.WHITE, bold = true, center = true), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        addView(menu, FrameLayout.LayoutParams(c.dp(44), c.dp(44), Gravity.CENTER_VERTICAL or Gravity.END))
    }
    fun moveList() {
        val g = game ?: return
        val list = c.vstack(Gravity.NO_GRAVITY) { setPadding(dp(16), dp(8), dp(16), dp(8)) }
        lateinit var dialog: AlertDialog
        g.notation.forEachIndexed { i, word ->
            list.addView(c.text((i / 2 + 1).toString() + "." + (if (i % 2 == 1) ".." else "") + " " + word, 16f).apply {
                minHeight = c.dp(44); gravity = Gravity.CENTER_VERTICAL
                pressable { browsing = if (i + 1 == g.moves.size) null else i + 1; selected = null; dialog.dismiss(); draw() }
            }, lp())
        }
        dialog = AlertDialog.Builder(act).setTitle(R.string.chess_moves).setView(ScrollView(c).apply { addView(list) }).setNegativeButton(R.string.coin_close, null).create()
        dialog.show()
    }
    val tools = c.hstack {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(22), dp(8), dp(22), dp(8))
        addView(c.barMark(R.drawable.ic_list_bullet, R.string.chess_moves) { moveList() }, lp(c.dp(44), c.dp(44)))
        spacer()
        if (!computer) {
            addView(c.barMark(R.drawable.ic_bar_chats, R.string.chess_chat) {
                if (inChat) onBack() else act.push { back -> conversationPage(act, ref, back) }
            }, lp(c.dp(44), c.dp(44)))
            spacer()
        }
        addView(c.barMark(R.drawable.ic_arrow_back_ios_new, R.string.chess_previous) { browse(-1) }, lp(c.dp(44), c.dp(44)))
        spacer()
        addView(c.barMark(R.drawable.ic_chevron_right, R.string.chess_next) { browse(1) }, lp(c.dp(44), c.dp(44)))
    }
    val column = c.vstack(Gravity.NO_GRAVITY) {
        addView(stripScroll, lp(MATCH, c.dp(44)))
        addView(topRow, lp())
        addView(FrameLayout(c).apply {
            addView(board, FrameLayout.LayoutParams(MATCH, WRAP))
            addView(plateHolder, FrameLayout.LayoutParams(MATCH, MATCH))
        }, lp())
        addView(bottomRow, lp())
        addView(c.hstack {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = c.dp(44); setPadding(c.dp(16), 0, c.dp(16), 0)
            addView(status, lp(0, WRAP, 1f)); addView(drawMark, lp(c.dp(44), c.dp(44)))
        }, lp())
        addView(notes, lp())
        addView(delivery, lp())
        addView(tools, lp())
    }
    val page = FrameLayout(c).apply {
        setBackgroundColor(Color.BLACK)
        addView(c.chatGround(ChatWall.PAGE), FrameLayout.LayoutParams(MATCH, MATCH))
        addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(bar, lp(MATCH, c.dp(52)))
            addView(ScrollView(c).apply { addView(column) }, lp(MATCH, 0, 1f))
        }, FrameLayout.LayoutParams(MATCH, MATCH))
    }
    val heard: () -> Unit = { reload() }
    page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        // THE BOARD HOLDS ITS OWN LANE (iOS startBoardPolling/stopBoardPolling, 29.09): the chat's farewell under the pushed board took
        // the hold with it, and the correspondent's move rode the box; while the board stands its moves ride the lane both ways
        override fun onViewAttachedToWindow(v: View) {
            if (computer) { ChessComputer.load(); ChessComputer.listen(heard); reload(); return }
            ChessSend.shownGame = gameId; Book.listen(heard); reload(); main.post(ticker); Signal.hold(ref, "board")
        }
        override fun onViewDetachedFromWindow(v: View) {
            if (computer) { ChessComputer.unlisten(heard); return }
            if (ChessSend.shownGame == gameId) ChessSend.shownGame = null; Book.unlisten(heard); main.removeCallbacks(ticker); Signal.release(ref, "board")
        }
    })
    return page
}

/** A page of chess: the bar with its cross or chevron and a trailing mark, the list that scrolls. */
private fun chessFrame(act: MainActivity, title: String, cross: Boolean, onLead: () -> Unit, trailing: View?, body: LinearLayout): View {
    val c: Context = act
    val bar = FrameLayout(c).apply {
        setPadding(c.dp(8), 0, c.dp(8), 0)
        addView(c.barMark(if (cross) R.drawable.ic_close else R.drawable.ic_arrow_back_ios_new, R.string.coin_close) { onLead() },
            FrameLayout.LayoutParams(c.dp(44), c.dp(44), Gravity.CENTER_VERTICAL or Gravity.START))
        addView(c.text(title, 17f, Color.WHITE, bold = true, center = true), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        if (trailing != null) addView(trailing, FrameLayout.LayoutParams(c.dp(44), c.dp(44), Gravity.CENTER_VERTICAL or Gravity.END))
    }
    return FrameLayout(c).apply {
        setBackgroundColor(Color.BLACK)
        addView(c.chatGround(ChatWall.PAGE), FrameLayout.LayoutParams(MATCH, MATCH))
        addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(bar, lp(MATCH, c.dp(52)))
            addView(ScrollView(c).apply { addView(body.apply { setPadding(c.dp(16), 0, c.dp(16), c.dp(32)) }) }, lp(MATCH, 0, 1f))
        }, FrameLayout.LayoutParams(MATCH, MATCH))
    }
}

/**
 * THE CHESS PAGE (iOS MTChessPage): every pair's games, the unfinished first and then the newest, each with the correspondent's
 * face, the game's state and its clock; the plus begins a new game.
 */
fun chessLobbyPage(act: MainActivity, onClose: () -> Unit): View {
    val c: Context = act
    val body = c.vstack(Gravity.NO_GRAVITY)
    val list = c.vstack(Gravity.NO_GRAVITY)
    fun draw() {
        list.removeAllViews()
        class Record(val ref: String, val game: ChessGame)
        val records = ArrayList<Record>()
        for (chat in Book.all()) {
            if (chat.msgs.none { it.text.startsWith(ChessLetter.MARK) }) continue
            val all = ChessSend.entries(chat.ref)
            val current = ChessGame.current(all)
            for ((id, l) in all.groupBy { it.letter.game }) ChessGame.of(l, id)?.replaced(current)?.let { records.add(Record(chat.ref, it)) }
        }
        records.sortWith(compareBy<Record>({ it.game.status.finished }, { -it.game.last.at }, { it.ref + ":" + it.game.id }))
        val training = ChessComputer.game
        if (records.isEmpty() && training == null) {
            list.addView(c.vstack {
                setPadding(0, dp(48), 0, dp(24))
                addView(ImageView(c).apply { setImageResource(R.drawable.chess_wn) }, lp(dp(72), dp(72)))
                gap(10)
                addView(c.text(c.getString(R.string.chess_title), 22f, Color.WHITE, bold = true, center = true))
                addView(c.text(c.getString(R.string.chess_empty_note), 15f, MT.gray, center = true))
            }, lp())
        }
        // A ROW OF THE PAGE (iOS MTChessPage: the face, the title, the state under it, a trailing word)
        fun LinearLayout.row(face: View, title: String, note: String, trailing: String?, open: () -> Unit) = addView(c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(face, lp(dp(44), dp(44)))
            gap(12)
            addView(c.vstack(Gravity.NO_GRAVITY) {
                addView(c.text(title, 17f, Color.WHITE, bold = true).apply { singleLineEllipsis() })   // USER-DATA: the name
                addView(c.text(note, 15f, MT.gray).apply { setPadding(0, dp(5), 0, 0) })
            }, lp(0, WRAP, 1f))
            if (trailing != null) addView(c.text(trailing, 15f, Color.WHITE))
            pressable(open)
        }, lp())
        list.addView(c.plate {
            // THE TRAINING GAME (iOS 4832070d): the computer at its level, the game's state
            if (training != null) {
                row(c.computerFace(44), ChessWords.computer(c, ChessComputer.level), ChessWords.status(c, training, true), null) {
                    act.push { back -> chessBoardPage(act, "", training.id, false, back) }
                }
                divider()
            }
            // THE TIMER (iOS MTChessTimer, the author's word 04.10 00:10): chess at one board, on one phone
            row(c.icon(R.drawable.ic_timer, Color.WHITE, 28).apply { setPadding(dp(8), dp(8), dp(8), dp(8)) },
                c.getString(R.string.chess_timer), c.getString(R.string.chess_timer_note), null) { act.push { back -> chessTimerPage(act, back) } }
            records.forEach { r ->
                divider()
                val chat = Book.chat(r.ref)
                val name = chat?.shown?.ifBlank { null } ?: c.getString(R.string.peer)
                addView(c.hstack {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    addView(c.peerFace(r.ref, name, 44), lp(dp(44), dp(44)))
                    gap(12)
                    addView(c.vstack(Gravity.NO_GRAVITY) {
                        addView(c.text(name, 17f, Color.WHITE, bold = true).apply { singleLineEllipsis() })   // USER-DATA: the name
                        addView(c.text(ChessWords.status(c, r.game), 15f, MT.gray))
                    }, lp(0, WRAP, 1f))
                    addView(c.text(ChessWords.control(c, r.game.seconds), 15f, Color.WHITE))
                    pressable { act.push { back -> chessBoardPage(act, r.ref, r.game.id, false, back) } }
                }, lp())
            }
        }, lp().apply { topMargin = c.dp(12) })
    }
    body.addView(list, lp())
    val plus = c.barMark(R.drawable.ic_plus, R.string.chess_new_game) { act.push { back -> newChessPage(act, back) } }
    val page = chessFrame(act, c.getString(R.string.chess_title), true, onClose, plus, body)
    val heard: () -> Unit = { draw() }
    page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { ChessComputer.load(); ChessComputer.listen(heard); Book.listen(heard); draw() }
        override fun onViewDetachedFromWindow(v: View) { ChessComputer.unlisten(heard); Book.unlisten(heard) }
    })
    return page
}

/**
 * THE TIMER (iOS MTChessTimer; the author's word 03.10.2026 21:10 UTC: «a game Timer for offline chess: on one phone switch whose
 * move it is»): a chess clock for two at one board. Each half is one player's -- the upper one turned to the player across the table
 * -- and a tap on one's own half ends one's move; the side to move thinks and its clock runs. It mints nothing (iOS 480cd898).
 */
fun chessTimerPage(act: MainActivity, onBack: () -> Unit): View {
    val c: Context = act
    val main = Handler(Looper.getMainLooper())
    var turn: Int? = null            // 0 the upper half, 1 the lower
    val used = longArrayOf(0L, 0L)
    val clocks = arrayOfNulls<TextView>(2)
    val words = arrayOfNulls<TextView>(2)
    val halves = arrayOfNulls<LinearLayout>(2)
    fun draw() {
        for (side in 0..1) {
            val moving = turn == side
            clocks[side]?.text = ChessWords.clock(used[side])   // USER-DATA: the time this side has thought
            words[side]?.setText(if (turn == null) R.string.chess_tap_start else if (moving) R.string.chess_your_move else R.string.chess_their_move)
            halves[side]?.background = if (moving) android.graphics.drawable.ColorDrawable(Color.argb(46, Color.red(MT.blue), Color.green(MT.blue), Color.blue(MT.blue))) else null
            halves[side]?.isEnabled = turn == null || moving
        }
    }
    val second = object : Runnable {
        override fun run() { val s = turn ?: return; used[s] += 1000; draw(); main.postDelayed(this, 1000) }
    }
    fun pass(side: Int) {
        if (turn != null && turn != side) return
        turn = 1 - side
        main.removeCallbacks(second); main.postDelayed(second, 1000)
        draw()
    }
    fun half(side: Int) = c.vstack {
        gravity = Gravity.CENTER
        addView(c.text("", 64f, Color.WHITE, bold = true, center = true).apply {
            fontFeatureSettings = "tnum"; clocks[side] = this
        }, lp(WRAP, WRAP))
        gap(12)
        addView(c.text("", 17f, MT.gray, bold = true, center = true).also { words[side] = it }, lp(WRAP, WRAP))
        if (side == 0) rotation = 180f
        isClickable = true; setOnClickListener { pass(side) }
        halves[side] = this
    }
    val body = c.vstack(Gravity.NO_GRAVITY) {
        addView(half(0), lp(MATCH, 0, 1f))
        addView(View(c).apply { setBackgroundColor(Color.argb(60, 255, 255, 255)) }, lp(MATCH, 1))
        addView(half(1), lp(MATCH, 0, 1f))
    }
    val bar = FrameLayout(c).apply {
        setPadding(c.dp(8), 0, c.dp(8), 0)
        addView(c.barMark(R.drawable.ic_arrow_back_ios_new, R.string.coin_close) { onBack() }, FrameLayout.LayoutParams(c.dp(44), c.dp(44), Gravity.CENTER_VERTICAL or Gravity.START))
        addView(c.text(c.getString(R.string.chess_timer), 17f, Color.WHITE, bold = true, center = true), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    }
    val page = FrameLayout(c).apply {
        setBackgroundColor(Color.BLACK)
        addView(c.chatGround(ChatWall.PAGE), FrameLayout.LayoutParams(MATCH, MATCH))
        addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(bar, lp(MATCH, c.dp(52)))
            addView(body, lp(MATCH, 0, 1f))
        }, FrameLayout.LayoutParams(MATCH, MATCH))
    }
    draw()
    page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { if (turn != null) { main.removeCallbacks(second); main.postDelayed(second, 1000) } }
        override fun onViewDetachedFromWindow(v: View) { main.removeCallbacks(second) }
    })
    return page
}

/**
 * A NEW GAME (iOS MTChessNewGame): the time control, then the correspondents, each invited by their row — no stake and no pot
 * (iOS 480cd898) — and our send; the invitation opens its board.
 */
fun newChessPage(act: MainActivity, onBack: () -> Unit): View {
    val c: Context = act
    val body = c.vstack(Gravity.NO_GRAVITY)
    // THE COMPUTER (iOS MTChessNewGame Section «Computer», 4832070d): its level on the platform's slider, the colour by its piece
    var level = ChessComputer.chosenLevel
    var white = true
    val levelWord = c.text(c.getString(R.string.chess_level, level), 15f, Color.WHITE).apply { fontFeatureSettings = "tnum" }
    val slider = android.widget.SeekBar(c).apply {
        max = ChessEngine.LEVELS.last - ChessEngine.LEVELS.first; progress = level - ChessEngine.LEVELS.first
        contentDescription = c.getString(R.string.chess_level_word)
        setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar, p: Int, user: Boolean) {
                level = ChessEngine.LEVELS.first + p; ChessComputer.chosenLevel = level
                levelWord.text = c.getString(R.string.chess_level, level)
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar) {}
        })
    }
    val colours = c.hstack { gravity = Gravity.CENTER_VERTICAL; background = c.rounded(Color.rgb(44, 44, 46), 9); setPadding(dp(2), dp(2), dp(2), dp(2)) }
    fun drawColours() {
        colours.removeAllViews()
        for (w in listOf(true, false)) colours.addView(FrameLayout(c).apply {
            background = if (w == white) c.rounded(Color.rgb(99, 99, 102), 7) else null
            contentDescription = c.getString(if (w) R.string.chess_white_pieces else R.string.chess_black_pieces)
            isSelected = w == white
            addView(ImageView(c).apply { setImageResource(ChessPiece(if (w) ChessSide.WHITE else ChessSide.BLACK, ChessKind.KING).art) },
                FrameLayout.LayoutParams(c.dp(30), c.dp(30), Gravity.CENTER))
            pressable { white = w; drawColours() }
        }, lp(0, c.dp(40), 1f))
    }
    drawColours()
    body.addView(c.text(c.getString(R.string.chess_computer), 13f, MT.gray).apply { setPadding(dp(16), dp(22), dp(16), dp(6)) }, lp())
    body.addView(c.plate {
        addView(c.vstack(Gravity.NO_GRAVITY) {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(levelWord, lp(WRAP, WRAP))
            addView(c.hstack {
                gravity = Gravity.CENTER_VERTICAL
                addView(c.text(ChessEngine.LEVELS.first.toString(), 13f, MT.gray), lp(WRAP, WRAP))
                addView(slider, lp(0, dp(44), 1f))
                addView(c.text(ChessEngine.LEVELS.last.toString(), 13f, MT.gray), lp(WRAP, WRAP))
            }, lp())
        }, lp())
        divider()
        addView(c.vstack(Gravity.NO_GRAVITY) {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(c.text(c.getString(R.string.chess_your_pieces), 13f, MT.gray), lp(WRAP, WRAP))
            addView(colours, lp().apply { topMargin = dp(6) })
        }, lp())
        divider()
        addView(c.hstack {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(44)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(c.computerFace(44), lp(dp(44), dp(44)))
            gap(12)
            addView(c.text(c.getString(R.string.chess_play_computer), 16f, MT.blue), lp(0, WRAP, 1f))
            addView(c.icon(R.drawable.ic_play_fill, MT.blue, 20), lp(dp(20), dp(20)))
            pressable {
                ChessComputer.start(level, white)
                val id = ChessComputer.game?.id ?: return@pressable
                onBack()
                act.push { back -> chessBoardPage(act, "", id, false, back) }
            }
        }, lp())
    }, lp())
    var seconds = ChessSend.CHAT_CLOCK
    val controls = c.hstack { gravity = Gravity.CENTER_VERTICAL }
    fun drawControls() {
        controls.removeAllViews()
        for (s in ChessLetter.TIME_CONTROLS) controls.addView(c.text(ChessWords.control(c, s), 15f, Color.WHITE, bold = true).apply {
            background = c.rounded(if (s == seconds) MT.blue else Color.rgb(58, 58, 60), 16)
            setPadding(dp(12), dp(8), dp(12), dp(8)); minHeight = dp(44); gravity = Gravity.CENTER
            pressable { seconds = s; drawControls() }
        }, lp(WRAP, WRAP).apply { marginEnd = c.dp(6) })
    }
    drawControls()
    body.addView(c.text(c.getString(R.string.chess_time_control), 13f, MT.gray).apply { setPadding(dp(16), dp(22), dp(16), dp(6)) }, lp())
    body.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(controls) }, lp())
    val people = Book.all().filter { ChessSend.canPlay(it.ref) }.sortedBy { it.shown.lowercase() }
    body.addView(c.text(c.getString(R.string.chess_invite_correspondent), 13f, MT.gray).apply { setPadding(dp(16), dp(22), dp(16), dp(6)) }, lp())
    body.addView(c.plate {
        if (people.isEmpty()) addView(c.text(c.getString(R.string.chess_no_correspondents), 16f, MT.gray).apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }, lp())
        people.forEachIndexed { i, ch ->
            if (0 < i) divider()
            fun offer() {
                val id = ChessSend.invite(c, ch.ref, seconds)
                if (id == null) { AlertDialog.Builder(act).setTitle(R.string.chess_invite_failed).setPositiveButton(R.string.ok, null).show(); return }
                onBack()
                act.push { back -> chessBoardPage(act, ch.ref, id, false, back) }
            }
            addView(c.hstack {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(8), dp(8))
                addView(c.hstack {
                    gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(44)
                    addView(c.peerFace(ch.ref, ch.shown, 44), lp(dp(44), dp(44)))
                    gap(12)
                    addView(c.text(ch.shown.ifBlank { c.getString(R.string.peer) }, 16f).apply { singleLineEllipsis() }, lp(0, WRAP, 1f))   // USER-DATA: the name
                    pressable { offer() }
                }, lp(0, WRAP, 1f))
                addView(c.barMark(R.drawable.ic_paperplane, R.string.coin_send, MT.blue) { offer() }, lp(dp(44), dp(44)))
            }, lp())
        }
    }, lp())
    return chessFrame(act, c.getString(R.string.chess_new_game), false, onBack, null, body)
}

/**
 * THE INVITATION'S BUBBLE (iOS the chess letter in the chat): the knight, the word, who plays which colour under which clock; an
 * invitation that waits for my answer wears the system's blue Accept and red Decline; a tap opens the board.
 */
fun Context.chessPlate(act: MainActivity?, ref: String, m: Msg, letter: ChessLetter, mine: Boolean): View = vstack(Gravity.NO_GRAVITY) {
    addView(hstack {
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(context).apply { setImageResource(R.drawable.chess_wn) }, lp(dp(40), dp(40)))
        gap(10)
        addView(vstack(Gravity.NO_GRAVITY) {
            addView(text(getString(R.string.chess_title), 17f, BubbleStyle.text(mine), bold = true))   // iOS .headline
            addView(text(ChessWords.seat(context, letter, mine), 15f, BubbleStyle.text(mine)).apply { setPadding(0, dp(3), 0, 0) })   // iOS .subheadline in the bubble's ink, 3 under the title
        }, lp(WRAP, WRAP))
    }, lp(WRAP, WRAP))
    // THE ANSWER STANDS ON THE INVITATION (iOS MontanaBubble.swift 754-758, MTChessScreen status 1187): both phones read one replay, so both
    // bubbles say it; none while the invitation waits. Six under the head, as the bubble's spacing
    ChessSend.state(ref, letter.game)?.takeIf { it.status != ChessGame.Status.INVITED }?.let { g ->
        addView(text(ChessWords.status(context, g), 15f, BubbleStyle.text(mine), bold = true).apply { setPadding(0, dp(6), 0, 0) }, lp(WRAP, WRAP))
    }
    if (act != null) {
        val game = letter.game
        // THE TWO BUTTONS SHARE THE BUBBLE'S WHOLE WIDTH (iOS maxWidth .infinity, one line each, 8 between)
        if (!mine && ChessSend.awaitsMe(ref, game)) addView(hstack {
            setPadding(0, dp(6), 0, 0)
            addView(text(getString(R.string.chess_accept), 15f, Color.WHITE, bold = true, center = true).apply {
                background = rounded(MT.blue, 18); setPadding(dp(8), dp(8), dp(8), dp(8)); minHeight = dp(44); gravity = Gravity.CENTER; singleLineEllipsis()
                pressable { if (ChessSend.join(act, ref, game) != null) act.push { back -> chessBoardPage(act, ref, game, true, back) } }
            }, lp(0, WRAP, 1f).apply { marginEnd = dp(8) })
            addView(text(getString(R.string.chess_decline), 15f, Color.WHITE, bold = true, center = true).apply {
                background = rounded(Color.rgb(128, 0, 33), 18); setPadding(dp(8), dp(8), dp(8), dp(8)); minHeight = dp(44); gravity = Gravity.CENTER; singleLineEllipsis()
                pressable {
                    val now = ChessSend.state(ref, game)
                    now?.action(ChessLetter.DECLINE, ChessSend.now())?.let { ChessSend.send(act, ref, it) }
                }
            }, lp(0, WRAP, 1f))
        }, lp(bubbleCeiling(context, ref) - dp(24), WRAP))
        pressable { act.push { back -> chessBoardPage(act, ref, game, true, back) } }
    }
}
