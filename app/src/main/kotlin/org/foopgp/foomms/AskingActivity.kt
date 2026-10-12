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

    private class Errand(
        val request: Intent,
        val input: ByteArray?,
        val failed: (String) -> Unit,
        val done: (Provider.Answer.Done) -> Unit,
    )

    private var errand: Errand? = null
    private var shown = 0

    protected fun ask(request: Intent, input: ByteArray?, failed: (String) -> Unit, done: (Provider.Answer.Done) -> Unit) {
        shown = 0
        errand = Errand(request, input, failed, done)
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
                is Provider.Answer.Failed -> { this.errand = null ; errand.failed(getString(R.string.failed, answer.says)) }
                is Provider.Answer.Ask ->
                    if (++shown > MOST) {
                        this.errand = null
                        errand.failed(getString(R.string.failed, "too many questions"))
                    } else {
                        runCatching { startIntentSenderForResult(answer.screen.intentSender, SCREEN, null, 0, 0, 0) }
                            .onFailure { this.errand = null ; errand.failed(getString(R.string.failed, it.message)) }
                    }
            }
        }
    }

    override fun onActivityResult(request: Int, outcome: Int, data: Intent?) {
        super.onActivityResult(request, outcome, data)
        if (request != SCREEN) return
        if (outcome == RESULT_OK) return again(data?.extras)
        errand?.failed?.invoke(getString(R.string.given_up))
        errand = null
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
