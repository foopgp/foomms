package org.foopgp.foomms

import android.content.Context

/**
 * What was being written and not sent, kept until it is: Android may close a
 * page while another application is in front, or a notification may open it
 * anew, and the words would be gone.
 *
 * The phone's store holds a mark of it, a draft with no words, in the
 * conversation it is for -- an SMS to one, an MMS to a group, an SMS in the
 * conversation of each when it is the same to each --: the conversation then
 * exists and is listed, by the moment of its draft, and how the draft is to
 * leave is the store's to tell. The words stay here, with foomms alone: any
 * application that may read the messages may read the store.
 */
object Drafts {

    /** How a draft is to leave: to one number, as a group MMS, or to each of several. */
    enum class How { ONE, GROUP, EACH }

    /** The words of the draft to these numbers -- one person, a group's members but this phone, or those a sending to each is for. */
    fun of(context: Context, numbers: List<String>): String {
        val kept = kept(context)
        val key = key(numbers)
        // To each: one draft for them all.
        kept.getString("e$key", null)?.let { id -> return kept.getString("w$id", null).orEmpty() }
        val thread = kept.getLong("k$key", NONE).takeIf { it != NONE }
        return thread?.let { inThread(context, it) } ?: legacy(context).getString(key, null).orEmpty()
    }

    fun of(context: Context, number: String): String = of(context, listOf(number))

    /** The words of the draft a conversation holds, when foomms wrote it. */
    fun inThread(context: Context, thread: Long): String? {
        val kept = kept(context)
        val id = kept.getString("t$thread", null) ?: return null
        return kept.getString("w$id", null)?.takeIf { it.isNotBlank() }
    }

    /** Those the same draft is for, each in their own conversation, when this conversation's is a sending to each's. */
    fun eachOf(context: Context, thread: Long): List<String>? {
        val kept = kept(context)
        val id = kept.getString("t$thread", null) ?: return null
        return kept.getString("s$id", null)?.split(',')?.filter { it.isNotEmpty() }?.takeIf { it.size > 1 }
    }

    /** How the draft to these very numbers was to leave, when there is one. */
    fun how(context: Context, numbers: List<String>): How? {
        val kept = kept(context)
        val key = key(numbers)
        if (kept.getString("e$key", null) != null) return How.EACH
        val thread = kept.getLong("k$key", NONE).takeIf { it != NONE } ?: return null
        val id = kept.getString("t$thread", null) ?: return null
        return kept.getString("h$id", null)?.let { runCatching { How.valueOf(it) }.getOrNull() }
    }

    /** The conversation a group's draft was marked in: the page opens on it without looking for it among them all. */
    fun groupThread(context: Context, numbers: List<String>): Long? {
        val kept = kept(context)
        val thread = kept.getLong("k${key(numbers)}", NONE).takeIf { it != NONE } ?: return null
        return thread.takeIf { kept.getString("h${kept.getString("t$it", null)}", null) == How.GROUP.name }
    }

    /**
     * Kept to one, [How.ONE], or to a group, [How.GROUP] -- its [numbers]
     * its members but this phone --, in [thread] when the page knows its
     * conversation; or forgotten, mark and words, when nothing is left.
     * Not on the main thread: the store is written.
     */
    fun keep(context: Context, numbers: List<String>, words: String, how: How, thread: Long? = null) {
        val kept = kept(context)
        val key = key(numbers)
        val before = kept.getLong("k$key", NONE).takeIf { it != NONE }
        legacy(context).edit().remove(key).apply()
        if (words.isBlank()) {
            val at = thread ?: before ?: return
            Keep.unmarkDraft(context, at)
            val id = kept.getString("t$at", null)
            val edit = kept.edit().remove("k$key").remove("t$at")
            // Words a sending to each still shares with others stay theirs.
            if (id != null && kept.getString("s$id", null) == null) edit.remove("w$id").remove("h$id")
            edit.apply()
            return
        }
        val at = thread ?: before ?: runCatching {
            val all = if (how == How.GROUP) Phone.sent(numbers, Me.number(context)).toSet() else numbers.toSet()
            android.provider.Telephony.Threads.getOrCreateThreadId(context, all)
        }.getOrNull() ?: return
        var id = kept.getString("t$at", null)
        // A sending to each's words changed in one of its conversations: from then on that person's own.
        if (id != null && kept.getString("s$id", null) != null && how != How.EACH) {
            if (kept.getString("w$id", null) == words) return
            id = null
        }
        val fresh = id ?: newId(context)
        val changed = words != kept.getString("w$fresh", null) || how.name != kept.getString("h$fresh", null)
        kept.edit().putString("w$fresh", words).putString("h$fresh", how.name).putString("t$at", fresh).putLong("k$key", at).apply()
        if (changed || !Keep.hasDraft(context, at)) Keep.markDraft(context, at, numbers, group = how == How.GROUP)
    }

