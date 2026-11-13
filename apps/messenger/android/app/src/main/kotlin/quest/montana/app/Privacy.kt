package quest.montana.app

/**
 * WHO MAY REACH ME, ONE ANSWER (iOS MTPrivacy, b9b212d4, OZ 1 p.1.8, the author's word 21096, decision 908 = a: «Settings, Privacy:
 * who may write to me, call me, see my page, add me to a group or a channel -- Everyone, My contacts, Nobody»). The rule is kept on
 * the receiver's phone and obeyed there: the letter's door (Post.letter), the call's birth (Calls.incoming), my about, face and
 * ground (the page) and a group's invitation new to this phone ask here and nowhere else. A rule never set reads Everyone. The
 * rules are this device's own (iOS SeedScope.keychainStays): they ride no copy. Android has no notification extension, so no
 * contacts' mirror is kept: «my contacts» is read from the book itself, as the wall's rule reads it (MyWall.byRule).
 */
object Privacy {
    enum class Act(val raw: String) { WRITE("write"), CALL("call"), PAGE("page"), INVITE("invite") }
    enum class Who(val raw: String) { EVERYONE("everyone"), CONTACTS("contacts"), NOBODY("nobody") }
    private const val RULES = "privacyRules"   // NOT-UI: rows «act:who» one per line, Everyone left unwritten (iOS privacyRules)

    private fun rows(): Set<String> = Prefs.str(RULES, "").split('\n').filter { it.isNotEmpty() }.toSet()

    fun rule(a: Act): Who {
        val row = rows().firstOrNull { it.startsWith(a.raw + ":") } ?: return Who.EVERYONE
        return Who.values().firstOrNull { it.raw == row.removePrefix(a.raw + ":") } ?: Who.EVERYONE
    }

    /** One rule changed (iOS MTPrivacy.set). */
    fun set(a: Act, who: Who) {
        val rows = rows().filter { !it.startsWith(a.raw + ":") }.toMutableSet()
        if (who != Who.EVERYONE) rows.add(a.raw + ":" + who.raw)
        Prefs.setStr(RULES, rows.sorted().joinToString("\n"))
    }

    /** A person of the contacts' page: a named conversation that is no group; a folded pipe speaks for its root. */
    private fun contact(ref: String): Boolean =
        setOf(ref, SamePair.root(ref)).any { r -> !Groups.isKey(r) && Book.chat(r)?.shown?.isNotBlank() == true }

    fun admits(a: Act, ref: String): Boolean = when (rule(a)) {
        Who.EVERYONE -> true
        Who.NOBODY -> false
        Who.CONTACTS -> contact(ref)
    }

    /**
     * ASKED AT THE BLOCK'S DOORS (iOS ChatStore.privacyRefuses): a person's letter by the rule of writing, a ring or a missed call by
     * the rule of calling. A control word, a machine word and a group's word are never a person reaching me and are never refused.
     */
    fun refuses(text: String, ref: String): Boolean {
        if (Groups.isKey(ref) || text.startsWith(Groups.MARK)) return false
        if (text.startsWith(Marks.RING) || text.startsWith(Marks.MISSED)) return !admits(Act.CALL, ref)
        val letter = !Marks.isService(text) || text.startsWith(Marks.VOICE) || text.startsWith(Marks.MEDIA) ||
            text.startsWith(Marks.STICKER) || text.startsWith(Marks.LONG)
        return letter && !admits(Act.WRITE, ref)
    }
}
