package com.loosecannon.servicetag.ui.health

import android.content.res.Resources
import com.loosecannon.servicetag.R

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
 * It reads [resources] and changes nothing in it; the number is formatted in the resources' language.
 * A screen builds one from its own resources where it draws health; none is stored in the graph.
 */
class AndroidHealthPlurals(private val resources: Resources) : HealthPlurals {

    override fun ageDays(n: Long): String =
        resources.getQuantityString(R.plurals.health_age_days, quantity(n), n)

    override fun daysOverdue(title: String, n: Long): String =
        resources.getQuantityString(R.plurals.health_days_overdue, quantity(n), title, n)

    /** Plural rules read the count as an [Int]; no day count the app shows comes near the limit. */
    private fun quantity(n: Long): Int = n.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}
