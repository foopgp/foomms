package org.foopgp.foomms

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView

/**
 * What changed, version by version, newest first, in the language of the
 * pages around it. Each note is a file of its own, `<code>-<version>.txt`
 * under `assets/changelogs/<language>/`: the code orders them, the version
 * is the heading, and a date after it, `<code>-<version>-<YYYY-MM-DD>.txt`,
 * the day it went out.
 */
class ChangelogActivity : Activity() {

    /** Left by the top, as every page but the list. */
    private val swipes by lazy { Swipes.watch(this) }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean = swipes.feed(event) || super.dispatchTouchEvent(event)

    override fun onCreate(state: Bundle?) {
        Look.dress(this)
        super.onCreate(state)
        title = getString(R.string.about_news)
        val column = Look.column(this)
        setContentView(Swipes.under(this, ScrollView(this).apply { addView(column) }, getString(R.string.conf_title)))
        Edges.fit(this)
        for ((version, note) in notes()) {
            // The day it went out, when the file name says it: small, and grey.
            val day = Regex("-(\\d{4}-\\d{2}-\\d{2})$").find(version)
            val heading: CharSequence = if (day == null) version else android.text.SpannableStringBuilder(version.removeSuffix(day.value)).apply {
                val from = length
                append("   ").append(day.groupValues[1])
                setSpan(android.text.style.RelativeSizeSpan(0.65f), from, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(Look.MUTED), from, length, 0)
            }
            column.addView(Look.words(this, heading, size = 19f).apply { setTypeface(typeface, Typeface.BOLD) ; setPadding(0, Look.dp(context, 12), 0, 0) })
            column.addView(Look.words(this, note, size = 15f).apply { setPadding(0, Look.dp(context, 4), 0, 0) })
        }
    }

    private fun notes(): List<Pair<String, String>> = runCatching {
        val folder = "changelogs/${language()}"
        assets.list(folder).orEmpty()
            .mapNotNull { name ->
                val stem = name.removeSuffix(".txt")
                stem.substringBefore('-').toIntOrNull()?.let { Triple(it, stem.substringAfter('-'), name) }
            }
            .sortedByDescending { it.first }
            .map { (_, version, name) -> version to assets.open("$folder/$name").bufferedReader().use { it.readText() }.trim() }
    }.getOrDefault(emptyList())

    /** The language these pages are in, which is not always the phone's; one not shipped falls back to English. */
    private fun language(): String {
        val wanted = getString(R.string.language_tag)
        val shipped = runCatching { assets.list("changelogs").orEmpty().toList() }.getOrDefault(emptyList())
        return if (wanted in shipped) wanted else "en"
    }
}
