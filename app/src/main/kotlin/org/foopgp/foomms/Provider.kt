package org.foopgp.foomms

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.openintents.openpgp.IOpenPgpService2
import org.openintents.openpgp.OpenPgpError

/**
 * foodjis, as this application knows it: an OpenPGP provider behind the
 * service interface mail applications already speak.
 *
 * One asks with an Intent -- an action, and who or what it is about --, sends
 * bytes down one pipe and reads the answer from another. There are three
 * answers: done, refused, or "show this screen of mine first and ask again",
 * which is how the provider gets the person in front of its own questions:
 * whom to lock for, whether to let us in, the card to present.
 */
class Provider(private val context: Context) {

    sealed interface Answer {
        /** Done: what came out of the pipe, and what the provider says of it. */
        class Done(val bytes: ByteArray, val says: Intent) : Answer

        /** A screen of the provider's is to be shown, and the question asked again. */
        class Ask(val screen: PendingIntent) : Answer

        class Failed(val says: String) : Answer
    }

    private val main = Handler(Looper.getMainLooper())
    private var door: IOpenPgpService2? = null
    private var bond: ServiceConnection? = null

    private fun whom(): ComponentName? = whom(context)

    private fun bound(then: (IOpenPgpService2?) -> Unit) {
        door?.let { return then(it) }
        val to = whom() ?: return then(null)
        val link = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                door = IOpenPgpService2.Stub.asInterface(binder)
                then(door)
            }
            override fun onServiceDisconnected(name: ComponentName) { door = null }
        }
        bond = link
        if (!context.bindService(Intent(SERVICE).setComponent(to), link, Context.BIND_AUTO_CREATE)) then(null)
    }

    /** Ask, with [input] sent along; [then] is called on the main thread. */
    fun ask(request: Intent, input: ByteArray?, then: (Answer) -> Unit) {
        request.putExtra(EXTRA_API_VERSION, API_VERSION)
        bound { door ->
            if (door == null) return@bound then(Answer.Failed(context.getString(R.string.no_provider)))
            thread {
                val answer = runCatching { exchange(door, request, input) }
                    .getOrElse { Answer.Failed(it.message ?: it.toString()) }
                main.post { then(answer) }
            }
        }
    }

    private fun exchange(door: IOpenPgpService2, request: Intent, input: ByteArray?): Answer {
        val id = pipes.incrementAndGet()
        val out = ByteArrayOutputStream()
        val reading = door.createOutputPipe(id)?.let { from ->
            thread { ParcelFileDescriptor.AutoCloseInputStream(from).use { it.copyTo(out) } }
        }
        val sent = input?.let { bytes ->
            val pipe = ParcelFileDescriptor.createPipe()
            // foodjis stops reading once it has what it needs -- the head of a locked message, to find
            // that the card is wanted -- and what is left unwritten then has nowhere to go. That is an
            // answer on its way, not a failure; uncaught, it took the whole application down.
            thread { runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) } } }
            pipe[0]
        }
        val says = door.execute(request, sent, id)
        sent?.close()
        reading?.join(TIMEOUT)
        says.setExtrasClassLoader(OpenPgpError::class.java.classLoader)
        return when (says.getIntExtra(RESULT_CODE, RESULT_CODE_ERROR)) {
            RESULT_CODE_SUCCESS -> Answer.Done(out.toByteArray(), says)
            RESULT_CODE_USER_INTERACTION_REQUIRED ->
                @Suppress("DEPRECATION")
                says.getParcelableExtra<PendingIntent>(RESULT_INTENT)?.let { Answer.Ask(it) }
                    ?: Answer.Failed("a screen was announced and none was given")
            else ->
                @Suppress("DEPRECATION")
                Answer.Failed(says.getParcelableExtra<OpenPgpError>(RESULT_ERROR)?.message ?: "refused")
        }
    }

    fun close() {
        bond?.let { runCatching { context.unbindService(it) } }
        bond = null
        door = null
    }

    companion object {
        const val FOODJIS = "org.foopgp.foodjis"
        const val FOODJIS_DEV = "org.foopgp.foodjis.dev"

        /**
         * The foodjis this build speaks to, in the order it would rather: a
         * trial build of ours the trial foodjis first, and the published one
         * when that is the one on the phone; the published build, the
         * published one only.
         */
        private fun preferred(context: Context): List<String> =
            if (context.packageName.endsWith(".dev")) listOf(FOODJIS_DEV, FOODJIS) else listOf(FOODJIS)

        fun whom(context: Context): ComponentName? {
            val preferred = preferred(context)
            return context.packageManager.queryIntentServices(Intent(SERVICE), 0)
                .map { it.serviceInfo }
                .filter { it.packageName in preferred }
                .minByOrNull { preferred.indexOf(it.packageName) }
                ?.let { ComponentName(it.packageName, it.name) }
        }

        /** The package of the foodjis spoken to: the one found, or the one this build would rather have. */
        fun foodjis(context: Context): String = whom(context)?.packageName ?: preferred(context).first()

        /**
         * Nobody to speak to: the way to install foodjis, the published one,
         * which a trial build of ours speaks to as well -- the store's page,
         * or that page on the web when the phone has no store.
         */
        /**
         * The version of the foodjis spoken to, when it is older than the one
         * this build needs -- 1.4.3, the first to fetch a missing certificate
         * --, or null: none to speak to, or recent enough.
         */
        fun outdated(context: Context): String? {
            val found = whom(context)?.packageName ?: return null
            val held = runCatching { context.packageManager.getPackageInfo(found, 0) }.getOrNull() ?: return null
            return held.versionName?.takeIf { held.longVersionCode < NEEDED_CODE }
        }

        /** The first foodjis that answers all that this build asks of it. */
        const val NEEDED_CODE = 51L
        const val NEEDED_NAME = "1.4.3"

        fun install(context: Context) {
            val wanted = FOODJIS
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$wanted"))) }
                .recoverCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$wanted")))
                }
        }
        const val SERVICE = "org.openintents.openpgp.IOpenPgpService2"

        // The words of the interface, as org.openintents.openpgp.util.OpenPgpApi names them.
        const val API_VERSION = 12
        const val GET_KEY_IDS = "org.openintents.openpgp.action.GET_KEY_IDS"
        const val GET_KEY = "org.openintents.openpgp.action.GET_KEY"
        const val ENCRYPT = "org.openintents.openpgp.action.ENCRYPT"
        const val SIGN_AND_ENCRYPT = "org.openintents.openpgp.action.SIGN_AND_ENCRYPT"
        const val DECRYPT_VERIFY = "org.openintents.openpgp.action.DECRYPT_VERIFY"
        const val SIGN = "org.openintents.openpgp.action.SIGN"
        const val GET_SIGN_KEY_ID = "org.openintents.openpgp.action.GET_SIGN_KEY_ID"
        const val EXTRA_API_VERSION = "api_version"
        const val EXTRA_USER_IDS = "user_ids"
        const val EXTRA_KEY_IDS = "key_ids"
        const val EXTRA_KEY_ID = "key_id"

        /** foodjis's own, with GET_KEY: the certificate asked of the keyservers again, even when held. */
        const val EXTRA_REFRESH = "org.foopgp.foodjis.refresh"
        const val EXTRA_SIGN_KEY_ID = "sign_key_id"

        /** foodjis's own: one contact is wanted from its list, not several. */
        const val EXTRA_SINGLE = "org.foopgp.foodjis.extra.SINGLE"
        const val EXTRA_REQUEST_ASCII_ARMOR = "ascii_armor"
        const val RESULT_CODE = "result_code"
        const val RESULT_ERROR = "error"
        const val RESULT_INTENT = "intent"
        const val RESULT_SIGNATURE = "signature"
        const val RESULT_CODE_ERROR = 0
        const val RESULT_CODE_SUCCESS = 1
        const val RESULT_CODE_USER_INTERACTION_REQUIRED = 2

        private const val TIMEOUT = 20_000L
        private val pipes = AtomicInteger()
    }
}
