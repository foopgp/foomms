package org.foopgp.foomms

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * A page that asks foodjis for something, and shows the screens foodjis
 * wants shown before it answers: may this application ask, whose key is
 * this, present the card. After each, the same question is asked again with
 * what the screen answered.
 */
abstract class AskingActivity : Activity() {

    protected val provider by lazy { Provider(this) }

    /** Left by the top, as every page but the list. */
    private val swipes by lazy { Swipes.watch(this) }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean = swipes.feed(event) || super.dispatchTouchEvent(event)

    private class Errand(
        val request: Intent,
        val input: ByteArray?,
        val failed: (String) -> Unit,
        val shown: (Intent?) -> Boolean,
        val refused: () -> Unit,
        val done: (Provider.Answer.Done) -> Unit,
    )

    private var errand: Errand? = null
    private var shown = 0

    /**
     * What the person writes with, out of sight while a screen of foodjis is
     * over the page: the PIN pad leaves it showing above its keys, and its
     * send button is not the screen's OK.
     */
    protected var writing: android.view.View? = null

    private fun screening(on: Boolean) {
        writing?.visibility = if (on) android.view.View.GONE else android.view.View.VISIBLE
    }

    /**
     * [screened] is told what a screen of foodjis answered, and says whether
     * that was the whole answer: a list to choose from answers by itself, a
     * question of consent only clears the way for asking again.
     */
    protected fun ask(
        request: Intent,
        input: ByteArray?,
        failed: (String) -> Unit,
        screened: (Intent?) -> Boolean = { false },
        refused: () -> Unit = {},
        done: (Provider.Answer.Done) -> Unit,
    ) {
        shown = 0
        errand = Errand(request, input, failed, screened, refused, done)
        again(null)
    }

    private fun again(carried: Bundle?) {
        val errand = errand ?: return
        // A request of its own each time: the provider reads it once.
        val request = Intent(errand.request)
        carried?.let { request.putExtras(it) }
        provider.ask(request, errand.input) { answer ->
            if (isFinishing || isDestroyed) return@ask
            when (answer) {
                is Provider.Answer.Done -> { this.errand = null ; errand.done(answer) }
                // Refused by foodjis itself -- not given up by the person in front of one of its screens.
                is Provider.Answer.Failed -> { this.errand = null ; errand.refused() ; errand.failed(getString(R.string.failed, answer.says)) }
                is Provider.Answer.Ask ->
                    if (++shown > MOST) {
                        this.errand = null
                        errand.failed(getString(R.string.failed, "too many questions"))
                    } else {
                        screening(true)
                        runCatching { startIntentSenderForResult(answer.screen.intentSender, SCREEN, null, 0, 0, 0) }
                            .onFailure { screening(false) ; this.errand = null ; errand.failed(getString(R.string.failed, it.message)) }
                    }
            }
        }
    }

    override fun onActivityResult(request: Int, outcome: Int, data: Intent?) {
        super.onActivityResult(request, outcome, data)
        if (request != SCREEN) return
        screening(false)
        if (outcome == RESULT_OK) {
            if (errand?.shown?.invoke(data) == true) { errand = null ; return }
            return again(data?.extras)
        }
        errand?.failed?.invoke(getString(R.string.given_up))
        errand = null
    }

    /**
     * Encrypting could be done and [locks] is off: asked before the message
     * leaves readable, with the way to encrypt it after all. [go] sends it,
     * told that it was asked.
     */
    protected fun plainOrAsk(locks: Flip, repaint: () -> Unit, go: () -> Unit): Boolean {
        if (!locks.isEnabled || locks.isChecked) return false
        android.app.AlertDialog.Builder(this)
            .setMessage(R.string.plain_ask)
            .setPositiveButton(R.string.plain_send) { _, _ -> go() }
            .setNeutralButton(R.string.plain_lock) { _, _ -> locks.isChecked = true ; repaint() ; go() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        return true
    }

    override fun onDestroy() {
        provider.close()
        super.onDestroy()
    }

    private companion object {
        const val SCREEN = 1

        /** Consent, a certificate to fetch, an identity to set up, the card: no errand has more questions than that. */
        const val MOST = 5
    }
}
