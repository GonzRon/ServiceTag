package com.loosecannon.servicetag.ui.health

import android.content.res.Configuration
import android.content.res.Resources
import com.loosecannon.servicetag.R
import java.util.Locale

/**
 * [HealthPlurals] over `res/values/plurals.xml` (plan decision 29), with the quantity as the selector.
 *
 * **The form is chosen by English rules on every device** (the controller's ruling on B12's review,
 * I-2). The ratified strings exist in English only, and each `one` form carries a literal "1" — so a
 * device language whose `one` category also holds 0 (French, Portuguese, Hindi) or 21, 31, 101 …
 * (Russian, Ukrainian, Croatian) would otherwise draw "1 day" for those numbers, and a language with
 * no `one` category at all (Japanese) would draw "1 days". The two plurals are therefore read through
 * a copy of [resources] configured for [Locale.ENGLISH]: `one` exactly when the number is 1, the
 * ratified `other` form otherwise, and the number in English digits.
 *
 * A screen builds one from its own resources where it draws health; none is stored in the graph.
 */
class AndroidHealthPlurals(resources: Resources) : HealthPlurals {

    private val english: Resources = inEnglish(resources)

    override fun ageDays(n: Long): String =
        english.getQuantityString(R.plurals.health_age_days, n.quantity(), n)

    override fun daysOverdue(title: String, n: Long): String =
        english.getQuantityString(R.plurals.health_days_overdue, n.quantity(), title, n)

    /** `getQuantityString` selects on an `Int`; a day count never comes near its bounds. */
    private fun Long.quantity(): Int = coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        /**
         * The same assets and metrics under an English configuration, so the plural **rule** is
         * English. The `Resources` constructor is deprecated in favour of
         * `Context.createConfigurationContext`, which needs a `Context` the pinned signature does not
         * carry.
         *
         * The constructor also applies its configuration to the `AssetManager` it shares with
         * [resources], which would switch every other lookup through [resources] — a library's
         * translated strings, say — to English. So the caller's own configuration is applied back at
         * once. The copy keeps its English rule either way: the rule lives in the copy, and the two
         * plurals exist only in `values/`, so which language the shared assets resolve in cannot
         * change them.
         */
        @Suppress("DEPRECATION")
        fun inEnglish(resources: Resources): Resources {
            val config = Configuration(resources.configuration).apply { setLocale(Locale.ENGLISH) }
            val english = Resources(resources.assets, resources.displayMetrics, config)
            resources.updateConfiguration(resources.configuration, resources.displayMetrics)
            return english
        }
    }
}
