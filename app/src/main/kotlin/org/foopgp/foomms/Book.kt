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

    /** [photo]: the contact's picture, small -- what a list shows of it, and cheap to draw. */
    class Person(val contact: Long, val name: String, val photo: Uri?, val fingerprint: String?)

    /** The contact's picture, small, read here rather than where it is drawn. */
    fun face(context: Context, person: Person): android.graphics.Bitmap? = person.photo?.let { uri ->
        Media.thumbnail(context, uri, Media.FACE)
    }

    fun who(context: Context, number: String): Person? = runCatching {
        val asked = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val columns = arrayOf(
            ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
        )
        // A number may be in more than one contact -- written once with its country, once without, or
        // synchronised from two accounts: the one that names a certificate is the one meant.
        context.contentResolver.query(asked, columns, null, null, null)?.use { rows ->
            val found = ArrayList<Person>()
            while (rows.moveToNext()) {
                val contact = rows.getLong(0)
                if (found.any { it.contact == contact }) continue
                found += Person(contact, rows.getString(1) ?: number, rows.getString(2)?.let(Uri::parse), fingerprint(context, contact))
            }
            found.firstOrNull { it.fingerprint != null } ?: found.firstOrNull()
        }
    }.getOrNull()

    /** The contact [contact] when it is given -- one's own --, else whoever [number] finds. */
    private fun known(context: Context, number: String, contact: Long?): Person? =
        if (contact != null) person(context, contact) else who(context, number)

    /** A contact by its number in the address book, with its name, picture and certificate. */
    fun person(context: Context, contact: Long): Person? = runCatching {
        context.contentResolver.query(
            android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contact),
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI), null, null, null,
        )?.use { rows -> if (rows.moveToFirst()) Person(contact, rows.getString(0).orEmpty(), rows.getString(1)?.let(Uri::parse), fingerprint(context, contact)) else null }
    }.getOrNull()

    /** Counts each writing in the address book made from here: what was read of it before is to be read again. */
    @Volatile var written = 0
        private set

    /** Take the certificate off a contact: the web address that names it, and nothing else of what was written. */
    fun untie(context: Context, contact: Long) {
        for (old in values(context, contact, Website.CONTENT_ITEM_TYPE, Website.URL).filter { Fingerprint.of(it) != null }) {
            context.contentResolver.delete(
                dataOf(contact), "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${Website.URL} = ?",
                arrayOf(contact.toString(), Website.CONTENT_ITEM_TYPE, old),
            )
        }
        written++
    }

    class Found(val name: String, val number: String)

    /** The contacts whose name or number holds [typed] (all, when nothing is typed), each with a number one can write to. */
    fun find(context: Context, typed: String, most: Int = 30): List<Found> = runCatching {
        // Nothing typed, every contact with a number.
        val asked = if (typed.isBlank()) PhoneRow.CONTENT_URI else Uri.withAppendedPath(PhoneRow.CONTENT_FILTER_URI, Uri.encode(typed.trim()))
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

    /** One's own contact, as the configuration shows it: its name, picture, numbers and certificate. */
    class Own(val contact: Long, val key: String, val name: String, val photo: Uri?, val numbers: List<String>, val fingerprint: String?)

    /** The contact a lookup key names, whatever its number in the address book has become since. */
    fun own(context: Context, key: String): Own? = runCatching {
        // The phone's own profile goes by a key of its own, and lives apart from the address book.
        val asked = if (key == PROFILE) ContactsContract.Profile.CONTENT_URI else Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, key)
        context.contentResolver.query(
            asked, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI, ContactsContract.Contacts.LOOKUP_KEY),
            null, null, null,
        )?.use { rows ->
            if (!rows.moveToFirst()) return@use null
            val contact = rows.getLong(0)
            val numbers = ArrayList<String>()
            for (one in values(context, contact, PhoneRow.CONTENT_ITEM_TYPE, PhoneRow.NUMBER)) if (numbers.none { Phone.same(it, one) || it == one }) numbers += one
            Own(contact, rows.getString(3) ?: key, rows.getString(1).orEmpty(), rows.getString(2)?.let(Uri::parse), numbers, fingerprint(context, contact))
        }
    }.getOrNull()

    /** The lookup key Android gives the phone's own profile -- "Me", "My profile" in the contacts applications. */
    const val PROFILE = "profile"

    /** The phone's own profile, when its contacts application has one and Android shows it: its name, picture, numbers. */
    fun profile(context: Context): Own? = own(context, PROFILE)

    /**
     * Every contact that carries one of [numbers], the phone's own profile
     * among them when Android shows it: those one's own contact may be. In
     * the order of the numbers, each contact once.
     */
    fun carrying(context: Context, numbers: List<String>): List<Own> {
        val keys = LinkedHashSet<String>()
        val profile = profile(context)
        for (number in numbers) {
            if (profile != null && profile.numbers.any { Phone.same(it, number) }) keys += profile.key
            runCatching {
                context.contentResolver.query(
                    Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                    arrayOf(ContactsContract.PhoneLookup.LOOKUP_KEY), null, null, null,
                )?.use { rows -> while (rows.moveToNext()) rows.getString(0)?.let { keys += it } }
            }
        }
        return keys.mapNotNull { own(context, it) }
    }

    /** The lookup key of a contact handed back by the address book: picked, or made. */
    fun keyOf(context: Context, contact: Uri): String? = runCatching {
        context.contentResolver.query(contact, arrayOf(ContactsContract.Contacts.LOOKUP_KEY), null, null, null)?.use { rows ->
            if (rows.moveToFirst()) rows.getString(0) else null
        }
    }.getOrNull()

    /** The lookup key of the contact tied to the certificate [fingerprint], if one is: the phone's own profile first. */
    fun tiedTo(context: Context, fingerprint: String): String? = profile(context)?.takeIf { it.fingerprint.equals(fingerprint, ignoreCase = true) }?.key ?: runCatching {
        context.contentResolver.query(
            Data.CONTENT_URI, arrayOf(Data.LOOKUP_KEY, Website.URL), "${Data.MIMETYPE} = ?", arrayOf(Website.CONTENT_ITEM_TYPE), null,
        )?.use { rows ->
            while (rows.moveToNext()) if (Fingerprint.of(rows.getString(1).orEmpty()).equals(fingerprint, ignoreCase = true)) return@use rows.getString(0)
            null
        }
    }.getOrNull()

    private fun fingerprint(context: Context, contact: Long): String? =
        values(context, contact, Website.CONTENT_ITEM_TYPE, Website.URL).firstNotNullOfOrNull { Fingerprint.of(it) }

    /**
     * Where a contact's rows are: the address book's, or for the phone's own
     * profile ("Me", "My profile"), the profile's, which Android keeps apart.
     */
    private fun dataOf(contact: Long): Uri =
        if (ContactsContract.isProfileId(contact)) Uri.withAppendedPath(ContactsContract.Profile.CONTENT_URI, "data") else Data.CONTENT_URI

    private fun rawsOf(contact: Long): Uri =
        if (ContactsContract.isProfileId(contact)) ContactsContract.Profile.CONTENT_RAW_CONTACTS_URI else RawContacts.CONTENT_URI

    private fun values(context: Context, contact: Long, kind: String, column: String): List<String> {
        val found = ArrayList<String>()
        context.contentResolver.query(
            dataOf(contact), arrayOf(column), "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
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
    /** Which of two to keep, when the contact and the certificate say otherwise: the contact's or the certificate's. */
    enum class Pick { CONTACT, CERTIFICATE }

    /** [contact]: the one to write in, when it is known -- the person's own --, rather than whoever the number finds. */
    fun tie(
        context: Context, number: String, card: Card, address: String, stale: List<Stale> = emptyList(),
        face: Pick = Pick.CONTACT, note: Pick = Pick.CONTACT, contact: Long? = null, name: Pick = Pick.CONTACT,
    ) {
        written++
        val known = known(context, number, contact)
        val raw = known?.let { rawOf(context, it.contact) } ?: create(context, number, card.name ?: number)
        val contact = known?.contact ?: contactOf(context, raw)
        val resolver = context.contentResolver

        // What an earlier certificate said and this one no longer does: gone first.
        for (old in stale) resolver.delete(android.content.ContentUris.withAppendedId(Data.CONTENT_URI, old.row), null, null)

        // One certificate a contact: the address of another is replaced, not kept beside.
        val former = values(context, contact, Website.CONTENT_ITEM_TYPE, Website.URL).filter { Fingerprint.of(it) != null }
        for (old in former) {
            resolver.delete(
                dataOf(contact), "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${Website.URL} = ?",
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
            missing(context, contact, kind, column, value, same)

        row(Website.CONTENT_ITEM_TYPE, Website.URL, address) { put(Website.TYPE, Website.TYPE_CUSTOM) ; put(Website.LABEL, MARK) }
        for (email in card.emails) {
            if (missing(Email.CONTENT_ITEM_TYPE, Email.ADDRESS, email) { a, b -> a.equals(b, true) }) {
                row(Email.CONTENT_ITEM_TYPE, Email.ADDRESS, email) { put(Email.TYPE, Email.TYPE_CUSTOM) ; put(Email.LABEL, MARK) }
            }
        }
        for (phone in card.phones.mapNotNull { Phone.plain(it) }) {
            if (!Phone.same(phone, number) && missing(PhoneRow.CONTENT_ITEM_TYPE, PhoneRow.NUMBER, phone, Phone::same)) {
                row(PhoneRow.CONTENT_ITEM_TYPE, PhoneRow.NUMBER, phone) { put(PhoneRow.TYPE, PhoneRow.TYPE_CUSTOM) ; put(PhoneRow.LABEL, MARK) }
            }
        }
        for (postal in card.addresses) {
            if (missing(StructuredPostal.CONTENT_ITEM_TYPE, StructuredPostal.FORMATTED_ADDRESS, postal)) {
                row(StructuredPostal.CONTENT_ITEM_TYPE, StructuredPostal.FORMATTED_ADDRESS, postal) {
                    put(StructuredPostal.TYPE, StructuredPostal.TYPE_CUSTOM) ; put(StructuredPostal.LABEL, MARK)
                }
            }
        }
        for (site in card.websites) {
            if (missing(Website.CONTENT_ITEM_TYPE, Website.URL, site)) {
                row(Website.CONTENT_ITEM_TYPE, Website.URL, site) { put(Website.TYPE, Website.TYPE_CUSTOM) ; put(Website.LABEL, MARK) }
            }
        }
        // The certificate's note: written where the contact has none; where it has its own, in its place if
        // the person chose so. One note, in one row: the phones' contacts show but one.
        val notes = values(context, contact, Note.CONTENT_ITEM_TYPE, Note.NOTE).filter { it.isNotBlank() }
        if (card.notes.isNotEmpty() && (notes.isEmpty() || note == Pick.CERTIFICATE)) {
            if (notes.isNotEmpty()) gone(context, contact, Note.CONTENT_ITEM_TYPE)
            row(Note.CONTENT_ITEM_TYPE, Note.NOTE, card.notes.joinToString("\n\n"))
        }
        // The certificate's name, in place of the contact's when the person chose so: written over the name rows,
        // their parts emptied for the address book to cut the new name anew -- given name, family name.
        val named = card.name
        if (name == Pick.CERTIFICATE && !named.isNullOrBlank()) {
            val parts = ContentValues().apply {
                put(StructuredName.DISPLAY_NAME, named)
                for (part in listOf(
                    StructuredName.GIVEN_NAME, StructuredName.FAMILY_NAME, StructuredName.MIDDLE_NAME, StructuredName.PREFIX, StructuredName.SUFFIX,
                    StructuredName.PHONETIC_GIVEN_NAME, StructuredName.PHONETIC_FAMILY_NAME, StructuredName.PHONETIC_MIDDLE_NAME,
                )) putNull(part)
            }
            val changed = resolver.update(
                dataOf(contact), parts, "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?", arrayOf(contact.toString(), StructuredName.CONTENT_ITEM_TYPE),
            )
            if (changed == 0) row(StructuredName.CONTENT_ITEM_TYPE, StructuredName.DISPLAY_NAME, named)
        }
        // The certificate's face: where the contact has none, or in place of its own when the person chose so.
        val picture = card.photo
        if (picture != null && (known?.photo == null || face == Pick.CERTIFICATE)) {
            if (known?.photo != null) gone(context, contact, Photo.CONTENT_ITEM_TYPE)
            resolver.insert(
                Data.CONTENT_URI,
                ContentValues().apply {
                    put(Data.RAW_CONTACT_ID, raw) ; put(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE) ; put(Photo.PHOTO, picture)
                    // The one shown, of all the contact's.
                    put(Data.IS_PRIMARY, 1) ; put(Data.IS_SUPER_PRIMARY, 1)
                },
            )
        }
    }

    /** Every row of a [kind] the contact holds, gone. */
    private fun gone(context: Context, contact: Long, kind: String) {
        context.contentResolver.delete(dataOf(contact), "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?", arrayOf(contact.toString(), kind))
    }

    /**
     * Where the contact and the certificate each have a face, or a note, and
     * they are not the same: the contact's, to set beside the certificate's.
     * Faces are compared as a person would ([Likeness]), notes word for word.
     */
    /** [name]: the contact's, when the certificate gives another. */
    class Differs(val face: android.graphics.Bitmap?, val notes: List<String>, val name: String? = null)

    fun differs(context: Context, number: String, card: Card, contact: Long? = null): Differs {
        val known = known(context, number, contact) ?: return Differs(null, emptyList())
        val theirs = card.photo
        val face = if (theirs == null) null else face(context, known)?.takeIf { !Likeness.alike(it, theirs) }
        // The contact's notes, each as it is -- a contact may hold several --, when they are not the certificate's.
        val mine = values(context, known.contact, Note.CONTENT_ITEM_TYPE, Note.NOTE).filter { it.isNotBlank() }
        val said = card.notes.joinToString("\n\n")
        val notes = mine.takeIf { it.isNotEmpty() && said.isNotBlank() && it.joinToString("\n\n").trim() != said.trim() }.orEmpty()
        // Names compared as read: case and spaces aside.
        fun read(name: String) = name.trim().replace(Regex("\\s+"), " ").lowercase()
        val name = known.name.takeIf { theirs -> card.name?.isNotBlank() == true && read(theirs) != read(card.name!!) }
        return Differs(face, notes, name)
    }

    /**
     * Whether [tie] would write anything the contact of [number] does not
     * say already: the certificate's address, or a line of its card, or its
     * face. Nothing, and there is nothing to ask the person.
     */
    fun lacks(context: Context, number: String, card: Card, address: String, contact: Long? = null): Boolean {
        val known = known(context, number, contact) ?: return true
        fun missing(kind: String, column: String, value: String, same: (String, String) -> Boolean = { a, b -> a == b }) =
            missing(context, known.contact, kind, column, value, same)
        return missing(Website.CONTENT_ITEM_TYPE, Website.URL, address) ||
            card.emails.any { email -> missing(Email.CONTENT_ITEM_TYPE, Email.ADDRESS, email) { a, b -> a.equals(b, true) } } ||
            card.phones.mapNotNull { Phone.plain(it) }.any { !Phone.same(it, number) && missing(PhoneRow.CONTENT_ITEM_TYPE, PhoneRow.NUMBER, it, Phone::same) } ||
            card.addresses.any { missing(StructuredPostal.CONTENT_ITEM_TYPE, StructuredPostal.FORMATTED_ADDRESS, it) } ||
            card.websites.any { missing(Website.CONTENT_ITEM_TYPE, Website.URL, it) } ||
            // A note where the contact has none: one beside its own is a choice, asked by [differs].
            (card.notes.isNotEmpty() && values(context, known.contact, Note.CONTENT_ITEM_TYPE, Note.NOTE).none { it.isNotBlank() }) ||
            (card.photo != null && known.photo == null)
    }

    /**
     * The label on what foomms writes from a certificate -- addresses,
     * numbers, sites --, as on the certificate's own address: what it may
     * take away again once the certificate no longer says it. A line without
     * it is the person's, and is never touched.
     */
    const val MARK = "PGP"

    /** A line foomms wrote from a certificate, by its row, and what it says. */
    class Stale(val row: Long, val value: String)

    /**
     * The lines foomms wrote from a certificate that [card] no longer
     * carries: revoked in it, or taken off. Marked ones only; the
     * certificate's own address is replaced by [tie], never counted here.
     */
    fun stale(context: Context, number: String, card: Card, contact: Long? = null): List<Stale> {
        val known = known(context, number, contact) ?: return emptyList()
        val found = ArrayList<Stale>()
        fun look(kind: String, column: String, carried: List<String>, kept: (String) -> Boolean = { false }, same: (String, String) -> Boolean = { a, b -> a == b }) {
            // TYPE and LABEL are DATA2 and DATA3 in every kind of row: a custom type, labelled with the mark.
            context.contentResolver.query(
                dataOf(known.contact), arrayOf(Data._ID, column),
                "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${Data.DATA2} = ? AND ${Data.DATA3} = ?",
                arrayOf(known.contact.toString(), kind, ContactsContract.CommonDataKinds.BaseTypes.TYPE_CUSTOM.toString(), MARK), null,
            )?.use { rows ->
                while (rows.moveToNext()) {
                    val value = rows.getString(1) ?: continue
                    if (!kept(value) && carried.none { same(it, value) }) found += Stale(rows.getLong(0), value)
                }
            }
        }
        look(Email.CONTENT_ITEM_TYPE, Email.ADDRESS, card.emails) { a, b -> a.equals(b, true) }
        look(PhoneRow.CONTENT_ITEM_TYPE, PhoneRow.NUMBER, card.phones.mapNotNull { Phone.plain(it) }, same = Phone::same)
        look(StructuredPostal.CONTENT_ITEM_TYPE, StructuredPostal.FORMATTED_ADDRESS, card.addresses)
        // The certificate's own address is not one of its sites: it is replaced by [tie], never stale.
        look(Website.CONTENT_ITEM_TYPE, Website.URL, card.websites, kept = { Fingerprint.of(it) != null })
        return found
    }

    private fun missing(context: Context, contact: Long, kind: String, column: String, value: String, same: (String, String) -> Boolean) =
        values(context, contact, kind, column).none { same(it, value) }

    private fun rawOf(context: Context, contact: Long): Long? =
        context.contentResolver.query(
            rawsOf(contact), arrayOf(RawContacts._ID), "${RawContacts.CONTACT_ID} = ?", arrayOf(contact.toString()), null,
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
