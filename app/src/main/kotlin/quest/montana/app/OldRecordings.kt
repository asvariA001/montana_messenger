package quest.montana.app

import org.json.JSONObject

/**
 * THE OLD CALL RECORDINGS STAY OUT OF SIGHT (the author's word 05.11.2026: «why do call recordings still load, we removed them»).
 * DEVIATION from iOS: there a recording already sent stays its chat's letter (ChatStore.sweepCallRecordings). Here the letters and the
 * notices of the retired recorder are kept in the book, untouched and undeleted, and no screen shows them: not the feed, the chat
 * list's line, the counters, the search or the panes. Their files are no longer fetched.
 * A letter is known by the exact words the retired recorder wrote (iOS MTCallRecording, CallTape before 1680e947), in all its languages.
 * A caption counts only on a voice or a film, so a person's own text or picture with the same words stays.
 */
object OldRecordings {
    private val CAPTIONS = setOf(
        "Call recording", "Call recording — incomplete",
        "Запись звонка", "Запись звонка — неполная",   // CYRILLIC-DATA-OK: the retired recorder's own words
        "通话录制", "通话录制 — 不完整",
    )
    private val NOTICES = setOf(
        "This call is being recorded. The recording will be sent to this chat after the call.",
        "This phone is recording the call locally. Only the caller sends a recording to the chat.",
        "This call has no recording because no audio was received.",
        "The call recording could not be saved yet. Montana will try again when you return to the app.",
        "Этот звонок записывается. После завершения запись будет отправлена в этот чат.",   // CYRILLIC-DATA-OK: the retired recorder's own words
        "Этот телефон записывает разговор локально. Запись в чат отправляет только тот, кто позвонил.",   // CYRILLIC-DATA-OK: the retired recorder's own words
        "Запись этого звонка отсутствует: звук не поступал.",   // CYRILLIC-DATA-OK: the retired recorder's own words
        "Пока не удалось сохранить запись звонка. Монтана попробует снова, когда вы вернётесь в приложение.",   // CYRILLIC-DATA-OK: the retired recorder's own words
        "此通话正在录制。通话结束后，录制文件将发送到此聊天。",
        "本机正在本地录制通话。只有主叫方会将录音发送到聊天。",
        "此通话没有录音，因为未收到音频。",
        "通话录制暂时无法保存。返回应用时，Montana 将重试。",
    )
    private const val SETTLED = "oldRecordingsSettled.v1"   // NOT-UI: a key's name

    fun notice(text: String): Boolean = text in NOTICES

    fun caption(man: JSONObject?): Boolean =
        man != null && man.optString("cap") in CAPTIONS && man.optString("k").let { it == "aud" || it == "vid" }

    /** Read on every row of a feed: only a letter that carries a caption at all is parsed. */
    fun hidden(m: Msg): Boolean {
        if (Retired.letter(m.text)) return true   // a secret letter of an older build (iOS mtRetiredLetter): no screen shows it
        if (!m.text.startsWith(Marks.MEDIA)) return notice(m.text)
        if ((m.meta ?: m.text).contains("\"cap\"").not()) return false
        return caption(Media.manifestOf(m))
    }

    /** A hidden letter leaves the counter when it stood among the unread: a sealed manifest names it only once it is read. */
    fun uncount(c: Chat, m: Msg) {
        if (0 < c.unread && hidden(m) && c.msgs.filter { !it.mine }.takeLast(c.unread).any { it.mid == m.mid }) c.unread--
    }

    /** Once per phone: the letters that already stood unread when this build came leave the counters. */
    fun settle() {
        if (Prefs.bool(SETTLED, false)) return
        for (ch in Book.all()) if (0 < ch.unread) {
            val hid = ch.msgs.toList().filter { !it.mine }.takeLast(ch.unread).count { hidden(it) }
            if (0 < hid) Book.edit(ch.ref) { it.unread = maxOf(0, it.unread - hid) }
        }
        Prefs.setBool(SETTLED, true)
    }
}
