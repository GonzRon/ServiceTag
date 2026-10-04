package com.loosecannon.servicetag.l10n

import android.content.Context
import java.util.Locale

/**
 * [LocalizedText] over the application's resources. The resources are looked up on every read, not held: a
 * language change — the device's, or the app's own from Android's per-app language setting — updates the
 * application's configuration in place, and the next read renders in the new language.
 */
class AndroidLocalizedText(context: Context) : LocalizedText {
    private val app: Context = context.applicationContext ?: context

    override val locale: Locale get() = app.resources.configuration.locales[0] ?: Locale.getDefault()

    override fun string(id: Int): String = app.resources.getString(id)

    override fun format(id: Int, args: Array<out Any?>): String = app.resources.getString(id, *args)

    override fun plural(id: Int, count: Int, args: Array<out Any?>): String =
        app.resources.getQuantityString(id, count, *args)
}
