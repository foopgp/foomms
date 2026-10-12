package org.foopgp.foomms

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Phone as PhoneRow
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts

/**
 * The phone's address book, where a number is tied to a certificate: few
 * people put their mobile number in their certificate, so each person says
 * whose key a number means by keeping the certificate's fingerprint in the
 * contact -- in a web address, the form a vCard's KEY field gives it
 * (`KEY;MEDIATYPE=application/pgp-keys:https://…/pks/lookup?op=get&search=0x…`).
 * Any web address of the contact that holds a fingerprint is read as one.
 */
object Book {

    class Person(val contact: Long, val name: String, val photo: Uri?, val fingerprint: String?)

    fun who(context: Context, number: String): Person? = runCatching {
        val asked = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val columns = arrayOf(
            ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI,
        )
        context.contentResolver.query(asked, columns, null, null, null)?.use { rows ->
            if (!rows.moveToFirst()) return@use null
            val contact = rows.getLong(0)
            Person(contact, rows.getString(1) ?: number, rows.getString(2)?.let(Uri::parse), fingerprint(context, contact))
        }
    }.getOrNull()

    private fun fingerprint(context: Context, contact: Long): String? =
        values(context, contact, Website.CONTENT_ITEM_TYPE, Website.URL).firstNotNullOfOrNull { Fingerprint.of(it) }

    private fun values(context: Context, contact: Long, kind: String, column: String): List<String> {
        val found = ArrayList<String>()
        context.contentResolver.query(
            Data.CONTENT_URI, arrayOf(column), "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(contact.toString(), kind), null,
        )?.use { rows -> while (rows.moveToNext()) rows.getString(0)?.let { found += it } }
        return found
    }

    /**
     * Write that [number] means the certificate at [address], and what the
     * certificate says of its holder -- name, addresses, numbers, face -- where
     * the contact does not say it already. A contact is made when the number
     * has none.
     */
    fun tie(context: Context, number: String, address: String, name: String, userIds: List<String>, avatar: ByteArray?) {
        val known = who(context, number)
        val raw = known?.let { rawOf(context, it.contact) } ?: create(context, number, name)
        val contact = known?.contact ?: contactOf(context, raw)
        val resolver = context.contentResolver

        // One certificate a contact: the address of another is replaced, not kept beside.
        val former = values(context, contact, Website.CONTENT_ITEM_TYPE, Website.URL).filter { Fingerprint.of(it) != null }
        for (old in former) {
            resolver.delete(
                Data.CONTENT_URI, "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${Website.URL} = ?",
                arrayOf(contact.toString(), Website.CONTENT_ITEM_TYPE, old),
            )
        }
        val rows = ArrayList<ContentValues>()
        fun row(kind: String, column: String, value: String, more: ContentValues.() -> Unit = {}) {
            rows += ContentValues().apply {
                put(Data.RAW_CONTACT_ID, raw) ; put(Data.MIMETYPE, kind) ; put(column, value) ; more()
            }
        }
        row(Website.CONTENT_ITEM_TYPE, Website.URL, address) {
            put(Website.TYPE, Website.TYPE_CUSTOM) ; put(Website.LABEL, "PGP")
        }
        fun missing(kind: String, column: String, value: String, same: (String, String) -> Boolean) =
            values(context, contact, kind, column).none { same(it, value) }
        for (userId in userIds.flatMap(::properties)) {
            val value = plain(userId.substringAfter(':').trim())
            if (value.isEmpty()) continue
            when (userId.substringBefore(':').substringBefore(';')) {
                "EMAIL" -> if (missing(Email.CONTENT_ITEM_TYPE, Email.ADDRESS, value) { a, b -> a.equals(b, true) }) {
                    row(Email.CONTENT_ITEM_TYPE, Email.ADDRESS, value) { put(Email.TYPE, Email.TYPE_OTHER) }
                }
                "TEL" -> Phone.plain(value)?.let { plain ->
                    if (missing(PhoneRow.CONTENT_ITEM_TYPE, PhoneRow.NUMBER, plain, Phone::same)) {
                        row(PhoneRow.CONTENT_ITEM_TYPE, PhoneRow.NUMBER, plain) { put(PhoneRow.TYPE, PhoneRow.TYPE_OTHER) }
                    }
                }
                "ADR" -> {
                    // A vCard address is seven fields between semicolons: read as lines, the empty ones dropped.
                    val lines = value.split(';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                    if (lines.isNotEmpty() && missing(StructuredPostal.CONTENT_ITEM_TYPE, StructuredPostal.FORMATTED_ADDRESS, lines) { a, b -> a == b }) {
                        row(StructuredPostal.CONTENT_ITEM_TYPE, StructuredPostal.FORMATTED_ADDRESS, lines) {
                            put(StructuredPostal.TYPE, StructuredPostal.TYPE_OTHER)
                        }
                    }
                }
                "URL" -> if (missing(Website.CONTENT_ITEM_TYPE, Website.URL, value) { a, b -> a == b }) {
                    row(Website.CONTENT_ITEM_TYPE, Website.URL, value) { put(Website.TYPE, Website.TYPE_OTHER) }
                }
                "NOTE" -> if (missing(Note.CONTENT_ITEM_TYPE, Note.NOTE, value) { a, b -> a == b }) {
                    row(Note.CONTENT_ITEM_TYPE, Note.NOTE, value)
                }
            }
        }
        for (values in rows) resolver.insert(Data.CONTENT_URI, values)
        // The face the certificate carries, when the contact has none of its own.
        if (avatar != null && known?.photo == null) {
            resolver.insert(
                Data.CONTENT_URI,
                ContentValues().apply {
                    put(Data.RAW_CONTACT_ID, raw) ; put(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE) ; put(Photo.PHOTO, avatar)
                },
            )
        }
    }

