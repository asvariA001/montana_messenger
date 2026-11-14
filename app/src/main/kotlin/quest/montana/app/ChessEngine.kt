package quest.montana.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * THE PHONE'S OWN OPPONENT (iOS MTChessEngine, 4832070d; the author's word 29.09: «so that I can also play the computer, without
 * people, and train; the level is set by a slider before the game»). A search on this phone over its own compact board -- material
 * and the widely published placement tables, negamax with alpha-beta, captures followed until the position is quiet -- whose depth,
 * time and bounded error are set by the level: a weak level plays plausible moves, a strong one its best. The rules owner
 * (ChessPosition) names the legal moves at the root -- castling, en passant and every promotion -- and judges the move the game
 * receives; below the root the search walks the same rules in a form fast enough to walk (castling and en passant stand at the root
 * only, a promotion below it is to a queen). Nothing leaves the phone.
 */
object ChessEngine {
    val LEVELS = 1..10

    class Plan(val depth: Int, val quiet: Boolean, val error: Int, val budget: Double)
    fun plan(level: Int): Plan = when (min(max(level, LEVELS.first), LEVELS.last)) {
        1 -> Plan(1, false, 400, 0.3)
        2 -> Plan(1, true, 250, 0.4)
        3 -> Plan(2, true, 150, 0.6)
        4 -> Plan(2, true, 80, 0.8)
        5 -> Plan(3, true, 45, 1.0)
        6 -> Plan(3, true, 20, 1.2)
        7 -> Plan(4, true, 8, 1.6)
        8 -> Plan(4, true, 0, 2.0)
        9 -> Plan(5, true, 0, 3.0)
        else -> Plan(6, true, 0, 4.0)
    }

    /** The engine's move in this position, or none when there is no legal move. */
    fun reply(position: ChessPosition, level: Int): ChessMove? {
        val legal = position.legalMoves()
        val only = legal.firstOrNull() ?: return null
        if (legal.size == 1) return only
        val plan = plan(level)
        val root = Board.of(position)
        val children = legal.map { root.child(it) }
        val search = Search(plan.quiet, System.nanoTime() + (plan.budget * 1_000_000_000).toLong())
        var order = legal.indices.toList()
        var scores = IntArray(legal.size)
        for (depth in 1..plan.depth) {
            search.timed = depth != 1   // the first depth always completes: there is always a move to give
            val round = IntArray(legal.size) { -Search.INFINITY }
            var best = -Search.INFINITY
            for (i in order) {
                val board = children[i].copy()
                // A move worse than the best by more than the level's error is never chosen: its exact score is not needed.
                val floor = if (best == -Search.INFINITY) -Search.INFINITY else best - plan.error - 1
                val score = -search.negamax(board, depth - 1, -Search.INFINITY, -floor, 1)
                if (search.aborted) break
                round[i] = score
                best = max(best, score)
            }
            if (search.aborted) break
            scores = round
            val s = scores
            order = order.sortedByDescending { s[it] }
            if (best >= Search.MATE - 64) break   // a mate is found: a deeper search cannot improve on it
        }
        // The level's error: a move within it of the best may be taken instead, never a worse one.
        var chosen = order[0]; var top = Int.MIN_VALUE
        for (i in order) {
            val noisy = scores[i] + (if (plan.error > 0) (0..plan.error).random() else 0)
            if (noisy > top) { top = noisy; chosen = i }
        }
        return legal[chosen]
    }

    /**
     * The compact board the search walks: a signed code per square (1 pawn, 2 knight, 3 bishop, 4 rook, 5 queen, 6 king; white
     * positive, black negative), square 0 = a1.
     */
    class Board(val cells: IntArray, var side: Int, var whiteKing: Int, var blackKing: Int) {
        class Step(val from: Int, val to: Int, val promote: Int, val order: Int)

        fun copy() = Board(cells.copyOf(), side, whiteKing, blackKing)

