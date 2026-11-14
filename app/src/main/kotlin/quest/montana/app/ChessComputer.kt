package quest.montana.app

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * THE TRAINING GAME (iOS MTChessComputer, 4832070d; the author's word 29.09: «so that I can also play the computer, without people,
 * and train; the level is set by a slider before the game»): one game with this phone's own engine (ChessEngine), kept in the very
 * letters a correspondent game is made of, so the one replay (ChessGame) judges every move and the end. Nothing of it leaves the
 * phone; the person's own letters are the game's content, the level they last chose is their setting -- both ride the copy under
 * the iPhone's own names (iOS MTChessAppDoor.dataKeys, settingKeys).
 */
object ChessComputer {
    const val GAME_KEY = "chess.computer.game"     // NOT-UI: iOS MTChessComputer.gameKey
    const val LEVEL_KEY = "chess.computer.level"   // NOT-UI: iOS MTChessComputer.levelKey
    var game: ChessGame? = null; private set
    var level = 3; private set
    var thinking = false; private set
    private var entries: List<ChessEntry> = emptyList()
    private var answering = 0   // the answer's generation: a take back or a new game leaves an older answer unlanded
    private var loaded = false
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { for (l in listeners.toList()) l() }
    private fun now(): Long = System.currentTimeMillis()

    /** The kept game, read once (iOS init): its letters and whose each was, judged anew by the replay. */
    fun load() {
        if (loaded) return
        loaded = true
        val kept = runCatching { JSONObject(Prefs.str(GAME_KEY, "")) }.getOrNull() ?: return
        val letters = kept.optJSONArray("letters") ?: return
        val mine = kept.optJSONArray("mine") ?: return
        if (letters.length() != mine.length() || letters.length() == 0) return
        val read = (0 until letters.length()).map { i -> letters.optJSONObject(i)?.let { ChessLetter.of(it) } ?: return }
        level = kept.optInt("level", 3).coerceIn(ChessEngine.LEVELS.first, ChessEngine.LEVELS.last)
        entries = read.mapIndexed { i, l -> ChessEntry(l, mine.optBoolean(i)) }
        game = ChessGame.of(entries, read[0].game)
        answerIfDue()
    }
    /** The level the person last chose, the new-game page's slider (iOS @AppStorage levelKey, 3 at first). */
    var chosenLevel: Int
        get() = Prefs.int(LEVEL_KEY, 3).coerceIn(ChessEngine.LEVELS.first, ChessEngine.LEVELS.last)
        set(v) { Prefs.setInt(LEVEL_KEY, v.coerceIn(ChessEngine.LEVELS.first, ChessEngine.LEVELS.last)) }

    /** A new game at the chosen level; the one who invites plays white, so the person invites when they take white. */
    fun start(level: Int, white: Boolean) {
        load()
        answering++; thinking = false
        val id = UUID.randomUUID().toString().lowercase(); val at = now()
        val invitation = ChessLetter(id, id, "", ChessLetter.INVITE, at, seconds = 0)
        val acceptance = ChessLetter(id, UUID.randomUUID().toString().lowercase(), id, ChessLetter.ACCEPT, at)
        this.level = level.coerceIn(ChessEngine.LEVELS.first, ChessEngine.LEVELS.last)
        entries = listOf(ChessEntry(invitation, white), ChessEntry(acceptance, !white))
        game = ChessGame.of(entries, id)
        save(); changed()
        answerIfDue()
    }
    /** The person's own action, judged by the replay; the engine answers when the turn becomes its. */
    fun play(kind: String, move: String? = null): Boolean {
        val g = game ?: return false
        if (thinking) return false
        val event = g.action(kind, now(), move) ?: return false
        commit(ChessEntry(event, true))
        return true
    }
    /** A training game takes a move back: the person's last move and the engine's answer to it. */
    val canTakeBack: Boolean get() = !thinking && entries.any { it.mine && it.letter.kind == ChessLetter.MOVE }
    fun takeBack() {
        val cut = entries.indexOfLast { it.mine && it.letter.kind == ChessLetter.MOVE }
        val first = entries.firstOrNull() ?: return
        if (cut < 0) return
        answering++; thinking = false
        entries = entries.take(cut)
        game = ChessGame.of(entries, first.letter.game)
        save(); changed()
    }

    private fun commit(entry: ChessEntry) {
        entries = entries + entry
        game = ChessGame.of(entries, entry.letter.game)
        save(); changed()
        answerIfDue()
    }
    private fun answerIfDue() {
        val g = game ?: return
        if (thinking || g.status != ChessGame.Status.PLAYING || g.position.turn == g.mySide) return
        thinking = true
        val generation = ++answering
        val position = g.position; val lvl = level; val last = g.last.id
        Thread {
            val began = System.nanoTime()
            val move = runCatching { ChessEngine.reply(position, lvl) }.getOrNull()
            // An answer never lands in the same frame as the person's move: the board shows their move first.
            val rest = 350L - (System.nanoTime() - began) / 1_000_000
            main.postDelayed({
                if (generation != answering) return@postDelayed
                thinking = false
                val now = game
                val event = if (move != null && now != null && now.last.id == last) now.action(ChessLetter.MOVE, now(), move.uci, mine = false) else null
                if (event == null) { changed(); return@postDelayed }
                commit(ChessEntry(event, false))
            }, maxOf(0L, rest))
        }.apply { name = "chess-engine"; priority = Thread.NORM_PRIORITY }.start()
        changed()
    }
    private fun save() {
        val kept = JSONObject().put("level", level)
            .put("letters", JSONArray().apply { entries.forEach { put(it.letter.json()) } })
            .put("mine", JSONArray().apply { entries.forEach { put(it.mine) } })
        Prefs.setStr(GAME_KEY, kept.toString())
    }
    /** The kept game as the copy carries it (iOS SeedScope.dataKeys «chess.computer.game»): the iPhone's own JSON, or null. */
    fun carried(): String? = Prefs.str(GAME_KEY, "").takeIf { it.isNotEmpty() }
    /** A copy's game laid here (iOS layCard: a data key is the copy's): read anew at the next look. */
    fun lay(json: String) {
        if (runCatching { JSONObject(json) }.isFailure) return
        answering++; thinking = false
        Prefs.setStr(GAME_KEY, json); loaded = false; game = null; entries = emptyList()
        main.post { load(); changed() }
    }
}