    /**
     * The same draft to each of [numbers], marked in the conversation of
     * each, over the draft any of them had; or forgotten everywhere. Not on
     * the main thread.
     */
    fun keepEach(context: Context, numbers: List<String>, words: String) {
        val kept = kept(context)
        val key = key(numbers)
        val id = kept.getString("e$key", null)
        if (words.isBlank()) {
            if (id == null) { legacy(context).edit().remove(key).apply() ; return }
            val edit = kept.edit()
            for (one in numbers) {
                val single = key(listOf(one))
                val at = kept.getLong("k$single", NONE).takeIf { it != NONE } ?: continue
                if (kept.getString("t$at", null) != id) continue
                Keep.unmarkDraft(context, at)
                edit.remove("t$at").remove("k$single")
            }
            edit.remove("e$key").remove("w$id").remove("h$id").remove("s$id").apply()
            return
        }
        legacy(context).edit().remove(key).apply()
        val fresh = id ?: newId(context)
        val changed = words != kept.getString("w$fresh", null)
        kept.edit().putString("w$fresh", words).putString("h$fresh", How.EACH.name)
            .putString("s$fresh", numbers.joinToString(",")).putString("e$key", fresh).apply()
        for (one in numbers) {
            val single = key(listOf(one))
            val at = kept.getLong("k$single", NONE).takeIf { it != NONE }
                ?: runCatching { android.provider.Telephony.Threads.getOrCreateThreadId(context, one) }.getOrNull() ?: continue
            val mine = kept.getString("t$at", null) == fresh
            kept.edit().putString("t$at", fresh).putLong("k$single", at).apply()
            if (!mine || changed || !Keep.hasDraft(context, at)) Keep.markDraft(context, at, listOf(one), group = false)
        }
    }

    /** Those of [numbers] whose conversation holds a draft of their own, which the same to each would replace. */
    fun ownOf(context: Context, numbers: List<String>): List<String> {
        val kept = kept(context)
        val each = kept.getString("e${key(numbers)}", null)
        return numbers.filter { one ->
            val at = kept.getLong("k${key(listOf(one))}", NONE).takeIf { it != NONE } ?: return@filter legacy(context).getString(key(listOf(one)), null) != null
            val id = kept.getString("t$at", null) ?: return@filter false
            id != each && !kept.getString("w$id", null).isNullOrBlank()
        }
    }

    /** Every draft forgotten: the marks in the store, any application's, and their words here. */
    fun forgetAll(context: Context) {
        Keep.unmarkAllDrafts(context)
        kept(context).edit().clear().apply()
        legacy(context).edit().clear().apply()
    }

    /** Each number however it is written; a sender that is a name, by that name. */
    private fun key(numbers: List<String>): String =
        numbers.map { Phone.plain(it) ?: it.trim().lowercase() }.distinct().sorted().joinToString(",")

    private fun newId(context: Context): String {
        val kept = kept(context)
        val next = kept.getLong("next", 0L) + 1
        kept.edit().putLong("next", next).apply()
        return next.toString()
    }

    private const val NONE = -1L

    private fun kept(context: Context) = context.getSharedPreferences("draft-marks", Context.MODE_PRIVATE)

    /** Drafts kept before they were marked in the store, by their numbers alone: read, and moved on when next kept. */
    private fun legacy(context: Context) = context.getSharedPreferences("drafts", Context.MODE_PRIVATE)
}