        companion object {
            fun of(position: ChessPosition): Board {
                val cells = IntArray(64)
                for (sq in 0 until 64) position.piece(sq)?.let { cells[sq] = code(it.kind) * (if (it.side == ChessSide.WHITE) 1 else -1) }
                return Board(cells, if (position.turn == ChessSide.WHITE) 1 else -1,
                    cells.indexOf(6).takeIf { it >= 0 } ?: 4, cells.indexOf(-6).takeIf { it >= 0 } ?: 60)
            }
            fun code(kind: ChessKind): Int = when (kind) {
                ChessKind.PAWN -> 1; ChessKind.KNIGHT -> 2; ChessKind.BISHOP -> 3; ChessKind.ROOK -> 4; ChessKind.QUEEN -> 5; ChessKind.KING -> 6
            }
            val KNIGHT_JUMPS = arrayOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)
            val KING_STEPS = arrayOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
            val STRAIGHT = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
            val DIAGONAL = arrayOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
            val QUEEN_RAYS = STRAIGHT + DIAGONAL
            val SLIDERS = arrayOf(STRAIGHT to 4, DIAGONAL to 3)
            val WORTH = intArrayOf(0, 100, 320, 330, 500, 900, 20000)
            private fun on(n: Int) = n in 0 until 8

            val PAWN = intArrayOf(
                  0,   0,   0,   0,   0,   0,   0,   0,
                 50,  50,  50,  50,  50,  50,  50,  50,
                 10,  10,  20,  30,  30,  20,  10,  10,
                  5,   5,  10,  25,  25,  10,   5,   5,
                  0,   0,   0,  20,  20,   0,   0,   0,
                  5,  -5, -10,   0,   0, -10,  -5,   5,
                  5,  10,  10, -20, -20,  10,  10,   5,
                  0,   0,   0,   0,   0,   0,   0,   0)
            val KNIGHT = intArrayOf(
                -50, -40, -30, -30, -30, -30, -40, -50,
                -40, -20,   0,   0,   0,   0, -20, -40,
                -30,   0,  10,  15,  15,  10,   0, -30,
                -30,   5,  15,  20,  20,  15,   5, -30,
                -30,   0,  15,  20,  20,  15,   0, -30,
                -30,   5,  10,  15,  15,  10,   5, -30,
                -40, -20,   0,   5,   5,   0, -20, -40,
                -50, -40, -30, -30, -30, -30, -40, -50)
            val BISHOP = intArrayOf(
                -20, -10, -10, -10, -10, -10, -10, -20,
                -10,   0,   0,   0,   0,   0,   0, -10,
                -10,   0,   5,  10,  10,   5,   0, -10,
                -10,   5,   5,  10,  10,   5,   5, -10,
                -10,   0,  10,  10,  10,  10,   0, -10,
                -10,  10,  10,  10,  10,  10,  10, -10,
                -10,   5,   0,   0,   0,   0,   5, -10,
                -20, -10, -10, -10, -10, -10, -10, -20)
            val ROOK = intArrayOf(
                  0,   0,   0,   0,   0,   0,   0,   0,
                  5,  10,  10,  10,  10,  10,  10,   5,
                 -5,   0,   0,   0,   0,   0,   0,  -5,
                 -5,   0,   0,   0,   0,   0,   0,  -5,
                 -5,   0,   0,   0,   0,   0,   0,  -5,
                 -5,   0,   0,   0,   0,   0,   0,  -5,
                 -5,   0,   0,   0,   0,   0,   0,  -5,
                  0,   0,   0,   5,   5,   0,   0,   0)
            val QUEEN = intArrayOf(
                -20, -10, -10,  -5,  -5, -10, -10, -20,
                -10,   0,   0,   0,   0,   0,   0, -10,
                -10,   0,   5,   5,   5,   5,   0, -10,
                 -5,   0,   5,   5,   5,   5,   0,  -5,
                  0,   0,   5,   5,   5,   5,   0,  -5,
                -10,   5,   5,   5,   5,   5,   0, -10,
                -10,   0,   5,   0,   0,   0,   0, -10,
                -20, -10, -10,  -5,  -5, -10, -10, -20)
            val KING_EARLY = intArrayOf(
                -30, -40, -40, -50, -50, -40, -40, -30,
                -30, -40, -40, -50, -50, -40, -40, -30,
                -30, -40, -40, -50, -50, -40, -40, -30,
                -30, -40, -40, -50, -50, -40, -40, -30,
                -20, -30, -30, -40, -40, -30, -30, -20,
                -10, -20, -20, -20, -20, -20, -20, -10,
                 20,  20,   0,   0,   0,   0,  20,  20,
                 20,  30,  10,   0,   0,  10,  30,  20)
            val KING_LATE = intArrayOf(
                -50, -40, -30, -20, -20, -30, -40, -50,
                -30, -20, -10,   0,   0, -10, -20, -30,
                -30, -10,  20,  30,  30,  20, -10, -30,
                -30, -10,  30,  40,  40,  30, -10, -30,
                -30, -10,  30,  40,  40,  30, -10, -30,
                -30, -10,  20,  30,  30,  20, -10, -30,
                -30, -30,   0,   0,   0,   0, -30, -30,
                -50, -30, -30, -30, -30, -30, -30, -50)
            val PLACEMENT = arrayOf(IntArray(64), PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING_EARLY)
        }

