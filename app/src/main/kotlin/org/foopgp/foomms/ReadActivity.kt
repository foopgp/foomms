package org.foopgp.foomms

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.concurrent.thread
import org.openintents.openpgp.OpenPgpSignatureResult

/**
 * One received message, opened by foodjis with the card: its words, who
 * signed them, and whether the number it came from is one the signer's
 * certificate carries. Shown, and kept nowhere.
 */
class ReadActivity : AskingActivity() {

    private lateinit var column: LinearLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = getString(R.string.read_title)
        column = Look.column(this)
        setContentView(ScrollView(this).apply { addView(column) })
        val part = intent.getLongExtra(EXTRA_PART, -1)
        thread {
            val message = Inbox.bytes(this, part)
            runOnUiThread { if (message == null) tell(getString(R.string.gone)) else open(message) }
        }
    }

    private fun open(message: ByteArray) {
        ask(Intent(Provider.DECRYPT_VERIFY), message, ::tell) { answer ->
            answer.says.setExtrasClassLoader(OpenPgpSignatureResult::class.java.classLoader)
            @Suppress("DEPRECATION")
            val signature = answer.says.getParcelableExtra<OpenPgpSignatureResult>(Provider.RESULT_SIGNATURE)
            column.removeAllViews()
            column.addView(Look.words(this, signed(signature), size = 14f, muted = true))
            from(signature)?.let { column.addView(Look.words(this, it, size = 14f, muted = true)) }
            column.addView(Look.words(this, String(answer.bytes, Charsets.UTF_8), size = 18f))
            intent.getStringExtra(EXTRA_NUMBER)?.let { number ->
                column.addView(
                    Look.button(this, R.string.answer) {
                        startActivity(Intent(this, WriteActivity::class.java).putExtra(WriteActivity.EXTRA_NUMBER, number))
                    },
                )
            }
        }
    }

    private fun signed(signature: OpenPgpSignatureResult?): String = when (signature?.result) {
        null, OpenPgpSignatureResult.RESULT_NO_SIGNATURE -> getString(R.string.unsigned)
        OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED -> getString(R.string.signed_certified, Signer.named(signature))
        OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED -> getString(R.string.signed_other, Signer.named(signature))
        OpenPgpSignatureResult.RESULT_KEY_MISSING -> getString(R.string.signed_unknown)
        else -> getString(R.string.signed_broken)
    }

    /**
     * A network says what number a message came from, and can be made to say
     * anything; a signature says who wrote. The two agree when the signer's
     * certificate carries that number.
     */
    private fun from(signature: OpenPgpSignatureResult?): String? {
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: return null
        val holds = signature?.result in VALID
        if (!holds) return getString(R.string.number_alone, number)
        val carried = Phone.carried(signature?.userIds.orEmpty() + listOfNotNull(signature?.primaryUserId))
        return if (carried.any { Phone.same(it, number) }) getString(R.string.number_theirs, number)
        else getString(R.string.number_not_theirs, number)
    }

    private fun tell(words: String) {
        column.removeAllViews()
        column.addView(Look.words(this, words))
    }

    companion object {
        const val EXTRA_PART = "part"
        const val EXTRA_NUMBER = "number"
        private val VALID = setOf(
            OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED,
            OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED,
        )
    }
}
