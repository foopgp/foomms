package org.foopgp.foomms

/**
 * What a file is, told from its first bytes: a network may relabel what an
 * MMS carries, and a file that was locked comes back with no label at all.
 * The label is trusted only when the bytes say nothing.
 */
object Kind {

    class Told(val type: String, val extension: String)

    fun of(start: ByteArray, labelled: String?): Told {
        fun at(offset: Int, vararg bytes: Int) = bytes.indices.all { start.getOrNull(offset + it)?.toInt()?.and(0xFF) == bytes[it] }
        fun word(offset: Int, word: String) = at(offset, *word.map { it.code }.toIntArray())
        return when {
            at(0, 0xFF, 0xD8, 0xFF) -> Told("image/jpeg", "jpg")
            at(0, 0x89) && word(1, "PNG") -> Told("image/png", "png")
            word(0, "GIF8") -> Told("image/gif", "gif")
            word(0, "RIFF") && word(8, "WEBP") -> Told("image/webp", "webp")
            word(0, "RIFF") && word(8, "WAVE") -> Told("audio/wav", "wav")
            word(0, "OggS") -> Told("audio/ogg", "ogg")
            word(0, "ID3") || at(0, 0xFF, 0xFB) -> Told("audio/mpeg", "mp3")
            word(0, "#!AMR-WB") -> Told("audio/amr-wb", "awb")
            word(0, "#!AMR") -> Told("audio/amr", "amr")
            // AAC as it is (ADTS): twelve bits set, then a layer of 00 -- an MP3's is not.
            at(0, 0xFF) && (start.getOrNull(1)?.toInt()?.and(0xF6)) == 0xF0 -> Told("audio/aac", "aac")
            word(4, "ftyp") && (word(8, "M4A") || word(8, "mp42") && labelled?.startsWith("audio/") == true) -> Told("audio/mp4", "m4a")
            word(4, "ftyp") && word(8, "3gp") -> Told(if (labelled?.startsWith("audio/") == true) "audio/3gpp" else "video/3gpp", "3gp")
            word(4, "ftyp") -> Told("video/mp4", "mp4")
            word(0, "%PDF") -> Told("application/pdf", "pdf")
            word(0, "BEGIN:VCARD") -> Told("text/x-vcard", "vcf")
            Pgp.isMessage(start) -> Told(Pgp.TYPE, "pgp")
            !labelled.isNullOrBlank() -> Told(labelled.substringBefore(';').trim(), labelled.substringAfter('/').substringBefore(';').filter { it.isLetterOrDigit() }.take(5).ifEmpty { "bin" })
            else -> Told("application/octet-stream", "bin")
        }
    }
}