        /** A root move on a copy, exactly as the rules owner moves: castling carries its rook, en passant takes the pawn beside. */
        fun child(move: ChessMove): Board {
            val next = copy()
            val piece = cells[move.from]; val kind = abs(piece)
            if (kind == 1 && move.from % 8 != move.to % 8 && cells[move.to] == 0) next.cells[move.to - 8 * side] = 0
            if (kind == 6 && abs(move.to - move.from) == 2) {
                val rook = if (move.to > move.from) move.from + 3 else move.from - 4
                next.cells[(move.from + move.to) / 2] = next.cells[rook]
                next.cells[rook] = 0
            }
            next.cells[move.to] = move.promotion?.let { code(it) * side } ?: piece
            next.cells[move.from] = 0
            if (piece == 6) next.whiteKing = move.to else if (piece == -6) next.blackKing = move.to
            next.side = -side
            return next
        }

        fun make(step: Step): Int {
            val piece = cells[step.from]; val taken = cells[step.to]
            cells[step.to] = if (step.promote == 0) piece else step.promote * side
            cells[step.from] = 0
            if (piece == 6) whiteKing = step.to else if (piece == -6) blackKing = step.to
            side = -side
            return taken
        }
        fun unmake(step: Step, taken: Int) {
            side = -side
            val piece = if (step.promote == 0) cells[step.to] else side
            cells[step.from] = piece
            cells[step.to] = taken
            if (piece == 6) whiteKing = step.from else if (piece == -6) blackKing = step.from
        }

        /** The side to move is in check. */
        val inCheck: Boolean get() = attacked(if (side == 1) whiteKing else blackKing, -side)
        /** After a move: the side that made it left its own king attacked, and the move was not legal. */
        val moverExposed: Boolean get() = attacked(if (side == 1) blackKing else whiteKing, side)

        fun attacked(square: Int, attacker: Int): Boolean {
            val file = square % 8; val rank = square / 8
            val behind = rank - attacker   // an attacking pawn stands one rank behind, the way it moves
            if (on(behind)) {
                if (file > 0 && cells[behind * 8 + file - 1] == attacker) return true
                if (file < 7 && cells[behind * 8 + file + 1] == attacker) return true
            }
            for ((df, dr) in KNIGHT_JUMPS) {
                val f = file + df; val r = rank + dr
                if (on(f) && on(r) && cells[r * 8 + f] == 2 * attacker) return true
            }
            for ((df, dr) in KING_STEPS) {
                val f = file + df; val r = rank + dr
                if (on(f) && on(r) && cells[r * 8 + f] == 6 * attacker) return true
            }
            for ((rays, slider) in SLIDERS) for ((df, dr) in rays) {
                var f = file + df; var r = rank + dr
                while (on(f) && on(r)) {
                    val piece = cells[r * 8 + f]
                    if (piece != 0) { if (piece == slider * attacker || piece == 5 * attacker) return true; break }
                    f += df; r += dr
                }
            }
            return false
        }

