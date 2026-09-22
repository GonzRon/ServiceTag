package com.loosecannon.servicetag.reminders

import com.loosecannon.servicetag.prefs.AppPrefs
import com.loosecannon.servicetag.prefs.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A `Map`-backed [KeyValueStore], the same shape `AppPrefsTest` already uses. */
private class MapStore : KeyValueStore {
    val longs = mutableMapOf<String, Long>()
    val strings = mutableMapOf<String, String>()
    override fun getLong(key: String): Long? = longs[key]
    override fun putLong(key: String, value: Long) { longs[key] = value }
    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String) { strings[key] = value }
}

/**
 * The two preferences #21 cannot work without, and the assertion that there are only two.
 *
 * The defaults are the interesting half: a phone that has never opened a reminder setting must
 * still remind, and it must remind at the hour D-5 fixed. A default of "off" or of hour zero is a
 * silent install, and neither would fail any behavioural test that set the value first.
 */
class ReminderPrefsTest {

    @Test
    fun theDigestHourDefaultsToNineAndRoundTrips() {
        val store = MapStore()
        val prefs = AppPrefs(store)

        assertEquals("09:00 local until the owner changes it (D-5, spec 5.6)", 9, prefs.digestHour)

        prefs.digestHour = 18
        assertEquals(18, prefs.digestHour)
        assertEquals(18L, store.longs["reminder_digest_hour"])
    }

    /**
     * An hour outside 0..23 would make `LocalTime.of` throw inside `arm()` — on the boot path,
     * where nothing is watching. Writing clamps and reading falls back, so neither a caller nor a
     * hand-edited preferences file can put the alarm in that position.
     */
    @Test
    fun anOutOfRangeDigestHourIsNeverHandedToTheAlarm() {
        val store = MapStore()
        val prefs = AppPrefs(store)

        prefs.digestHour = 27
        assertEquals(23, prefs.digestHour)
        prefs.digestHour = -4
        assertEquals(0, prefs.digestHour)

        store.longs["reminder_digest_hour"] = 99L
        assertEquals("a stored value out of range reads as the default", 9, prefs.digestHour)
    }

    @Test
    fun remindersAreOnUntilTheOwnerSwitchesThemOff() {
        val store = MapStore()
        val prefs = AppPrefs(store)

        assertTrue("an install that never touched the switch still reminds", prefs.remindersEnabled)

        prefs.remindersEnabled = false
        assertFalse(prefs.remindersEnabled)
        assertEquals(0L, store.longs["reminders_enabled"])

        prefs.remindersEnabled = true
        assertTrue(prefs.remindersEnabled)
    }

    /**
     * The matrix's "too many preferences" row. 1.2 is not #18: the preferences screen and every
     * setting that would belong on it stay out, and this brief was given exactly two values. The
     * shipped four plus these two is six — a count read off the source, because no runtime
     * assertion can see a seventh key nothing happens to read yet.
     */
    @Test
    fun appPrefsGainedExactlyTwoKeys() {
        val keys = Regex("""(?m)^\s*const val (KEY_\w+) = "([^"]+)"""")
            .findAll(sourceFile("kotlin/com/loosecannon/servicetag/prefs/AppPrefs.kt").readText())
            .map { it.groupValues[2] }
            .toList()

        assertEquals(
            listOf(
                "last_backup_at",
                "appearance_mode",
                "attachment_tree_uri",
                "last_restored_backup_set_id",
                "reminder_digest_hour",
                "reminders_enabled",
            ),
            keys,
        )
    }
}