    /**
     * A user id as vCard properties: itself when it is one (`EMAIL:…`), and
     * for the older form `Name <address>` the address it carries.
     */
    internal fun properties(userId: String): List<String> = when {
        PROPERTY.containsMatchIn(userId) -> listOf(userId)
        else -> listOfNotNull(
            userId.substringAfterLast('<', "").substringBefore('>').takeIf { '@' in it }?.let { "EMAIL:$it" },
        )
    }

    /** A vCard value as it reads: `\,` is a comma, `\;` a semicolon, `\n` a new line (RFC 6350, section 3.4). */
    internal fun plain(value: String): String =
        ESCAPE.replace(value) { found -> if (found.groupValues[1].equals("n", true)) "\n" else found.groupValues[1] }

    private val ESCAPE = Regex("""\\(.)""")
    private val PROPERTY = Regex("^[A-Z]+[:;]")

    private fun rawOf(context: Context, contact: Long): Long? =
        context.contentResolver.query(
            RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), "${RawContacts.CONTACT_ID} = ?", arrayOf(contact.toString()), null,
        )?.use { rows -> if (rows.moveToFirst()) rows.getLong(0) else null }

    private fun contactOf(context: Context, raw: Long): Long =
        context.contentResolver.query(
            RawContacts.CONTENT_URI, arrayOf(RawContacts.CONTACT_ID), "${RawContacts._ID} = ?", arrayOf(raw.toString()), null,
        )?.use { rows -> if (rows.moveToFirst()) rows.getLong(0) else null } ?: -1

    /** A contact kept on the phone itself, with the name the certificate gives and the number. */
    private fun create(context: Context, number: String, name: String): Long {
        val made = context.contentResolver.applyBatch(
            ContactsContract.AUTHORITY,
            arrayListOf(
                ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                    .withValue(RawContacts.ACCOUNT_TYPE, null).withValue(RawContacts.ACCOUNT_NAME, null).build(),
                ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(StructuredName.DISPLAY_NAME, name).build(),
                ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, PhoneRow.CONTENT_ITEM_TYPE)
                    .withValue(PhoneRow.NUMBER, number).withValue(PhoneRow.TYPE, PhoneRow.TYPE_MOBILE).build(),
            ),
        )
        return android.content.ContentUris.parseId(made[0].uri!!)
    }
}