        /** The moves of the side to move, own king's safety not yet judged, the likeliest good ones first. */
        fun steps(capturesOnly: Boolean): List<Step> {
            val out = ArrayList<Step>(48)
            for (from in 0 until 64) {
                val piece = cells[from]
                if (piece == 0 || (piece > 0) != (side > 0)) continue
                val kind = abs(piece); val file = from % 8; val rank = from / 8
                fun add(to: Int, promote: Int = 0) {
                    val target = cells[to]
                    val gain = if (target == 0) 0 else WORTH[abs(target)] * 16 - kind
                    out.add(Step(from, to, promote, gain + (if (promote == 0) 0 else 8000)))
                }
                fun enemy(target: Int) = target != 0 && (target > 0) != (side > 0)
                when (kind) {
                    1 -> {
                        val ahead = rank + side
                        if (on(ahead)) {
                            val last = ahead == 0 || ahead == 7
                            val promote = if (last) 5 else 0
                            if (cells[ahead * 8 + file] == 0 && (!capturesOnly || last)) {
                                add(ahead * 8 + file, promote)
                                val two = rank + 2 * side
                                if (!capturesOnly && rank == (if (side == 1) 1 else 6) && cells[two * 8 + file] == 0) add(two * 8 + file)
                            }
                            for (df in intArrayOf(-1, 1)) {
                                val f = file + df
                                if (on(f) && enemy(cells[ahead * 8 + f])) add(ahead * 8 + f, promote)
                            }
                        }
                    }
                    2, 6 -> for ((df, dr) in if (kind == 2) KNIGHT_JUMPS else KING_STEPS) {
                        val f = file + df; val r = rank + dr
                        if (!on(f) || !on(r)) continue
                        val target = cells[r * 8 + f]
                        if (if (target == 0) !capturesOnly else enemy(target)) add(r * 8 + f)
                    }
                    else -> for ((df, dr) in if (kind == 3) DIAGONAL else if (kind == 4) STRAIGHT else QUEEN_RAYS) {
                        var f = file + df; var r = rank + dr
                        while (on(f) && on(r)) {
                            val target = cells[r * 8 + f]
                            if (target != 0) { if (enemy(target)) add(r * 8 + f); break }
                            if (!capturesOnly) add(r * 8 + f)
                            f += df; r += dr
                        }
                    }
                }
            }
            out.sortByDescending { it.order }
            return out
        }

        /**
         * Material and placement, from the side to move. The tables read from white's side with the eighth rank first, so a white
         * piece's square is mirrored and a black piece's is read as it stands.
         */
        fun evaluate(): Int {
            var officers = 0
            for (piece in cells) if (piece != 0) { val kind = abs(piece); if (kind != 1 && kind != 6) officers += WORTH[kind] }
            val late = officers <= 2600
            var score = 0
            for (sq in 0 until 64) {
                val piece = cells[sq]
                if (piece == 0) continue
                val kind = abs(piece); val white = piece > 0
                val table = if (kind == 6 && late) KING_LATE else PLACEMENT[kind]
                val value = WORTH[kind] + table[if (white) sq xor 56 else sq]
                score += if (white) value else -value
            }
            return score * side
        }
    }

    class Search(private val quiet: Boolean, private val deadline: Long) {
        companion object { const val INFINITY = 1_000_000; const val MATE = 100_000 }
        var timed = false
        var aborted = false
        private var nodes = 0

        private fun expired(): Boolean {
            nodes += 1
            if (timed && nodes and 1023 == 0 && System.nanoTime() > deadline) aborted = true
            return aborted
        }

        fun negamax(board: Board, depth: Int, alphaIn: Int, beta: Int, ply: Int): Int {
            if (expired()) return 0
            if (depth <= 0) return if (quiet) quiesce(board, alphaIn, beta, 0) else board.evaluate()
            var alpha = alphaIn; var legal = false
            for (step in board.steps(false)) {
                val taken = board.make(step)
                if (board.moverExposed) { board.unmake(step, taken); continue }
                legal = true
                val score = -negamax(board, depth - 1, -beta, -alpha, ply + 1)
                board.unmake(step, taken)
                if (aborted) return 0
                if (score >= beta) return beta
                if (score > alpha) alpha = score
            }
            // No legal move: mated (the sooner, the worse) or stalemated.
            if (!legal) return if (board.inCheck) -MATE + ply else 0
            return alpha
        }

        fun quiesce(board: Board, alphaIn: Int, beta: Int, depth: Int): Int {
            if (expired()) return 0
            val stand = board.evaluate()
            if (stand >= beta) return beta
            var alpha = max(alphaIn, stand)
            if (depth >= 8) return alpha
            for (step in board.steps(true)) {
                val taken = board.make(step)
                if (board.moverExposed) { board.unmake(step, taken); continue }
                val score = -quiesce(board, -beta, -alpha, depth + 1)
                board.unmake(step, taken)
                if (aborted) return 0
                if (score >= beta) return beta
                if (score > alpha) alpha = score
            }
            return alpha
        }
    }
}
