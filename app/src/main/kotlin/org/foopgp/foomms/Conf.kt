package org.foopgp.foomms

import android.content.Context

/** What the person sets, kept between two uses of the application. */
object Conf {

    enum class Theme { DARK, SYSTEM, LIGHT }

    /** How long the words of an opened message are held in memory. */
    enum class Hold(val minutes: Int) {
        /** Not past the conversation they were opened in. */
        NEVER(0),
        TWO_MINUTES(2),
        FIVE_MINUTES(5),
        FIFTEEN_MINUTES(15),
        HOUR(60),
        FOUR_HOURS(240),
    }

    const val MOST_AROUND = 5
    private const val USUAL_AROUND = 2

    /** How many closed messages near the one pressed a use of the key opens with it; none, and it opens alone. */
    fun around(context: Context): Int = kept(context).getInt("neighbours", USUAL_AROUND).coerceIn(0, MOST_AROUND)

    fun around(context: Context, most: Int) = kept(context).edit().putInt("neighbours", most.coerceIn(0, MOST_AROUND)).apply()

    fun theme(context: Context): Theme = named(kept(context).getString("theme", null), Theme.SYSTEM)

    fun theme(context: Context, theme: Theme) = kept(context).edit().putString("theme", theme.name).apply()

    fun hold(context: Context): Hold = named(kept(context).getString("hold", null), Hold.FIFTEEN_MINUTES)

    fun hold(context: Context, hold: Hold) = kept(context).edit().putString("hold", hold.name).apply()

    private inline fun <reified T : Enum<T>> named(name: String?, otherwise: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: otherwise

    private fun kept(context: Context) = context.getSharedPreferences("configuration", Context.MODE_PRIVATE)
}
