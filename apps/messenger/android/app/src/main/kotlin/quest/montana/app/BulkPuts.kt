package quest.montana.app

import android.util.Log

/**
 * THE BULK PUTS IN FLIGHT: A PLACE BELONGS TO A CARGO, AND THE PERSON GOES FIRST (iOS MTBulkPuts, 996b13f7; T1 10.11.2026 15:51Z).
 * The lane was one for the whole phone -- three places in the order of coming: three pieces of a wall's page stood on a silent door
 * and a photo sent that second waited behind them. A cargo now has [WIDTH] places of its own and waits for nobody but itself; the
 * phone holds [WHOLE] bodies at once (a body lives in memory for the whole put), and a freed place goes to the waiting cargo with
 * the fewest in flight, the first in line of it. A QUIET put (the long body of a state letter) starts only while no put of the
 * person is in flight or in line, and all the quiet together hold [WIDTH] places. Puts with no cargo mark share one key: the
 * person's the empty one, the service's [SERVICE], so the two share no place.
 */
object BulkPuts {
    const val WIDTH = 3
    const val WHOLE = 9
    const val SERVICE = "service"

    private class Wait(val cargo: String, val quiet: Boolean) { var go = false }
    private val lock = Object()
    private val running = HashMap<String, Int>()
    private var flying = 0
    private var hushed = 0
    private val waiting = ArrayList<Wait>()

    /** A put of the person is in flight or in line: the quiet stand aside ([yieldToPerson] waits on it). */
    val personAhead: Boolean get() = synchronized(lock) { ahead() }
    private fun ahead() = hushed < flying || waiting.any { !it.quiet }

    /** A place for this cargo's put; the caller blocks until it has one. An interrupt leaves the line and is thrown on. */
    fun enter(cargo: String, quiet: Boolean) = synchronized(lock) {
        if (admits(cargo, quiet)) { take(cargo, quiet); return@synchronized }
        val w = Wait(cargo, quiet)
        waiting.add(w)
        try { while (!w.go) lock.wait() }
        catch (e: InterruptedException) { if (w.go) give(cargo, quiet) else waiting.remove(w); throw e }
    }

    fun leave(cargo: String, quiet: Boolean) = synchronized(lock) { give(cargo, quiet) }

    private fun give(cargo: String, quiet: Boolean) {
        flying--
        if (quiet) hushed--
        val left = (running[cargo] ?: 1) - 1
        if (left > 0) running[cargo] = left else running.remove(cargo)
        var woke = false
        while (true) {
            val i = nextInLine() ?: break
            val w = waiting.removeAt(i)
            take(w.cargo, w.quiet); w.go = true; woke = true
        }
        if (woke) lock.notifyAll()
    }

    private fun take(cargo: String, quiet: Boolean) {
        flying++
        running[cargo] = (running[cargo] ?: 0) + 1
        if (quiet) hushed++
    }

    private fun admits(cargo: String, quiet: Boolean): Boolean {
        if (flying >= WHOLE || (running[cargo] ?: 0) >= WIDTH) return false
        return !quiet || (hushed < WIDTH && !ahead())
    }

    /** The first in line of the cargo with the fewest in flight, among the puts admitted now; no quiet one past a waiting person. */
    private fun nextInLine(): Int? {
        var best: Int? = null
        for ((i, w) in waiting.withIndex()) {
            if (!admits(w.cargo, w.quiet)) continue
            val b = best
            if (b != null && (running[waiting[b].cargo] ?: 0) <= (running[w.cargo] ?: 0)) continue
            best = i
        }
        return best
    }

    /**
     * THE SERVICE STANDS ASIDE FOR WHAT THE PERSON SENDS AND WAITS FOR (iOS yieldToPerson, 996b13f7): a quiet put waits here, holding
     * no body and no place, while a piece the person waits for is in line (Lane.wanted) or a put of the person is in flight or in
     * line. False -- the thread was interrupted.
     */
    fun yieldToPerson(work: String): Boolean {
        var held = false
        while (true) {
            val media = Lane.wanted
            if (!media && !personAhead) return true
            if (!held) { held = true; Log.d("Montana", "bulk_lane held=" + (if (media) "media" else "put") + " work=" + work) }
            try { Thread.sleep(1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
        }
    }
}
