package org.foopgp.foomms

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
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

        /** Refused: why, in the person's language, and the number the provider gives the reason ([ERROR_NOT_FOR_US]). */
        class Failed(val says: String, val code: Int = ERROR_GENERIC) : Answer
    }

    private val main = Handler(Looper.getMainLooper())
    private var door: IOpenPgpService2? = null
    private var bond: ServiceConnection? = null

    private fun whom(): ComponentName? = whom(context)

    private fun bound(then: (IOpenPgpService2?) -> Unit) {
        door?.let { return then(it) }
        val to = whom() ?: return then(null)
        var told = false
        val link = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                door = IOpenPgpService2.Stub.asInterface(binder)
                // Connected again after foodjis died and came back: the question was answered, or failed, long ago.
                if (told) return
                told = true
                then(door)
            }
            override fun onServiceDisconnected(name: ComponentName) { door = null }

            // foodjis updated or installed again: this link is dead for good, and Android will not bring it
            // back. Kept, every question after it failed with DeadObjectException until foomms was started
            // again; forgotten, the next question binds to the new foodjis.
            override fun onBindingDied(name: ComponentName) = forget()
        }
        bond = link
        if (!context.bindService(Intent(SERVICE).setComponent(to), link, Context.BIND_AUTO_CREATE)) then(null)
    }

    private fun forget() {
        bond?.let { runCatching { context.unbindService(it) } }
        bond = null
        door = null
    }

    /** Ask, with [input] sent along; [then] is called on the main thread. */
    fun ask(request: Intent, input: ByteArray?, then: (Answer) -> Unit) {
        request.putExtra(EXTRA_API_VERSION, API_VERSION)
        asked(request, input, then, again = true)
    }

    /**
     * [again]: a foodjis found gone in the middle -- updated, or stopped by Android -- is bound once more and
     * asked the same; gone twice, it is said not to answer, in words, rather than by the name of an exception.
     */
    private fun asked(request: Intent, input: ByteArray?, then: (Answer) -> Unit, again: Boolean) {
        bound { door ->
            if (door == null) return@bound then(Answer.Failed(context.getString(R.string.no_provider)))
            thread {
                val tried = runCatching { exchange(door, request, input) }
                val gone = tried.exceptionOrNull() is DeadObjectException
                main.post {
                    when {
                        gone && again -> { forget() ; asked(request, input, then, again = false) }
                        gone -> { forget() ; then(Answer.Failed(context.getString(R.string.no_provider))) }
                        else -> then(tried.getOrElse { Answer.Failed(it.message ?: it.toString()) })
                    }
                }
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
                says.getParcelableExtra<OpenPgpError>(RESULT_ERROR).let { Answer.Failed(it?.message ?: "refused", it?.errorId ?: ERROR_GENERIC) }
        }
    }

    fun close() = forget()

    companion object {
        const val ERROR_GENERIC = 0

        /** foodjis's own, from 1.4.5: the message is locked for none of the keys of the phone's identity. */
        const val ERROR_NOT_FOR_US = 100

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

        /**
         * The foodjis to speak to: of those this build would rather, the ones signed by the very key foomms is
         * signed with -- the same, by the stores' rules -- and no other. A package name is anyone's to take: an
         * application installed under foodjis's would otherwise be handed the texts to lock, and could answer
         * "certified" for whatever it liked.
         */
        fun whom(context: Context): ComponentName? {
            val preferred = preferred(context)
            val manager = context.packageManager
            return manager.queryIntentServices(Intent(SERVICE), 0)
                .map { it.serviceInfo }
                .filter { it.packageName in preferred }
                .filter { manager.checkSignatures(context.packageName, it.packageName) == android.content.pm.PackageManager.SIGNATURE_MATCH }
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
         * this build needs -- 1.5.0, the first to lay a signature made already
         * inside the lock --, or null: none to speak to, or recent enough.
         */
        fun outdated(context: Context): String? {
            val found = whom(context)?.packageName ?: return null
            val held = runCatching { context.packageManager.getPackageInfo(found, 0) }.getOrNull() ?: return null
            return held.versionName?.takeIf { held.longVersionCode < NEEDED_CODE }
        }

        /** The first foodjis that answers all that this build asks of it. */
        const val NEEDED_CODE = 54L
        const val NEEDED_NAME = "1.5.0"

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
        const val DETACHED_SIGN = "org.openintents.openpgp.action.DETACHED_SIGN"
        const val RESULT_DETACHED_SIGNATURE = "detached_signature"

        /** foodjis's own, with ENCRYPT: a signature over the same bytes, made already, laid inside the lock. */
        const val EXTRA_SIGNATURE = "org.foopgp.foodjis.signature"
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

        /** A file's name, kept inside what is locked: told back as [RESULT_METADATA] by foodjis from 1.4.5. */
        const val EXTRA_ORIGINAL_FILENAME = "original_filename"
        const val RESULT_METADATA = "metadata"
        const val RESULT_CODE = "result_code"
        const val RESULT_ERROR = "error"
        const val RESULT_INTENT = "intent"
        const val RESULT_SIGNATURE = "signature"
        const val RESULT_DECRYPTION = "decryption"

        /**
         * Whether what foodjis answered was encrypted: as it says, or as [guess] -- foomms's own reading of the
         * armor -- says, either being enough. A message held for not encrypted is never closed again, and a
         * notification may say its words: foomms's reading alone, of an armor a network bent, could miss one.
         */
        fun encrypted(answer: Answer.Done, guess: Boolean): Boolean {
            answer.says.setExtrasClassLoader(org.openintents.openpgp.OpenPgpDecryptionResult::class.java.classLoader)
            @Suppress("DEPRECATION")
            val said = answer.says.getParcelableExtra<org.openintents.openpgp.OpenPgpDecryptionResult>(RESULT_DECRYPTION)?.result
            return guess || said == org.openintents.openpgp.OpenPgpDecryptionResult.RESULT_ENCRYPTED
        }
        const val RESULT_CODE_ERROR = 0
        const val RESULT_CODE_SUCCESS = 1
        const val RESULT_CODE_USER_INTERACTION_REQUIRED = 2

        private const val TIMEOUT = 20_000L
        private val pipes = AtomicInteger()
    }
}
