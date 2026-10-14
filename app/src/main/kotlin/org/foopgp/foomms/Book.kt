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

    class Found(val name: String, val number: String)

    /** The contacts whose name or number holds [typed], each with a number one can write to. */
    fun find(context: Context, typed: String, most: Int = 30): List<Found> = runCatching {
        val asked = Uri.withAppendedPath(PhoneRow.CONTENT_FILTER_URI, Uri.encode(typed.trim()))
        val found = ArrayList<Found>()
        context.contentResolver.query(asked, arrayOf(PhoneRow.DISPLAY_NAME, PhoneRow.NUMBER), null, null, PhoneRow.DISPLAY_NAME)?.use { rows ->
            while (rows.moveToNext() && found.size < most) {
                val number = rows.getString(1) ?: continue
                // The same number written twice in a contact is one way to reach them.
                if (found.none { Phone.same(it.number, number) }) found += Found(rows.getString(0) ?: number, number)
            }
        }
        found
    }.getOrDefault(emptyList())

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
     * Write that [number] means the certificate [card] is the card of, and
     * what the card says of its holder -- name, addresses, numbers, notes,
     * face -- where the contact does not say it already. A contact is made
     * when the number has none.
     */
    fun tie(context: Context, number: String, card: Card, address: String) {
        val known = who(context, number)
        val raw = known?.let { rawOf(context, it.contact) } ?: create(context, number, card.name ?: number)
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
        fun row(kind: String, column: String, value: String, more: ContentValues.() -> Unit = {}) {
            resolver.insert(
                Data.CONTENT_URI,
                ContentValues().apply { put(Data.RAW_CONTACT_ID, raw) ; put(Data.MIMETYPE, kind) ; put(column, value) ; more() },
            )
        }
        fun missing(kind: String, column: String, value: String, same: (String, String) -> Boolean = { a, b -> a == b }) =
            values(context, contact, kind, column).none { same(it, value) }

        row(Website.CONTENT_ITEM_TYPE, Website.URL, address) { put(Website.TYPE, Website.TYPE_CUSTOM) ; put(Website.LABEL, "PGP") }
        for (email in card.emails) {
            if (missing(Email.CONTENT_ITEM_TYPE, Email.ADDRESS, email) { a, b -> a.equals(b, true) }) {
                row(Email.CONTENT_ITEM_TYPE, Email.ADDRESS, email) { put(Email.TYPE, Email.TYPE_OTHER) }
            }
        }
        for (phone in card.phones.mapNotNull { Phone.plain(it) }) {
            if (!Phone.same(phone, number) && missing(PhoneRow.CONTENT_ITEM_TYPE, PhoneRow.NUMBER, phone, Phone::same)) {
                row(PhoneRow.CONTENT_ITEM_TYPE, PhoneRow.NUMBER, phone) { put(PhoneRow.TYPE, PhoneRow.TYPE_OTHER) }
            }
        }
        for (postal in card.addresses) {
            if (missing(StructuredPostal.CONTENT_ITEM_TYPE, StructuredPostal.FORMATTED_ADDRESS, postal)) {
                row(StructuredPostal.CONTENT_ITEM_TYPE, StructuredPostal.FORMATTED_ADDRESS, postal) {
                    put(StructuredPostal.TYPE, StructuredPostal.TYPE_OTHER)
                }
            }
        }
        for (site in card.websites) {
            if (missing(Website.CONTENT_ITEM_TYPE, Website.URL, site)) {
                row(Website.CONTENT_ITEM_TYPE, Website.URL, site) { put(Website.TYPE, Website.TYPE_OTHER) }
            }
        }
        for (note in card.notes) {
            if (missing(Note.CONTENT_ITEM_TYPE, Note.NOTE, note)) row(Note.CONTENT_ITEM_TYPE, Note.NOTE, note)
        }
        // The face the certificate carries, when the contact has none of its own.
        val face = card.photo
        if (face != null && known?.photo == null) {
            resolver.insert(
                Data.CONTENT_URI,
                ContentValues().apply {
                    put(Data.RAW_CONTACT_ID, raw) ; put(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE) ; put(Photo.PHOTO, face)
                },
            )
        }
    }

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
