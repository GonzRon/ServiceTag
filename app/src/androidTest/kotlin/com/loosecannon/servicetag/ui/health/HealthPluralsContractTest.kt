package com.loosecannon.servicetag.ui.health

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
