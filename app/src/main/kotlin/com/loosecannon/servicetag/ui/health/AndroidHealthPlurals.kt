package com.loosecannon.servicetag.ui.health

import android.content.res.Resources
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.l10n.localizedPlural

/**
 * [HealthPlurals] over the two Android plurals in `res/values/plurals.xml` (plan decision 29; #102):
 * `health_age_days` for S99's `<age>` and `health_days_overdue` for S102.
 *
 * **The form is chosen by the language's own plural rules** ([Resources.getQuantityString]), which is
 * safe because every form, in every language, shows the number through a placeholder and never as a
 * literal "1": where `one` also holds 0 (French) or 21, 31, 101 … (Russian), the form it selects still
 * prints the right number, and where there is no `one` at all (Japanese) the `other` form does. English
 * reads exactly as ratified: "1 day", "2 days", "<title> is 1 day overdue".
 *
 * It reads [resources] and changes nothing in it; the number is formatted in the resources' language. When
 * the resources' language has no pack of its own — their words are another language's, usually English —
 * the form comes from [localizedPlural], which picks it by the rules of the language the words are written
 * in, so a Ukrainian phone reads "21 days" and not "21 day".
 * A screen builds one from its own resources where it draws health; none is stored in the graph.
 */
class AndroidHealthPlurals(private val resources: Resources) : HealthPlurals {

    override fun ageDays(n: Long): String =
        if (ownLanguage) {
            resources.getQuantityString(R.plurals.health_age_days, quantity(n), n)
        } else {
            localizedPlural(R.plurals.health_age_days, n, n)
        }

    override fun daysOverdue(title: String, n: Long): String =
        if (ownLanguage) {
            resources.getQuantityString(R.plurals.health_days_overdue, quantity(n), title, n)
        } else {
            localizedPlural(R.plurals.health_days_overdue, n, title, n)
        }

    /** Whether the resources' first language is the one their words are written in (`format_language`). */
    private val ownLanguage: Boolean
        get() = resources.configuration.locales[0]?.language == resources.getString(R.string.format_language)

    /** Plural rules read the count as an [Int]; no day count the app shows comes near the limit. */
    private fun quantity(n: Long): Int = n.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}
