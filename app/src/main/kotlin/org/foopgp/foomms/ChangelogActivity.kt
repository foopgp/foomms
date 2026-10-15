package org.foopgp.foomms

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView

/**
 * What changed, version by version, newest first, in the language of the
 * pages around it. Each note is a file of its own, `<code>-<version>.txt`
 * under `assets/changelogs/<language>/`: the code orders them, the version
 * is the heading.
 */
class ChangelogActivity : Activity() {

    override fun onCreate(state: Bundle?) {
        Look.dress(this)
        super.onCreate(state)
        title = getString(R.string.about_news)
        val column = Look.column(this)
        setContentView(ScrollView(this).apply { addView(column) })
        Edges.fit(this)
        for ((version, note) in notes()) {
            column.addView(Look.words(this, version, size = 19f).apply { setTypeface(typeface, Typeface.BOLD) ; setPadding(0, Look.dp(context, 12), 0, 0) })
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
