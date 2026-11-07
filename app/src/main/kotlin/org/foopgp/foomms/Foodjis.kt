package org.foopgp.foomms

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri

/**
 * What foodjis says of one certificate, asked by fingerprint through the door
 * it keeps beside the service interface: the name, whether the person
 * certified it, the face, and its address on a key server.
 *
 * foodjis opens that door to the applications the person let in. Until then
 * every question here is refused, and [shut] says so.
 */
class Foodjis(private val context: Context) {

    class Cert(val fingerprint: String, val name: String, val eid: String?, val validity: String, val self: Boolean, val userIds: List<String>) {
        val certified: Boolean get() = validity == "certified"
        val usable: Boolean get() = validity == "certified" || validity == "uncertified"
    }

    /** foodjis has refused to answer: the person has not let this application in yet. */
    @Volatile var shut = false
        private set

    /** foodjis's directory, when a foodjis signed as foomms is ([Provider.whom]): none, and nothing is asked. */
    private val door: String? =
        Provider.whom(context)?.packageName?.let { "content://$it.directory" }

    private val cards = HashMap<String, Cert?>()
    private val faces = HashMap<String, Bitmap?>()

    // Held for reading and writing what is known, not while foodjis is asked: several askings go side by side.
    fun card(fingerprint: String): Cert? {
        synchronized(cards) { if (cards.containsKey(fingerprint)) return cards[fingerprint] }
        val card = tried {
            context.contentResolver.query(Uri.parse("$door/cert/$fingerprint"), null, null, null, null)?.use { rows ->
                if (!rows.moveToFirst()) null
                else Cert(
                    rows.getString(0), rows.getString(1), rows.getString(2), rows.getString(3),
                    rows.getInt(4) == 1, rows.getString(5).orEmpty().split('\n').filter { it.isNotEmpty() },
                )
            }
        }
        // Kept only when foodjis answered -- a certificate, or none --: one that could not answer, ended or updating,
        // is asked again next time rather than held to have said "none".
        card.onSuccess { found -> synchronized(cards) { cards[fingerprint] = found } }
        return card.getOrNull()
    }

    fun avatar(fingerprint: String): ByteArray? = tried {
        context.contentResolver.openInputStream(Uri.parse("$door/avatar/$fingerprint"))?.use { it.readBytes() }
    }.getOrNull()

    fun face(fingerprint: String): Bitmap? {
        synchronized(faces) { if (faces.containsKey(fingerprint)) return faces[fingerprint] }
        val bytes = tried { context.contentResolver.openInputStream(Uri.parse("$door/avatar/$fingerprint"))?.use { it.readBytes() } }
        val face = bytes.getOrNull()?.let { Media.thumbnail(it, Media.FACE) }
        bytes.onSuccess { synchronized(faces) { faces[fingerprint] = face } }
        return face
    }

    /** The vCard foodjis shares for this certificate; asks the network for the key server it names. */
    fun vcard(fingerprint: String): Card? = asked {
        context.contentResolver.openInputStream(Uri.parse("$door/vcard/$fingerprint"))
            ?.use { Card.of(String(it.readBytes(), Charsets.UTF_8)) }
    }

    /** The certificate a key id means, when one alone wears it: all a signature is reported with. */
    fun signer(keyId: Long): Cert? = card("%016X".format(keyId))

    /** Where the certificate can be fetched: asks the network, through foodjis. */
    fun address(fingerprint: String): String? = asked {
        context.contentResolver.query(Uri.parse("$door/address/$fingerprint"), null, null, null, null)?.use { rows ->
            if (rows.moveToFirst()) rows.getString(0) else null
        }
    }

    /**
     * What foodjis says of a certificate changes there -- certified, revoked,
     * renamed -- and nothing tells this application: a page coming back to the
     * front asks again. The faces are kept, which cost more and change less.
     */
    fun fresh() = synchronized(cards) { cards.clear() }

    fun forget() {
        synchronized(cards) { cards.clear() }
        synchronized(faces) { faces.clear() }
        shut = false
    }

    companion object {
        /** One for the application: what foodjis said is asked again when a page comes back ([fresh]). */
        @Volatile private var one: Foodjis? = null

        /**
         * What foodjis said, behind its icon -- in place of words saying who
         * speaks --; its name when the icon cannot be had.
         */
        fun says(context: Context, words: String?): CharSequence {
            val icon = runCatching { context.packageManager.getApplicationIcon(Provider.foodjis(context)) }.getOrNull()
                ?: return "foodjis : ${words.orEmpty()}"
            val side = Look.dp(context, 22)
            icon.setBounds(0, 0, side, side)
            return android.text.SpannableStringBuilder("\u00A0").apply {
                setSpan(android.text.style.ImageSpan(icon, android.text.style.DynamicDrawableSpan.ALIGN_BOTTOM), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append("  ").append(words.orEmpty())
            }
        }

        fun of(context: Context): Foodjis = one ?: synchronized(this) {
            one ?: Foodjis(context.applicationContext).also { one = it }
        }
    }

    /**
     * foodjis woken, out of the way, with a question that costs it nothing: its
     * process started -- what takes the longest the first time it is asked --
     * before a page needs its answers. Nothing is kept of the answer.
     */
    fun wake() {
        kotlin.concurrent.thread {
            runCatching { context.contentResolver.query(Uri.parse("$door/cert/${"0".repeat(40)}"), null, null, null, null)?.close() }
        }
    }

    private fun <T> asked(question: () -> T?): T? = tried(question).getOrNull()

    /** The answer, or why there was none: foodjis not letting this application in, or not there to answer. */
    private fun <T> tried(question: () -> T?): Result<T?> = if (door == null) Result.failure(IllegalStateException("no foodjis")) else try {
        Result.success(question().also { shut = false })
    } catch (refused: SecurityException) {
        shut = true
        Result.failure(refused)
    } catch (other: Exception) {
        Result.failure(other)
    }
}
