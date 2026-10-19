package org.foopgp.foomms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

/**
 * What a message carries beside its words, made ready to travel: a network
 * takes a few hundred kilobytes and no more, and nothing can shrink a picture
 * once it is locked -- so it is shrunk here, before.
 */
object Media {

    /** [millis]: how long a sound lasts, when it is known. */
    class Attachment(val type: String, val name: String, val bytes: ByteArray, val millis: Long? = null)

    /**
     * The name of a recording: what it is -- `a` for a sound alone, `v` for a
     * video --, and when it began, in ISO 8601's basic form and in UTC:
     * `a20261019T145503Z.3gp`. Sorted by name, recordings sort by time, and
     * two from different phones do not take each other's name.
     */
    fun named(kind: String, at: Long, extension: String): String =
        kind + java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(at)) + "." + extension

    /** A length of time as a clock says it: 0:07, 12:45, 3:05:00. */
    fun clock(millis: Long): String =
        if (millis >= 3_600_000) "%d:%02d:%02d".format(millis / 3_600_000, millis / 60_000 % 60, millis / 1000 % 60)
        else "%d:%02d".format(millis / 60_000, millis / 1000 % 60)

    /** How long a sound lasts, as it came. */
    fun lasting(context: Context, uri: Uri): Long? = lasting { it.setDataSource(context, uri) }

    /** How long a sound lasts, read where it is held: what was opened from its lock stays in memory. */
    fun lasting(bytes: ByteArray): Long? = lasting {
        it.setDataSource(object : android.media.MediaDataSource() {
            override fun readAt(at: Long, into: ByteArray, offset: Int, size: Int): Int {
                if (at >= bytes.size) return -1
                val n = minOf(size.toLong(), bytes.size - at).toInt()
                System.arraycopy(bytes, at.toInt(), into, offset, n)
                return n
            }
            override fun getSize() = bytes.size.toLong()
            override fun close() {}
        })
    }

    private fun lasting(source: (android.media.MediaMetadataRetriever) -> Unit): Long? = runCatching {
        val reader = android.media.MediaMetadataRetriever()
        try {
            source(reader)
            reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally { reader.release() }
    }.getOrNull()

    /** How many bytes a part of a message weighs: the phone's store does not say, it is counted. */
    fun weight(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) { val n = input.read(buffer) ; if (n < 0) break ; total += n }
            total
        }
    }.getOrNull() ?: 0L

    /**
     * A picture, as JPEG, no heavier than [most]: made smaller, then lighter,
     * until it fits. Null when it is not a picture one can read.
     */
    fun picture(context: Context, from: Uri, most: Int): Attachment? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(from)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2 * LONGEST) sample *= 2
        var bitmap = context.contentResolver.openInputStream(from)
            ?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        var longest = LONGEST
        while (true) {
            val scale = longest.toFloat() / maxOf(bitmap.width, bitmap.height)
            if (scale < 1f) bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            for (quality in listOf(85, 70, 55)) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= most) return Attachment("image/jpeg", "photo.jpg", out.toByteArray())
            }
            if (longest <= 320) return null
            longest = longest * 3 / 4
        }
    }

    /** A file as it is -- a sound, most often --, or null when it is heavier than [most]. */
    fun file(context: Context, from: Uri, most: Int): Attachment? {
        val bytes = context.contentResolver.openInputStream(from)?.use { it.readBytes() } ?: return null
        if (bytes.size > most) return null
        val type = context.contentResolver.getType(from) ?: "application/octet-stream"
        val name = runCatching {
            context.contentResolver.query(from, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { rows ->
                if (rows.moveToFirst()) rows.getString(0) else null
            }
        }.getOrNull() ?: from.lastPathSegment?.takeIf { '.' in it } ?: ("file." + type.substringAfter('/').substringBefore(';'))
        return Attachment(type, name.filter { it.code in 32..126 && it !in "\"\\/" }.ifEmpty { "file" }, bytes)
    }

    /** A contact of the address book, as the vCard the phone itself writes of it. */
    fun contact(context: Context, chosen: Uri, most: Int): Attachment? {
        val (key, name) = context.contentResolver.query(
            chosen, arrayOf(android.provider.ContactsContract.Contacts.LOOKUP_KEY, android.provider.ContactsContract.Contacts.DISPLAY_NAME), null, null, null,
        )?.use { rows -> if (rows.moveToFirst()) rows.getString(0) to (rows.getString(1) ?: "contact") else null } ?: return null
        val card = Uri.withAppendedPath(android.provider.ContactsContract.Contacts.CONTENT_VCARD_URI, key)
        val bytes = context.contentResolver.openInputStream(card)?.use { it.readBytes() } ?: return null
        if (bytes.size > most) return null
        val file = name.filter { it.isLetterOrDigit() || it == ' ' }.trim().replace(' ', '_').ifEmpty { "contact" }
        return Attachment("text/x-vcard", "$file.vcf", bytes)
    }

    /** A picture small enough to sit in a bubble, or null. */
    fun thumbnail(context: Context, from: Uri, side: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(from)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2 * side) sample *= 2
        context.contentResolver.openInputStream(from)
            ?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }.getOrNull()

    /** The same, of a picture held in memory: decoded at about the size it is shown, never whole. */
    fun thumbnail(bytes: ByteArray, side: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2 * side) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** The side faces are decoded for, in pixels: the largest they are drawn, in a choice between two. */
    const val FACE = 256

    /** The longest side a picture leaves with: enough for a phone's screen. */
    private const val LONGEST = 1600
}
