package org.foopgp.foomms

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
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
        Opened.holdFor = Conf.hold(activity).seconds * 1000L
    }

    val INK: Int get() = if (dark) Color.rgb(0xF4, 0xF4, 0xF5) else Color.rgb(0x18, 0x18, 0x1B)
    val MUTED: Int get() = if (dark) Color.argb(0x99, 0xF4, 0xF4, 0xF5) else Color.argb(0x99, 0x18, 0x18, 0x1B)
    val INDIGO = Color.rgb(0x31, 0x26, 0x4B)
    val ORANGE = Color.rgb(0xFF, 0x80, 0x12)
    val FACE: Int get() = if (dark) Color.rgb(0x46, 0x3A, 0x63) else Color.rgb(0xD6, 0xCF, 0xEC)
    private val HOLLOW: Int get() = if (dark) Color.rgb(0x2E, 0x2E, 0x33) else Color.rgb(0xEE, 0xEE, 0xEE)

    /**
     * A string with its marks -- a word in bold -- and its arguments put in
     * place: getString would drop the marks, and formatting would too.
     */
    fun styled(context: Context, id: Int, vararg args: CharSequence): CharSequence {
        val out = android.text.SpannableStringBuilder(context.resources.getText(id))
        for ((at, arg) in args.withIndex()) {
            val tag = "%${at + 1}\$s"
            var from = out.indexOf(tag)
            while (from >= 0) {
                out.replace(from, from + tag.length, arg)
                from = out.indexOf(tag, from + arg.length)
            }
        }
        return out
    }

    /**
     * A control as players draw theirs over a picture -- a sign in our
     * orange, bold, on a round shade --, for the pages that film and that play.
     */
    fun knob(context: Context, sign: String, said: String, then: () -> Unit) = TextView(context).apply {
        text = sign
        textSize = 20f
        gravity = android.view.Gravity.CENTER
        setTextColor(ORANGE)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL ; setColor(Color.argb(0x66, 0, 0, 0)) }
        contentDescription = said
        isClickable = true
        setOnClickListener { then() }
    }

    /** The same round knob, with a drawing for its sign: the share, whose usual sign is no character. */
    fun knob(context: Context, drawing: Int, said: String, then: () -> Unit) = ImageView(context).apply {
        setImageResource(drawing)
        imageTintList = android.content.res.ColorStateList.valueOf(ORANGE)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = dp(context, 14)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL ; setColor(Color.argb(0x66, 0, 0, 0)) }
        contentDescription = said
        isClickable = true
        setOnClickListener { then() }
    }

    /** the whole screen to a picture -- no title bar, the phone's own bars away until swiped back. */
    fun fullScreen(activity: Activity) {
        activity.actionBar?.hide()
        val window = activity.window
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        // Under the camera's notch too, on every side. "Short edges", what Android 9 and 10 had, is obsolete since
        // Android 15, which takes it for this one: those two leave a dark strip by the notch, lying sideways.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        }
    }

    /** the shade under what lies over a picture, dark at the edge [from]. */
    fun shade(from: GradientDrawable.Orientation) = GradientDrawable(from, intArrayOf(Color.argb(0xB0, 0, 0, 0), Color.TRANSPARENT))

    /** A verdict's colour, as foodjis paints its own: full for a mark, pale under a text. */
    fun colour(verdict: Verdict, dark: Boolean = this.dark): Int = when (verdict) {
        Verdict.RED -> Color.rgb(0xFF, 0x62, 0x7D)
        Verdict.YELLOW -> Color.rgb(0xFA, 0xCC, 0x15)
        Verdict.BLUE -> if (dark) Color.rgb(0x71, 0xBF, 0xFF) else Color.rgb(0x00, 0x68, 0xB2)
        Verdict.GREEN -> Color.rgb(0x00, 0xD3, 0x90)
        Verdict.GREY -> Color.rgb(0xA1, 0xA1, 0xAA)
        // Deeper than the violet of a signed message's mark, more olive than the yellow-green of an encrypted one's.
        Verdict.VIOLET -> if (dark) Color.rgb(0xC0, 0x84, 0xFC) else Color.rgb(0x6D, 0x28, 0xD9)
        Verdict.OLIVE -> Color.rgb(0x8A, 0xA6, 0x1C)
    }

    /**
     * A form's colour: the ink of the page for a message in clear, violet --
     * red and blue, what a signed one may be worth -- for one signed, and
     * yellow-green -- yellow and green -- for one encrypted.
     */
    fun colour(form: Form, dark: Boolean = this.dark): Int = when (form) {
        Form.PLAIN -> if (dark) Color.rgb(0xF4, 0xF4, 0xF5) else Color.rgb(0x18, 0x18, 0x1B)
        Form.SIGNED -> Color.rgb(0xA8, 0x55, 0xF7)
        Form.LOCKED -> Color.rgb(0xD8, 0xD4, 0x16)
    }

    /** The same colour under a text: pale on a light page, deep on a dark one, the text legible on both. */
    fun pale(verdict: Verdict): Int = if (dark) {
        when (verdict) {
            Verdict.RED -> Color.rgb(0x5A, 0x1F, 0x2B)
            Verdict.YELLOW -> Color.rgb(0x57, 0x47, 0x08)
            Verdict.BLUE -> Color.rgb(0x0B, 0x3A, 0x5E)
            Verdict.GREEN -> Color.rgb(0x0A, 0x4A, 0x38)
            Verdict.GREY -> Color.rgb(0x3F, 0x3F, 0x46)
            Verdict.VIOLET -> Color.rgb(0x3B, 0x1F, 0x63)
            Verdict.OLIVE -> Color.rgb(0x38, 0x42, 0x0E)
        }
    } else {
        when (verdict) {
            Verdict.RED -> Color.rgb(0xFF, 0xE3, 0xE8)
            Verdict.YELLOW -> Color.rgb(0xFE, 0xF4, 0xC4)
            Verdict.BLUE -> Color.rgb(0xD6, 0xEC, 0xFF)
            Verdict.GREEN -> Color.rgb(0xCD, 0xF6, 0xE8)
            Verdict.GREY -> Color.rgb(0xE4, 0xE4, 0xE7)
            Verdict.VIOLET -> Color.rgb(0xEC, 0xE3, 0xFC)
            Verdict.OLIVE -> Color.rgb(0xEE, 0xF4, 0xCC)
        }
    }

    /**
     * What a certificate is worth, as foodjis words and paints it: the word
     * on its colour -- green certified, yellow not certified, orange expired,
     * red revoked or broken.
     */
    fun standing(context: Context, validity: String): CharSequence {
        val (word, ink) = when (validity) {
            "certified" -> R.string.standing_certified to Color.rgb(0x00, 0x4C, 0x39)
            "uncertified" -> R.string.standing_uncertified to Color.BLACK
            "expired" -> R.string.standing_expired to Color.WHITE
            "revoked" -> R.string.standing_revoked to Color.rgb(0x4D, 0x02, 0x18)
            else -> R.string.standing_broken to Color.WHITE
        }
        // On a ground with rounded corners, the colour that frames the faces; the ground leaves room each side.
        return android.text.SpannableString(context.getString(word)).apply { setSpan(Badge(standingColour(validity), ink), 0, length, 0) }
    }

    /**
     * The colour foodjis gives a certificate's standing, by the word it
     * says it with: what frames the face of whoever the number means.
     */
    fun standingColour(validity: String): Int = when (validity) {
        "certified" -> Color.rgb(0x00, 0xD3, 0x90)
        "uncertified" -> Color.rgb(0xFA, 0xCC, 0x15)
        "expired" -> Color.rgb(0xF9, 0x73, 0x16)
        "revoked" -> Color.rgb(0xFF, 0x62, 0x7D)
        else -> Color.rgb(0xDB, 0x3A, 0x60)
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

    /**
     * Three dots, one above the other, at the end of a line of a bar, saying
     * that the bar can be pressed: the line takes the room left, the dots
     * stand at its right.
     */
    fun pressable(context: Context, line: android.widget.TextView): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(line, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(
            TextView(context).apply {
                text = "⋮"
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                paint.isFakeBoldText = true
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setPadding(dp(context, 10), 0, dp(context, 4), 0)
            },
        )
    }

    /** A drawing to press, in a title bar: what it does is said for whoever cannot see it. */
    fun icon(context: Context, drawing: Int, said: CharSequence, then: (View) -> Unit): ImageView = ImageView(context).apply {
        setImageResource(drawing)
        contentDescription = said
        val pad = dp(context, 8)
        setPadding(pad, pad, pad, pad)
        val ripple = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        background = ripple.getDrawable(0)
        ripple.recycle()
        setOnClickListener(then)
    }

    /**
     * A page's title in its bar, on one line and smaller as it grows longer: "Rechercher dans les conversations"
     * was cut. The page keeps the title for what reads the screen aloud.
     */
    fun barTitle(activity: Activity, text: CharSequence) {
        activity.title = text
        val bar = activity.actionBar ?: return
        val shown = (bar.customView as? TextView) ?: TextView(activity).apply {
            setTextColor(ORANGE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(12, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            bar.setDisplayShowTitleEnabled(false)
            bar.setDisplayShowCustomEnabled(true)
            bar.customView = this
            layoutParams = android.app.ActionBar.LayoutParams(android.app.ActionBar.LayoutParams.MATCH_PARENT, android.app.ActionBar.LayoutParams.WRAP_CONTENT)
        }
        shown.text = text
    }

    /** When something happened, as people say it: "3 min ago", "yesterday", "12 Oct". */
    fun ago(at: Long): CharSequence =
        android.text.format.DateUtils.getRelativeTimeSpanString(
            at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_ALL,
        )

    fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    fun column(activity: Activity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(activity, 20)
        setPadding(pad, pad, pad, pad)
    }

    /**
     * What is attached, said above the field between a paper clip and its
     * cross, both as tall and with as much room on each side; the cross,
     * red and bold, alone takes it off. Neither shows while the field records.
     */
    class Carried(val row: LinearLayout, val words: TextView, val clip: TextView, val cross: TextView) {
        fun attached(on: Boolean) {
            clip.visibility = if (on) View.VISIBLE else View.GONE
            cross.visibility = clip.visibility
        }
    }

    fun carried(context: Context, drop: () -> Unit): Carried {
        val pad = dp(context, 6)
        val said = words(context, "", size = 16f, muted = true).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }
        fun glyph(text: String) = words(context, text, size = 22f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(pad * 4, 0, pad * 4, 0)
        }
        val clip = glyph("📎")
        val cross = glyph("✕").apply {
            setTextColor(Color.rgb(0xE5, 0x39, 0x35))
            contentDescription = context.getString(R.string.drop_attachment)
            setOnClickListener { drop() }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad, 0, 0)
            visibility = View.GONE
            // The words take what the two leave, so that the cross never goes past the edge.
            addView(clip)
            addView(said, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(cross)
        }
        return Carried(row, said, clip, cross)
    }

    /** A discreet frame around a text set apart, as a note among others: a thin line, rounded, nothing filled. */
    fun framed(context: Context): GradientDrawable = GradientDrawable().apply {
        setStroke(dp(context, 1), MUTED)
        cornerRadius = dp(context, 6).toFloat()
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
        background = buttonFace(context, FACE)
        setTextColor(INK)
        setOnClickListener(then)
    }

    /** A small button, to sit on a line of text. */
    fun small(context: Context, label: CharSequence, then: (View) -> Unit): Button = button(context, label, then).apply {
        textSize = 12f
        minHeight = 0 ; minimumHeight = 0 ; minWidth = 0 ; minimumWidth = 0
        setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5))
    }

    /** A button's face, with a margin drawn in: two buttons one after the other stay apart, wherever they are put. */
    fun buttonFace(context: Context, colour: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.InsetDrawable(rounded(colour, dp(context, 10)), dp(context, 2), dp(context, 3), dp(context, 2), dp(context, 3))

    fun rounded(colour: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(colour)
        cornerRadius = radius.toFloat()
    }

    /**
     * A face: the contact's own picture, else the one the certificate carries,
     * else the mark foodjis shows where there is none.
     *
     * [framable]: room is kept around the picture for a frame, whether one is drawn or not, so that a list
     * of faces stays aligned and every picture keeps its [side].
     *
     * [seed]: with no picture, the face of pixels it makes ([pixels]); [lineDp], the frame's width.
     */
    fun face(
        context: Context, side: Int, photo: Uri?, avatar: Bitmap?, standing: String? = null, framable: Boolean = false,
        seed: String? = null, lineDp: Int = 4,
    ): View {
        val px = dp(context, side)
        val inner: View = when {
            photo != null -> ImageView(context).apply { setImageURI(photo) ; scaleType = ImageView.ScaleType.CENTER_CROP }
            avatar != null -> ImageView(context).apply { setImageBitmap(avatar) ; scaleType = ImageView.ScaleType.CENTER_CROP }
            // Its pixels kept sharp, not blurred as a photo is when it grows.
            seed != null -> ImageView(context).apply {
                setImageDrawable(android.graphics.drawable.BitmapDrawable(context.resources, pixels(seed)).apply { isFilterBitmap = false })
                scaleType = ImageView.ScaleType.FIT_XY
            }
            else -> TextView(context).apply {
                text = "👤"
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, side * 0.5f)
                alpha = 0.35f
                gravity = Gravity.CENTER
            }
        }
        val radius = px / 5f
        // Tied to a certificate, the face is framed in the colour of its standing: the line is added around the
        // picture, which keeps its whole size, and the picture's corners follow the line's inner edge.
        val line = if (standing != null || framable) dp(context, lineDp) else 0
        inner.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        inner.clipToOutline = true
        inner.background = rounded(HOLLOW, px / 5)
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(px + 2 * line, px + 2 * line)
            // The line is drawn half its width inside its rectangle: rounded on radius + line/2, its inner edge
            // falls exactly on the picture's corners, and its outer edge rounds on radius + line.
            standing?.let { background = GradientDrawable().apply { cornerRadius = radius + line / 2f ; setStroke(line, standingColour(it)) } }
            setPadding(line, line, line, line)
            addView(inner, FrameLayout.LayoutParams(px, px))
        }
    }

    /**
     * A choice and its value on one line, the value on the right; when both
     * do not fit, the value on the line below, still on the right. A name is
     * never cut by the end of a line.
     */
    fun beside(context: Context, label: View, value: View): android.view.ViewGroup = object : android.view.ViewGroup(context) {
        private var under = false

        init { addView(label) ; addView(value) }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val width = MeasureSpec.getSize(widthSpec)
            val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            val most = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
            label.measure(most, free)
            value.measure(free, free)
            under = label.measuredWidth + dp(context, 8) + value.measuredWidth > width
            if (under) value.measure(most, free)
            val height = if (under) label.measuredHeight + value.measuredHeight else maxOf(label.measuredHeight, value.measuredHeight)
            setMeasuredDimension(width, height)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val width = right - left
            if (under) {
                label.layout(0, 0, label.measuredWidth, label.measuredHeight)
                value.layout(width - value.measuredWidth, label.measuredHeight, width, label.measuredHeight + value.measuredHeight)
            } else {
                val middle = measuredHeight / 2
                label.layout(0, middle - label.measuredHeight / 2, label.measuredWidth, middle + (label.measuredHeight + 1) / 2)
                value.layout(width - value.measuredWidth, middle - value.measuredHeight / 2, width, middle + (value.measuredHeight + 1) / 2)
            }
        }
    }

    /**
     * Views one after the other on a line, going on to the next when the
     * line is full; every line kept to the right but for the first view,
     * on the left: words, then faces in columns, that never cross the edge.
     */
    fun flow(context: Context, gap: Int): android.view.ViewGroup = object : android.view.ViewGroup(context) {
        private val lines = ArrayList<List<View>>()

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val most = MeasureSpec.getSize(widthSpec).takeIf { MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED } ?: Int.MAX_VALUE
            lines.clear()
            var line = ArrayList<View>()
            var width = 0
            var widest = 0
            var height = 0
            fun close() { if (line.isNotEmpty()) { lines += line ; height += line.maxOf { it.measuredHeight } } ; line = ArrayList() ; width = 0 }
            for (at in 0 until childCount) {
                val one = getChildAt(at)
                one.measure(MeasureSpec.makeMeasureSpec(most, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                val need = one.measuredWidth + if (line.isEmpty()) 0 else gap
                if (line.isNotEmpty() && width + need > most) close()
                width += if (line.isEmpty()) one.measuredWidth else need
                line += one
                widest = maxOf(widest, width)
            }
            close()
            // Several lines: the whole width, for their right edges to meet.
            setMeasuredDimension(if (lines.size > 1 && most != Int.MAX_VALUE) most else widest, height)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            var y = 0
            for ((at, line) in lines.withIndex()) {
                val tall = line.maxOf { it.measuredHeight }
                // From the right edge leftwards; the very first view alone at the left.
                var x = right - left
                for ((rank, one) in line.withIndex().reversed()) {
                    val down = y + (tall - one.measuredHeight) / 2
                    val from = if (at == 0 && rank == 0) 0 else x - one.measuredWidth
                    one.layout(from, down, from + one.measuredWidth, down + one.measuredHeight)
                    x = from - gap
                }
                y += tall
            }
        }
    }

    /** One of a group, as a small face shows them: their picture, what their certificate is worth, and what pixels would be made from. */
    class Mini(val face: Bitmap?, val standing: String?, val seed: String?)

    /**
     * A group at a glance, in a square of [side]: three by three small
     * faces, in reading order -- beyond nine, eight and how many more --,
     * each framed by what its
     * certificate is worth. The places a smaller group leaves stay empty:
     * how full the square is says how many it is.
     */
    fun crowd(context: Context, side: Int, minis: List<Mini>): View {
        val line = 1
        val cell = (side - 6 * line) / 3
        val pad = dp(context, 3)
        // Beyond nine, eight faces and how many more.
        val shown: List<Mini?> = if (minis.size <= 9) minis else minis.take(8) + listOf(null)
        val rows = (shown.size + 2) / 3
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // The rows taken, in the middle of the square: the order of reading kept, the square balanced.
            gravity = Gravity.CENTER_VERTICAL
            // In a small frame of its own, faint: one square, not nine faces adrift.
            background = GradientDrawable().apply {
                setStroke(dp(context, 1), (MUTED and 0x00FFFFFF) or (0x44 shl 24))
                cornerRadius = dp(context, 6).toFloat()
            }
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(dp(context, side) + 2 * pad, dp(context, side) + 2 * pad)
            for (row in 0 until rows) {
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        for (column in 0 until 3) {
                            val at = row * 3 + column
                            if (at >= shown.size) break
                            val one = shown[at]
                            addView(
                                if (one != null) face(context, cell, null, one.face, one.standing, framable = true, seed = one.seed, lineDp = line)
                                else TextView(context).apply {
                                    text = "+${minis.size - 8}"
                                    gravity = Gravity.CENTER
                                    setTextColor(MUTED)
                                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, cell * 0.45f)
                                    layoutParams = LinearLayout.LayoutParams(dp(context, cell + 2 * line), dp(context, cell + 2 * line))
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    /** What a face of pixels is made from: a certificate's fingerprint, else the number. */
    fun seed(fingerprint: String?, number: String?): String? = fingerprint ?: number?.let(Phone::key)

    private val drawn = HashMap<String, Bitmap>()

    /**
     * A face for whoever has no picture, made of pixels from [seed]: five by
     * five, the left mirrored on the right, as forges draw them. The same
     * seed, the same face, wherever it is shown. Its hue is kept away from
     * those of a certificate's frame -- green, yellow, orange, red -- and
     * from the violet of what is signed: cyan to indigo, or magenta.
     */
    fun pixels(seed: String): Bitmap = synchronized(drawn) {
        drawn.getOrPut(seed) {
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
            fun byte(at: Int) = hash[at].toInt() and 0xFF
            val hue = if (byte(0) % 4 == 3) 300f + byte(1) % 25 else 180f + byte(1) % 70
            val ink = Color.HSVToColor(floatArrayOf(hue, 0.55f + byte(2) % 30 / 100f, 0.62f + byte(3) % 25 / 100f))
            val cell = 8
            val margin = cell / 2
            val bitmap = Bitmap.createBitmap(5 * cell + 2 * margin, 5 * cell + 2 * margin, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(0xEE, 0xEE, 0xF2))
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint().apply { color = ink }
            for (row in 0 until 5) for (column in 0 until 3) {
                if (byte(4 + row * 3 + column) % 2 == 0) continue
                for (x in setOf(column, 4 - column)) {
                    canvas.drawRect(
                        (margin + x * cell).toFloat(), (margin + row * cell).toFloat(),
                        (margin + (x + 1) * cell).toFloat(), (margin + (row + 1) * cell).toFloat(), paint,
                    )
                }
            }
            bitmap
        }
    }
}
