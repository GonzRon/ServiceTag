package com.loosecannon.servicetag.l10n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.loosecannon.servicetag.R
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * [LocalizedText] over the application's resources. The resources are looked up on every read, not held: a
 * language change — the device's, or the app's own from Android's per-app language setting — updates the
 * application's configuration in place, and the next read renders in the new language.
 */
class AndroidLocalizedText(context: Context) : LocalizedText {
    private val app: Context = context.applicationContext ?: context

    /** Resources configured for a pack's own language, by that language; see [pluralResources]. */
    private val writtenIn = ConcurrentHashMap<String, Resources>()

    override val locale: Locale get() = app.resources.configuration.locales[0] ?: Locale.getDefault()

    override fun string(id: Int): String = app.resources.getString(id)

    override fun format(id: Int, args: Array<out Any?>): String = app.resources.getString(id, *args)

    override fun plural(id: Int, count: Int, args: Array<out Any?>): String =
        pluralResources().getQuantityString(id, count, *args)

    /**
     * Android picks a plural form by the rules of the configuration's first language, even when that language has
     * no pack and the words on screen are English: a Ukrainian phone would read "21 day", because Ukrainian's `one`
     * takes 21. Every pack names the language it is written in (`format_language`); when that is not the
     * configuration's language, the form is picked from resources configured for the written language, so English
     * words always follow English rules and Russian words Russian ones.
     */
    private fun pluralResources(): Resources {
        val resources = app.resources
        val written = resources.getString(R.string.format_language)
        if (resources.configuration.locales[0]?.language == written) return resources
        return writtenIn.getOrPut(written) {
            val config = Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(written)) }
            app.createConfigurationContext(config).resources
        }
    }
}
