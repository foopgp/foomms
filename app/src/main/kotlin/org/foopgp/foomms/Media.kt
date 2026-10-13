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

    class Attachment(val type: String, val name: String, val bytes: ByteArray)

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
        }.getOrNull() ?: ("sound." + type.substringAfter('/').substringBefore(';'))
        return Attachment(type, name.filter { it.code in 32..126 && it !in "\"\\/" }.ifEmpty { "file" }, bytes)
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

    /** The longest side a picture leaves with: enough for a phone's screen. */
    private const val LONGEST = 1600
}
