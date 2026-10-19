package org.foopgp.foomms

import android.graphics.Bitmap

/**
 * Whether two pictures are the same picture, as a person would say: the one
 * a contact holds has been cropped, shrunk and compressed again by Android,
 * and is never the certificate's byte for byte. Each is told by a
 * difference hash -- the square at its centre, in grey, nine by eight, each
 * point lighter or not than the one to its right: 64 bits that keep the
 * shape of the picture and lose its exact colours.
 */
object Likeness {

    /** How many of the 64 bits may differ between two copies of one picture. */
    private const val MARGIN = 10

    fun alike(mine: Bitmap, theirs: ByteArray): Boolean {
        // Nine points by eight need no more than a few dozen pixels a side.
        val other = Media.thumbnail(theirs, 64) ?: return false
        return java.lang.Long.bitCount(hash(mine) xor hash(other)) <= MARGIN
    }

    fun hash(picture: Bitmap): Long {
        val side = minOf(picture.width, picture.height)
        val square = Bitmap.createBitmap(picture, (picture.width - side) / 2, (picture.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(square, 9, 8, true)
        var bits = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                bits = bits shl 1
                if (grey(small.getPixel(x, y)) > grey(small.getPixel(x + 1, y))) bits = bits or 1L
            }
        }
        return bits
    }

    private fun grey(pixel: Int): Int =
        (299 * ((pixel shr 16) and 0xFF) + 587 * ((pixel shr 8) and 0xFF) + 114 * (pixel and 0xFF)) / 1000
}
