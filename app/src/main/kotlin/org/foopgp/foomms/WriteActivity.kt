package org.foopgp.foomms

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast

/**
 * A message to one number: foodjis locks it for the certificate that carries
 * the number -- and signs it, with the card, when asked to --, and what
 * leaves the phone is an MMS with that locked message as its attachment.
 *
 * The number is at once where the message goes and whose key it is locked
 * for: there is no choosing a key that is not the number's.
 */
class WriteActivity : AskingActivity() {

    private lateinit var number: EditText
    private lateinit var words: EditText

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = getString(R.string.write_title)
        val column = Look.column(this)
        number = EditText(this).apply {
            setHint(R.string.number_hint)
            inputType = InputType.TYPE_CLASS_PHONE
            setText(intent.getStringExtra(EXTRA_NUMBER).orEmpty())
        }
        words = EditText(this).apply {
            setHint(R.string.words_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            gravity = Gravity.TOP
        }
        column.addView(number)
        column.addView(words)
        column.addView(Look.button(this, R.string.sign_encrypt_send) { seal(signed = true) })
        column.addView(Look.button(this, R.string.encrypt_send) { seal(signed = false) })
        column.addView(Look.words(this, getString(R.string.write_about), size = 14f, muted = true))
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun seal(signed: Boolean) {
        val to = Phone.plain(number.text.toString()) ?: return say(getString(R.string.not_a_number))
        val written = words.text.toString()
        if (written.isBlank()) return say(getString(R.string.nothing))
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 1)
        }
        // Not armored: an MMS carries bytes as they are, and armor would weigh a third more for nothing.
        val request = Intent(if (signed) Provider.SIGN_AND_ENCRYPT else Provider.ENCRYPT)
            .putExtra(Provider.EXTRA_USER_IDS, arrayOf("tel:$to"))
            .putExtra(Provider.EXTRA_REQUEST_ASCII_ARMOR, false)
        ask(request, written.toByteArray(), ::say) { answer -> send(to, answer.bytes) }
    }

    private fun send(to: String, message: ByteArray) {
        if (!Pgp.isMessage(message)) return say(getString(R.string.failed, "what came back is not a PGP message"))
        val transaction = "T" + java.lang.Long.toHexString(System.currentTimeMillis())
        val pdu = Pdu.send(
            transaction, listOf(to),
            // A line in clear first, for whoever receives this without knowing what the attachment is.
            listOf(Pdu.Part.text(getString(R.string.notice, Pgp.NAME)), Pdu.Part(Pgp.TYPE, Pgp.NAME, message)),
        )
        val most = Outbox.heaviest(this)
        if (pdu.size > most) return say(getString(R.string.too_heavy, pdu.size / 1024 + 1, most / 1024))
        runCatching { Outbox.send(this, to, pdu, transaction) }
            .onFailure { return say(getString(R.string.failed, it.message)) }
        Toast.makeText(this, getString(R.string.handed_over, pdu.size / 1024 + 1), Toast.LENGTH_LONG).show()
        finish()
    }

    private fun say(words: String) {
        AlertDialog.Builder(this).setMessage(words).setPositiveButton(R.string.close, null).show()
    }

    companion object {
        const val EXTRA_NUMBER = "number"
    }
}
