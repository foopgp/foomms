package org.foopgp.foomms

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.concurrent.thread

/**
 * Tying a contact to a certificate, or refreshing what it says of it: the
 * window that shows what would be written, and where the contact and the
 * certificate each have their own name, face or note, asks which to keep.
 * The conversation's page and the configuration's "Me" open it alike.
 */
object Tying {

    /**
     * Nothing is written in the address book before the person has seen what
     * would be: the card foodjis makes of the certificate, a line each, and
     * the [stale] lines an earlier one wrote, which go.
     */
    fun offer(
        activity: Activity, number: String, whose: Foodjis.Cert, stale: List<Book.Stale> = emptyList(), contact: Long? = null,
        write: (Card, Book.Pick, Book.Pick, Book.Pick) -> Unit,
    ) {
        thread {
            val card = Foodjis.of(activity).vcard(whose.fingerprint)
            // Where the contact has its own face or note, and the certificate another: the person chooses.
            val differs = card?.takeIf { activity.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED }
                ?.let { Book.differs(activity, number, it, contact) } ?: Book.Differs(null, emptyList())
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (card == null) {
                    AlertDialog.Builder(activity).setMessage(activity.getString(R.string.no_card, whose.name)).setPositiveButton(R.string.close, null).show()
                    return@runOnUiThread
                }
                // A face or a note the person chooses below is not also in the list of what is written.
                val told = card.told(withNotes = differs.notes.isEmpty(), withName = differs.name == null).joinToString("\n") { "•  $it" } +
                    (if (card.photo != null && differs.face == null) "\n•  " + activity.getString(R.string.card_photo) else "") +
                    "\n•  🔑 " + (card.key ?: Fingerprint.address(whose.fingerprint))
                val pad = Look.dp(activity, 20)
                var face = Book.Pick.CONTACT
                var note = Book.Pick.CONTACT
                var name = Book.Pick.CONTACT
                val column = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(pad, pad / 2, pad, 0)
                    addView(
                        Look.words(
                            context,
                            android.text.SpannableStringBuilder(activity.getString(R.string.tie_ask_before)).append(" ")
                                // Anything but certified, in bold: what the person is about to write in their address book.
                                .append(
                                    Look.standing(context, whose.validity).let { said ->
                                        if (whose.certified) said
                                        else android.text.SpannableString(said).apply { setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0) }
                                    },
                                ).append(activity.getString(R.string.tie_ask_after)).append("\n\n").append(told)
                                .apply { if (stale.isNotEmpty()) append("\n\n").append(activity.getString(R.string.tie_ask_removed)).append("\n").append(stale.joinToString("\n") { "•  ${it.value}" }) },
                            size = 15f,
                        ),
                    )
                    // The contact's name or the certificate's, as for the note: one or the other.
                    val ours = differs.name
                    val given = card.name
                    if (ours != null && given != null) {
                        addView(Look.words(context, activity.getString(R.string.choose_name), size = 15f).apply { setPadding(0, pad, 0, pad / 3) })
                        // Each name whole, on the right: beside its choice when both fit on the line, under it otherwise.
                        val buttons = ArrayList<android.widget.RadioButton>()
                        for ((label, shown, pick) in listOf(
                            Triple(R.string.name_contact, ours, Book.Pick.CONTACT),
                            Triple(R.string.name_certificate, given, Book.Pick.CERTIFICATE),
                        )) {
                            val button = android.widget.RadioButton(context).apply {
                                text = activity.getString(label) ; textSize = 14f ; setTextColor(Look.INK)
                                isChecked = pick == Book.Pick.CONTACT
                                setOnClickListener { name = pick ; buttons.forEach { other -> other.isChecked = other === this } }
                            }.also { buttons += it }
                            val value = Look.words(context, shown, size = 14f).apply {
                                setTypeface(typeface, android.graphics.Typeface.BOLD)
                                gravity = Gravity.END
                                setOnClickListener { button.performClick() }
                            }
                            addView(Look.beside(context, button, value))
                        }
                    }
                    // The two faces side by side, the contact's on the left: the one pressed is kept.
                    val theirs = card.photo?.let { Media.thumbnail(it, Media.FACE) }
                    if (differs.face != null && theirs != null) {
                        addView(Look.words(context, activity.getString(R.string.choose_face), size = 15f).apply { setPadding(0, pad, 0, pad / 3) })
                        val faces = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL ; gravity = Gravity.CENTER }
                        val frames = ArrayList<View>()
                        fun choice(picture: Bitmap, said: Int, pick: Book.Pick) = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER_HORIZONTAL
                            setPadding(pad / 3, pad / 3, pad / 3, pad / 3)
                            addView(
                                android.widget.ImageView(context).apply { setImageBitmap(picture) ; scaleType = android.widget.ImageView.ScaleType.CENTER_CROP },
                                LinearLayout.LayoutParams(Look.dp(context, 96), Look.dp(context, 96)),
                            )
                            addView(Look.words(context, activity.getString(said), size = 13f, muted = true))
                            setOnClickListener { face = pick ; frames.forEach { it.background = null } ; background = Look.rounded(Look.ORANGE, Look.dp(context, 8)) }
                        }.also { frames += it }
                        faces.addView(choice(differs.face, R.string.face_contact, Book.Pick.CONTACT).apply { background = Look.rounded(Look.ORANGE, Look.dp(context, 8)) })
                        faces.addView(View(context), LinearLayout.LayoutParams(pad, 1))
                        faces.addView(choice(theirs, R.string.face_certificate, Book.Pick.CERTIFICATE))
                        addView(faces)
                    }
                    // The contact's notes or the certificate's, each note in a frame of its own under its choice, cut when
                    // long: one choice or the other, never both, so that notes do not pile up in the contact.
                    if (differs.notes.isNotEmpty()) {
                        addView(Look.words(context, activity.getString(R.string.choose_note), size = 15f).apply { setPadding(0, pad, 0, pad / 3) })
                        fun cut(text: String) = if (text.length <= NOTE_SHOWN) text else "${text.take(NOTE_SHOWN).trimEnd()}… " + activity.getString(R.string.note_size, text.length)
                        val picks = listOf(
                            Triple(Book.Pick.CONTACT, if (differs.notes.size > 1) R.string.note_contact_many else R.string.note_contact, differs.notes),
                            Triple(Book.Pick.CERTIFICATE, if (card.notes.size > 1) R.string.note_certificate_many else R.string.note_certificate, card.notes),
                        )
                        addView(
                            android.widget.RadioGroup(context).apply {
                                for ((at, pick) in picks.withIndex()) {
                                    addView(android.widget.RadioButton(context).apply { id = at + 1 ; text = activity.getString(pick.second) ; textSize = 14f ; setTextColor(Look.INK) })
                                    // Pressed, a note chooses as its button does.
                                    for (one in pick.third) {
                                        addView(
                                            Look.words(context, cut(one), size = 14f).apply {
                                                setPadding(pad / 2, pad / 3, pad / 2, pad / 3)
                                                background = Look.framed(context)
                                                setOnClickListener { check(at + 1) }
                                            },
                                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                                .apply { marginStart = Look.dp(context, 32) ; bottomMargin = pad / 3 },
                                        )
                                    }
                                }
                                check(1)
                                setOnCheckedChangeListener { _, id -> note = picks[id - 1].first }
                            },
                        )
                    }
                }
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.tie_ask, number, whose.name))
                    .setView(android.widget.ScrollView(activity).apply { addView(column) })
                    .setPositiveButton(R.string.tie_write) { _, _ -> write(card, face, note, name) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    /** How much of a note the choice shows: a long one is cut, its length said. */
    private const val NOTE_SHOWN = 160
}
