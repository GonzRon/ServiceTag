package com.loosecannon.servicetag.ui.health

import android.content.res.Configuration
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.HealthSubjectCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.condition.displayDate
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The plurals resource behind S99's `<age>` and S102 (plan decision 29), as a **framework contract**
 * with no navigation: [AndroidHealthPlurals] over the **app's** resources — the target context's, not
 * the test APK's — says "1 day" for one and the ratified "`<n>` days" otherwise. The JVM suites
 * cannot see `plurals.xml`; this is the one place it is read for real.
 *
 * Emulator only — the second case wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class HealthPluralsContractTest {

    @get:Rule val rule = createComposeRule()

    @Before fun freshInstall() = clearInstall()

    private val plurals: AndroidHealthPlurals
        get() = AndroidHealthPlurals(InstrumentationRegistry.getInstrumentation().targetContext.resources)

    @Test fun androidHealthPluralsSaysOneDayAndNDays() {
        assertEquals("1 day", plurals.ageDays(1))
        assertEquals("5 days", plurals.ageDays(5))
        assertEquals("Oil change is 1 day overdue", plurals.daysOverdue("Oil change", 1))
        assertEquals("Oil change is 5 days overdue", plurals.daysOverdue("Oil change", 5))

        // The plurals resource names the same two forms: under English rules it reads exactly the same.
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(target.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val english = target.createConfigurationContext(config).resources
        listOf(1, 5).forEach { n ->
            assertEquals(english.getQuantityString(R.plurals.health_age_days, n, n), plurals.ageDays(n.toLong()))
            assertEquals(
                english.getQuantityString(R.plurals.health_days_overdue, n, "Oil change", n),
                plurals.daysOverdue("Oil change", n.toLong()),
            )
        }
    }

    /**
     * The ruling on B12's review, I-2: the form is chosen by **English** rules whatever the device
     * language, because the ratified strings are English. French puts 0 in `one`, Russian puts 21 in
     * `one`, and Japanese has no `one` at all — so on each, 0 still reads "0 days", 21 "21 days" and
     * 1 "1 day". The reader changes nothing in the resources it is given: the rest of them stay in
     * their own language.
     */
    @Test fun theFormFollowsEnglishRulesOnAnyDeviceLanguage() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        listOf(Locale.FRENCH, Locale.forLanguageTag("ru"), Locale.JAPANESE).forEach { locale ->
            val config = Configuration(target.resources.configuration).apply { setLocale(locale) }
            val local = target.createConfigurationContext(config).resources
            val cancel = local.getString(android.R.string.cancel)
            assertNotEquals("$locale: the probe string is translated", "Cancel", cancel)

            val words = AndroidHealthPlurals(local)

            assertEquals("$locale", "0 days", words.ageDays(0))
            assertEquals("$locale", "1 day", words.ageDays(1))
            assertEquals("$locale", "21 days", words.ageDays(21))
            assertEquals("$locale", "Oil change is 1 day overdue", words.daysOverdue("Oil change", 1))
            assertEquals("$locale", "Oil change is 21 days overdue", words.daysOverdue("Oil change", 21))
            assertEquals("$locale: the rest keeps its language", cancel, local.getString(android.R.string.cancel))
        }
    }

    /**
     * The carry-forward from B05's review: a replacement dated **after today** reads as age 0 in the
     * engine, so S99 draws "0 days ago" through the plurals resource — never a negative age. The line
     * comes through the real read path and is drawn on a real Compose tree.
     */
    @Test fun aFutureDatedReplacementDrawsZeroDaysAgo() {
        val graph = app.graph
        val tomorrow = LocalDate.now().plusDays(1)
        val line = runBlocking {
            val ups = graph.createAsset.run(AssetCommand(name = "UPS", category = "Power")).id
            graph.saveHealthSubject.create(
                ups,
                HealthSubjectCommand(
                    name = "Battery", kind = HealthSubjectKind.PART, driver = HealthDriver.AGE,
                    nominalUntilDays = 0, warningFromDays = 40, criticalFromDays = 75,
                ),
            )
            graph.logEvent.run(
                EventCommand(
                    assetId = ups, profileId = null, kind = EventKind.REPLACEMENT, title = "Battery replaced",
                    occurredOn = tomorrow.toString(), occurredTime = null, tzId = ZoneId.systemDefault().id,
                    notes = "", values = emptyMap(), consumables = emptyList(),
                ),
            )
            graph.assetHealthReadModel.forAsset(ups).result.subjects.single().lines.single()
        }
        val text = driverLineText(line, plurals, ::displayDate)!!

        rule.setContent { ServiceTagTheme { Text(text) } }

        rule.onNodeWithText("Replaced ${displayDate(tomorrow)}, 0 days ago").assertIsDisplayed()
    }
}
