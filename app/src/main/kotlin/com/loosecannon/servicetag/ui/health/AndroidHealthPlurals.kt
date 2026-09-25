package com.loosecannon.servicetag.ui.health

import android.content.res.Resources
import com.loosecannon.servicetag.R
import java.util.Locale

/**
 * [HealthPlurals] over the four day forms in `res/values/plurals.xml` (plan decision 29).
 *
 * **The form is chosen by English rules on every device** (the controller's rulings on B12's review,
 * I-2 and RS-3): `one` exactly when the number is 1, the ratified `other` form otherwise. The ratified
 * strings exist in English only, and each `one` form carries a literal "1". An Android plurals
 * resource would apply the device language's rules instead: where `one` also holds 0 (French,
 * Portuguese, Hindi) or 21, 31, 101 … (Russian, Ukrainian, Croatian), those numbers would read
 * "1 day", and where there is no `one` at all (Japanese), 1 would read "1 days".
 *
 * So this class picks the form itself and reads it **by its own string id**. It reads [resources] and
 * changes nothing in it, and it formats the number with English digits.
 *
 * A screen builds one from its own resources where it draws health; none is stored in the graph.
 */
class AndroidHealthPlurals(private val resources: Resources) : HealthPlurals {

    override fun ageDays(n: Long): String =
        if (n == 1L) {
            resources.getString(R.string.health_age_one_day)
        } else {
            english(resources.getString(R.string.health_age_n_days), n)
        }

    override fun daysOverdue(title: String, n: Long): String =
        english(
            resources.getString(if (n == 1L) R.string.health_one_day_overdue else R.string.health_n_days_overdue),
            title,
            n,
        )

    private fun english(form: String, vararg args: Any): String = String.format(Locale.ENGLISH, form, *args)
}
