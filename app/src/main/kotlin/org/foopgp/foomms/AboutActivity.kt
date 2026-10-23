package org.foopgp.foomms

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * What this application is, who wrote it, and under what terms -- in the
 * order foodjis gives them. The copyright lines are written here and not in
 * strings.xml: a copyright line is not translated, and an identifier
 * mistyped in one catalogue is a broken claim about a person.
 */
class AboutActivity : Activity() {

    private class Holder(val years: String, val name: String, val eid: String, val mail: String)

    private val copyrights = listOf(
        Holder("2026", "Jean-Jacques Brucker", "u4sRyUhEbNU5OwyLEjfSwaXAe_42.17-002.76", "jjbrucker@foopgp.org"),
        Holder("2026", "Mnêmê", "u5001777236237.945e_43.30_005.38 claude-opus-5-5", "mneme@foopgp.org"),
    )

    private val thirdParty = listOf("openpgp-api (thunderbird-android) — Apache License 2.0")

    /** Where the component presents itself. */
    private val thirdPartyHome = "https://github.com/thunderbird/thunderbird-android/tree/main/plugins/openpgp-api-lib"

    /** Left by the top, as every page but the list. */
    private val swipes by lazy { Swipes.watch(this) }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean = swipes.feed(event) || super.dispatchTouchEvent(event)

    override fun onCreate(state: Bundle?) {
        Look.dress(this)
        super.onCreate(state)
        title = getString(R.string.about_title)
        val column = Look.column(this)
        setContentView(Swipes.under(this, ScrollView(this).apply { addView(column) }, getString(R.string.about_news)))
        Edges.fit(this)

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        column.addView(Look.words(this, "${getString(R.string.app_name)} $version", size = 26f).apply { setTypeface(typeface, Typeface.BOLD) })
        column.addView(paragraph(getString(R.string.about_what)))

        column.addView(heading(R.string.about_copyright))
        for (holder in copyrights) {
            column.addView(
                Look.words(this, "Copyright ${holder.years} ${holder.name} (${holder.eid}) <${holder.mail}>", size = 12f)
                    .apply { typeface = Typeface.MONOSPACE },
            )
        }

        column.addView(heading(R.string.about_licence))
        column.addView(paragraph(getString(R.string.about_licence_body)))
        // Whoever holds the binary may ask for the source that made it: the licence says so, so the page says where to ask.
        column.addView(paragraph(getString(R.string.about_source)))
        column.addView(Look.button(this, getString(R.string.about_licence_gnu)) { open("https://www.gnu.org/licenses/gpl-3.0.html#license-text") })

        // What the Apache 2.0 asks of whoever passes its files on: the attribution, where the application names itself.
        column.addView(heading(R.string.about_third_party))
        for (line in thirdParty) column.addView(Look.words(this, line, size = 12f).apply { typeface = Typeface.MONOSPACE })
        column.addView(Look.button(this, getString(R.string.about_third_party_home)) { open(thirdPartyHome) })
        column.addView(Look.button(this, getString(R.string.about_licence_apache)) { open("https://www.apache.org/licenses/LICENSE-2.0") })
    }

    private fun open(address: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address))) }
    }

    private fun heading(words: Int) = Look.words(this, getString(words), size = 17f).apply {
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, Look.dp(context, 16), 0, Look.dp(context, 4))
    }

    private fun paragraph(words: String) = Look.words(this, words, size = 15f).apply {
        setPadding(0, Look.dp(context, 6), 0, Look.dp(context, 6))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
}
