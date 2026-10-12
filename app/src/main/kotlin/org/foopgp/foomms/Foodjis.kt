package org.foopgp.foomms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    class Card(val fingerprint: String, val name: String, val eid: String?, val validity: String, val self: Boolean, val userIds: List<String>) {
        val certified: Boolean get() = validity == "certified"
        val usable: Boolean get() = validity == "certified" || validity == "uncertified"
    }

    /** foodjis has refused to answer: the person has not let this application in yet. */
    var shut = false
        private set

    private val door: String =
        "content://" + (if (context.packageName.endsWith(".dev")) Provider.FOODJIS_DEV else Provider.FOODJIS) + ".directory"

    private val cards = HashMap<String, Card?>()
    private val faces = HashMap<String, Bitmap?>()

    fun card(fingerprint: String): Card? = synchronized(cards) {
        if (cards.containsKey(fingerprint)) return cards[fingerprint]
        val card = asked {
            context.contentResolver.query(Uri.parse("$door/cert/$fingerprint"), null, null, null, null)?.use { rows ->
                if (!rows.moveToFirst()) null
                else Card(
                    rows.getString(0), rows.getString(1), rows.getString(2), rows.getString(3),
                    rows.getInt(4) == 1, rows.getString(5).orEmpty().split('\n').filter { it.isNotEmpty() },
                )
            }
        }
        if (!shut) cards[fingerprint] = card
        card
    }

    fun avatar(fingerprint: String): ByteArray? = asked {
        context.contentResolver.openInputStream(Uri.parse("$door/avatar/$fingerprint"))?.use { it.readBytes() }
    }

    fun face(fingerprint: String): Bitmap? = synchronized(faces) {
        if (faces.containsKey(fingerprint)) return faces[fingerprint]
        val face = avatar(fingerprint)?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        if (!shut) faces[fingerprint] = face
        face
    }

    /** Where the certificate can be fetched: asks the network, through foodjis. */
    fun address(fingerprint: String): String? = asked {
        context.contentResolver.query(Uri.parse("$door/address/$fingerprint"), null, null, null, null)?.use { rows ->
            if (rows.moveToFirst()) rows.getString(0) else null
        }
    }

    fun forget() {
        synchronized(cards) { cards.clear() }
        synchronized(faces) { faces.clear() }
        shut = false
    }

    companion object {
        @Volatile private var one: Foodjis? = null

        /** One for the application: what foodjis said is asked once. */
        fun of(context: Context): Foodjis = one ?: synchronized(this) {
            one ?: Foodjis(context.applicationContext).also { one = it }
        }
    }

    private fun <T> asked(question: () -> T?): T? = try {
        question().also { shut = false }
    } catch (refused: SecurityException) {
        shut = true
        null
    } catch (other: Exception) {
        null
    }
}
