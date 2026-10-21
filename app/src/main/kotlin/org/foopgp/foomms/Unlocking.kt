package org.foopgp.foomms

import android.content.Context
import android.content.Intent
import org.openintents.openpgp.OpenPgpSignatureResult

/**
 * What is locked, opened several messages for one use of the key: those of a
 * conversation around the one pressed, and those not read yet in the others.
 * The conversation's page and the list both ask through here.
 */
object Unlocking {

    /** The most the list's button opens with one use of the key, whatever is set for a conversation. */
    const val MOST_AT_ONCE = 10

    /** Something a message holds that the key is to open: its words, or one of the parts it carries. */
    sealed class Job {
        abstract val line: Store.Line
        class Words(override val line: Store.Line, val armor: String) : Job()
        class Part(override val line: Store.Line, val piece: Store.Piece, val bytes: ByteArray) : Job()

        val input: ByteArray get() = when (this) { is Words -> armor.toByteArray() ; is Part -> bytes }
    }

    /**
     * A line's PGP message: the innermost opened so far ([Opened.inner]), or
     * none. One envelope opened onto another shows that one, closed, as a
     * message of its own -- grey, "encrypted for" --, and the person opens it
     * or not: nothing is opened further behind their back.
     */
    fun armorOf(line: Store.Line): String? = Pgp.armorIn(line.body)?.let { Opened.inner(it) }

    /** What of a message is still closed: its words, and each sealed part, read from the phone -- not on the main thread. */
    fun jobs(context: Context, line: Store.Line): List<Job> {
        val found = ArrayList<Job>()
        armorOf(line)?.takeIf { Opened.words(it) == null }?.let { found += Job.Words(line, it) }
        for (piece in line.pieces.filter { it.sealed && Opened.piece(it.id) == null }) {
            runCatching { context.contentResolver.openInputStream(piece.uri)?.use { it.readBytes() } }.getOrNull()
                ?.let { found += Job.Part(line, piece, it) }
        }
        return found
    }

    /**
     * The messages received and not read yet that hold something locked and
     * still closed, newest first, in every conversation but [besides]: at
     * most [most]. Not on the main thread.
     */
    fun waiting(context: Context, most: Int, besides: Long? = null): List<Store.Line> {
        if (most <= 0) return emptyList()
        val found = ArrayList<Store.Line>()
        for (thread in Store.unreadThreads(context)) {
            if (thread == besides) continue
            val lines = runCatching { Store.lines(context, thread) }.getOrNull() ?: continue
            found += lines.filter { it.unread && it.incoming && locked(context, it) }
        }
        return found.sortedByDescending { it.at }.take(most)
    }

    /** Whether a message holds something locked -- not only signed -- and still closed. */
    private fun locked(context: Context, line: Store.Line): Boolean =
        armorOf(line)?.let { Pgp.isLocked(it) && Opened.words(it) == null } == true ||
            line.pieces.any { piece ->
                piece.sealed && Opened.piece(piece.id) == null &&
                    runCatching { context.contentResolver.openInputStream(piece.uri)?.use { it.readBytes() } }.getOrNull()?.let { Pgp.isLocked(it) } == true
            }

    /**
     * [first] asked for through [ask], which shows the screens of foodjis --
     * the key to present --; [rest] told of before, so that foodjis knows
     * what waits, and collected after: one presentation of the key has
     * answered for all of them. A signed message needs no key and is read as
     * soon as it is told of. [done] hears of each thing opened, on the main
     * thread, while [alive].
     */
    fun open(
        provider: Provider, first: Job, rest: List<Job>,
        ask: (ByteArray, (Provider.Answer.Done) -> Unit) -> Unit,
        alive: () -> Boolean, done: (Job, Provider.Answer.Done) -> Unit,
    ) {
        val waiting = rest.toMutableList()
        fun theFirst() {
            ask(first.input) { answer ->
                done(first, answer)
                // A message alike byte for byte -- received twice, sent twice -- is one asking for foodjis, which
                // answers it once: those like the first take its answer, and the others are asked for once a kind.
                val alike = waiting.filter { it.input.contentEquals(first.input) }
                alike.forEach { done(it, answer) }
                // What the key answered for the others is waiting: each is asked for again, and says nothing if it is not.
                for (kind in (waiting - alike.toSet()).groupBy { java.nio.ByteBuffer.wrap(it.input) }.values) {
                    provider.ask(Intent(Provider.DECRYPT_VERIFY), kind.first().input) { again ->
                        if (again is Provider.Answer.Done && alive()) kind.forEach { done(it, again) }
                    }
                }
            }
        }
        var left = rest.size
        if (left == 0) return theFirst()
        for (job in rest) {
            provider.ask(Intent(Provider.DECRYPT_VERIFY), job.input) { answer ->
                if (answer is Provider.Answer.Done && alive()) { done(job, answer) ; waiting.remove(job) }
                if (--left == 0 && alive()) theFirst()
            }
        }
    }

    /** What the key opened in a conversation not on screen, kept for it as its page keeps it. */
    fun keep(context: Context, job: Job, answer: Provider.Answer.Done) {
        when (job) {
            is Job.Words -> Signer.keep(context, job.armor, answer, job.line.thread)
            is Job.Part -> keepPiece(context, job.piece, answer, Pgp.isLocked(job.bytes), job.line.thread)
        }
    }

    /** A locked part, opened: kept with what it is, which is told back. */
    fun keepPiece(context: Context, piece: Store.Piece, answer: Provider.Answer.Done, locked: Boolean, thread: Long): Kind.Told {
        answer.says.setExtrasClassLoader(OpenPgpSignatureResult::class.java.classLoader)
        @Suppress("DEPRECATION")
        val signature = answer.says.getParcelableExtra<OpenPgpSignatureResult>(Provider.RESULT_SIGNATURE)
        val certified = signature?.result == OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED
        val told = Kind.of(answer.bytes.copyOf(minOf(answer.bytes.size, 32)), null)
        val name = Pgp.innerName(piece.name, answer.bytes, told.extension)
        val signer = signature?.takeIf { it.result != OpenPgpSignatureResult.RESULT_NO_SIGNATURE }
        Opened.keepPiece(
            context, piece.id, told.type, name, answer.bytes, Verdict.of(locked, certified),
            signed = Signer.of(signature), signer = signer?.keyId, eid = signer?.let { Signer.eid(it) }, thread = thread,
        )
        return told
    }
}
