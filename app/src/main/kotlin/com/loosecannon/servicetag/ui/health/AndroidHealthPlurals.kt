package com.loosecannon.servicetag.ui.health

import android.content.res.Resources
import com.loosecannon.servicetag.R

/**
 * [HealthPlurals] over `res/values/plurals.xml` (plan decision 29): the quantity is the selector,
 * so the `one` form ("1 day", "… is 1 day overdue") is chosen for exactly one and the ratified
 * `other` form for everything else, zero included.
 *
 * A screen builds one from its own resources where it draws health; none is stored in the graph.
 */
class AndroidHealthPlurals(private val resources: Resources) : HealthPlurals {

    override fun ageDays(n: Long): String =
        resources.getQuantityString(R.plurals.health_age_days, n.quantity(), n)

    override fun daysOverdue(title: String, n: Long): String =
        resources.getQuantityString(R.plurals.health_days_overdue, n.quantity(), title, n)

    /** `getQuantityString` selects on an `Int`; a day count never comes near its bounds. */
    private fun Long.quantity(): Int = coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}
