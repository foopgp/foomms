package org.foopgp.foomms

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The few views these pages are made of, built by hand, in the colours of foodjis. */
object Look {

    /** Whether the pages are dark, as the person set it or as the phone is. */
    var dark = false
        private set

    /** What the person wants now: asked before a page is built, and again when it comes back. */
    fun wantsDark(context: Context): Boolean = when (Conf.theme(context)) {
        Conf.Theme.DARK -> true
        Conf.Theme.LIGHT -> false
        Conf.Theme.SYSTEM ->
            context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /** Called by each page before it is built: its theme, and what the settings mean for the rest. */
    fun dress(activity: Activity) {
        dark = wantsDark(activity)
        activity.setTheme(if (dark) R.style.Pages_Dark else R.style.Pages)
        Opened.holdFor = Conf.hold(activity).minutes * 60_000L
    }

    val INK: Int get() = if (dark) Color.rgb(0xF4, 0xF4, 0xF5) else Color.rgb(0x18, 0x18, 0x1B)
    val MUTED: Int get() = if (dark) Color.argb(0x99, 0xF4, 0xF4, 0xF5) else Color.argb(0x99, 0x18, 0x18, 0x1B)
    val INDIGO = Color.rgb(0x31, 0x26, 0x4B)
    val ORANGE = Color.rgb(0xFF, 0x80, 0x12)
    val FACE: Int get() = if (dark) Color.rgb(0x46, 0x3A, 0x63) else Color.rgb(0xD6, 0xCF, 0xEC)
    private val HOLLOW: Int get() = if (dark) Color.rgb(0x2E, 0x2E, 0x33) else Color.rgb(0xEE, 0xEE, 0xEE)

    /** A verdict's colour, as foodjis paints its own: full for a mark, pale under a text. */
    fun colour(verdict: Verdict): Int = when (verdict) {
        Verdict.RED -> Color.rgb(0xFF, 0x62, 0x7D)
        Verdict.YELLOW -> Color.rgb(0xFA, 0xCC, 0x15)
        Verdict.BLUE -> if (dark) Color.rgb(0x71, 0xBF, 0xFF) else Color.rgb(0x00, 0x68, 0xB2)
        Verdict.GREEN -> Color.rgb(0x00, 0xD3, 0x90)
    }

    /** The same colour under a text: pale on a light page, deep on a dark one, the text legible on both. */
    fun pale(verdict: Verdict): Int = if (dark) {
        when (verdict) {
            Verdict.RED -> Color.rgb(0x5A, 0x1F, 0x2B)
            Verdict.YELLOW -> Color.rgb(0x57, 0x47, 0x08)
            Verdict.BLUE -> Color.rgb(0x0B, 0x3A, 0x5E)
            Verdict.GREEN -> Color.rgb(0x0A, 0x4A, 0x38)
        }
    } else {
        when (verdict) {
            Verdict.RED -> Color.rgb(0xFF, 0xE3, 0xE8)
            Verdict.YELLOW -> Color.rgb(0xFE, 0xF4, 0xC4)
            Verdict.BLUE -> Color.rgb(0xD6, 0xEC, 0xFF)
            Verdict.GREEN -> Color.rgb(0xCD, 0xF6, 0xE8)
        }
    }

    /**
     * What a certificate is worth, as foodjis words and paints it: the word
     * on its colour -- green certified, yellow not certified, orange expired,
     * red revoked or broken.
     */
    fun standing(context: Context, validity: String): CharSequence {
        val (word, ground, ink) = when (validity) {
            "certified" -> Triple(R.string.standing_certified, Color.rgb(0x00, 0xD3, 0x90), Color.rgb(0x00, 0x4C, 0x39))
            "uncertified" -> Triple(R.string.standing_uncertified, Color.rgb(0xFA, 0xCC, 0x15), Color.BLACK)
            "expired" -> Triple(R.string.standing_expired, Color.rgb(0xF9, 0x73, 0x16), Color.WHITE)
            "revoked" -> Triple(R.string.standing_revoked, Color.rgb(0xFF, 0x62, 0x7D), Color.rgb(0x4D, 0x02, 0x18))
            else -> Triple(R.string.standing_broken, Color.rgb(0xDB, 0x3A, 0x60), Color.WHITE)
        }
        // A space each side, inside the colour: a word flush against the edge of its ground reads as a stain.
        return android.text.SpannableString(" ${context.getString(word)} ").apply {
            setSpan(android.text.style.BackgroundColorSpan(ground), 0, length, 0)
            setSpan(android.text.style.ForegroundColorSpan(ink), 0, length, 0)
        }
    }

    /**
     * A certificate's standing as the contacts page of foodjis marks it: a
     * letter on its colour -- C certified, U not certified, E expired, R
     * revoked, B broken. The letter carries the meaning, for whoever does not
     * tell the colours apart.
     */
    fun badge(validity: String): CharSequence {
        val (letter, ground, ink) = when (validity) {
            "certified" -> Triple("C", Color.rgb(0x00, 0xD3, 0x90), Color.rgb(0x00, 0x4C, 0x39))
            "uncertified" -> Triple("U", Color.rgb(0xFA, 0xCC, 0x15), Color.BLACK)
            "expired" -> Triple("E", Color.rgb(0xF9, 0x73, 0x16), Color.WHITE)
            "revoked" -> Triple("R", Color.rgb(0xFF, 0x62, 0x7D), Color.rgb(0x4D, 0x02, 0x18))
            else -> Triple("B", Color.rgb(0xDB, 0x3A, 0x60), Color.WHITE)
        }
        return android.text.SpannableString(letter).apply { setSpan(Badge(ground, ink), 0, length, 0) }
    }

    /** A letter on a coloured ground with rounded corners, sitting on the line like the text beside it. */
    private class Badge(private val ground: Int, private val ink: Int) : android.text.style.ReplacementSpan() {
        override fun getSize(paint: android.graphics.Paint, text: CharSequence, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int {
            val bold = android.graphics.Paint(paint).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
            return (bold.measureText(text, start, end) + paint.textSize * 0.8f).toInt()
        }

        override fun draw(
            canvas: android.graphics.Canvas, text: CharSequence, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint,
        ) {
            val bold = android.graphics.Paint(paint).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
            val width = bold.measureText(text, start, end) + paint.textSize * 0.8f
            val metrics = paint.fontMetrics
            val rect = android.graphics.RectF(x, y + metrics.ascent - 1, x + width, y + metrics.descent + 1)
            val radius = paint.textSize * 0.35f
            canvas.drawRoundRect(rect, radius, radius, android.graphics.Paint(paint).apply { color = ground })
            bold.color = ink
            canvas.drawText(text, start, end, x + paint.textSize * 0.4f, y.toFloat(), bold)
        }
    }

    fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    fun column(activity: Activity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(activity, 20)
        setPadding(pad, pad, pad, pad)
    }

    fun words(context: Context, text: CharSequence, size: Float = 16f, muted: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(if (muted) MUTED else INK)
        }

    fun button(context: Context, label: CharSequence, then: (View) -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        background = rounded(FACE, dp(context, 10))
        setTextColor(INK)
        setOnClickListener(then)
    }

    fun rounded(colour: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(colour)
        cornerRadius = radius.toFloat()
    }

    /**
     * A face: the contact's own picture, else the one the certificate carries,
     * else the mark foodjis shows where there is none.
     */
    fun face(context: Context, side: Int, photo: Uri?, avatar: Bitmap?): View {
        val px = dp(context, side)
        val inner: View = when {
            photo != null -> ImageView(context).apply { setImageURI(photo) ; scaleType = ImageView.ScaleType.CENTER_CROP }
            avatar != null -> ImageView(context).apply { setImageBitmap(avatar) ; scaleType = ImageView.ScaleType.CENTER_CROP }
            else -> TextView(context).apply {
                text = "👤"
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, side * 0.5f)
                alpha = 0.35f
                gravity = Gravity.CENTER
            }
        }
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(px, px)
            background = rounded(HOLLOW, px / 5)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, px / 5f)
            }
            clipToOutline = true
            addView(inner, FrameLayout.LayoutParams(px, px))
        }
    }
}
