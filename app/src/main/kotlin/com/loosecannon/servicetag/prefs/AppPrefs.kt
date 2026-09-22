package com.loosecannon.servicetag.prefs

import android.content.Context

interface KeyValueStore {
    fun getLong(key: String): Long?
    fun putLong(key: String, value: Long)
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

class SharedPrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences("servicetag", Context.MODE_PRIVATE)
    override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
}

enum class AppearanceMode { SYSTEM, LIGHT, DARK }

/** The few per-install facts the UI needs that are not domain data. Backed up? No — they are device-local by design. */
class AppPrefs(private val store: KeyValueStore) {
    val lastBackupAt: Long? get() = store.getLong(KEY_LAST_BACKUP)
    fun markBackupExported(now: Long) = store.putLong(KEY_LAST_BACKUP, now)
    var appearanceMode: AppearanceMode
        get() = store.getString(KEY_APPEARANCE)?.let { runCatching { AppearanceMode.valueOf(it) }.getOrNull() } ?: AppearanceMode.SYSTEM
        set(value) = store.putString(KEY_APPEARANCE, value.name)

    /** The SAF tree the owner chose for attachments. Device-local, never in a backup. */
    var attachmentTreeUri: String?
        get() = store.getString(KEY_ATTACHMENT_TREE).orNullIfBlank()
        set(value) = store.putString(KEY_ATTACHMENT_TREE, value ?: "")

    /** The set id of the last data archive restored, so a later artifacts restore can refuse (spec 7.3). */
    var lastRestoredBackupSetId: String?
        get() = store.getString(KEY_LAST_RESTORED_SET).orNullIfBlank()
        set(value) = store.putString(KEY_LAST_RESTORED_SET, value ?: "")

    /**
     * The hour of the device-local day the digest alarm is armed for (#21, spec 5.6, D-5). **9**,
     * i.e. 09:00 local, until the owner changes it.
     *
     * An hour and not an instant, because the digest is a date-shaped obligation: a zone change
     * moves *when* the phone says 09:00 and never *what* is due. An out-of-range stored value is
     * read as the default rather than repaired, so a hand-edited preferences file cannot make
     * `LocalTime.of` throw inside an alarm arm.
     */
    var digestHour: Int
        get() = store.getLong(KEY_DIGEST_HOUR)?.toInt()?.takeIf { it in 0..23 } ?: DEFAULT_DIGEST_HOUR
        set(value) = store.putLong(KEY_DIGEST_HOUR, value.coerceIn(0, 23).toLong())

    /**
     * The global reminders switch the `REMINDERS_GLOBALLY_OFF` finding reads (spec 5.6, 5.8).
     * **On** until the owner switches it off — an install that has never touched it reminds.
     *
     * Switching it off silences delivery and nothing else: the alarm stays armed, the backstop
     * stays enqueued and every recompute still runs, exactly as a denied permission leaves them
     * (D-22). [KeyValueStore] stores longs and strings, so the flag is 0 or 1 rather than a third
     * accessor pair on a store four other values already share.
     */
    var remindersEnabled: Boolean
        get() = (store.getLong(KEY_REMINDERS_ENABLED) ?: 1L) != 0L
        set(value) = store.putLong(KEY_REMINDERS_ENABLED, if (value) 1L else 0L)

    private companion object {
        const val KEY_LAST_BACKUP = "last_backup_at"
        const val KEY_APPEARANCE = "appearance_mode"
        const val KEY_ATTACHMENT_TREE = "attachment_tree_uri"
        const val KEY_LAST_RESTORED_SET = "last_restored_backup_set_id"
        const val KEY_DIGEST_HOUR = "reminder_digest_hour"
        const val KEY_REMINDERS_ENABLED = "reminders_enabled"

        /** 09:00 local (D-5, spec 5.6). */
        const val DEFAULT_DIGEST_HOUR = 9
    }
}

/** `KeyValueStore.putString` cannot store null, so clearing writes "": one state, not two. */
private fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
