package com.loosecannon.servicetag.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins Settings as a door to Backup that never closes. The Dashboard nudge that used to be the
 * only way in disappears once a backup has been recorded (see `DashboardScreen`), so a owner who
 * already has a backup would otherwise have no way back into `Route.Backup` at all. This test
 * pins the row itself — label, position under "Utilities", and that it actually calls `onBackup`
 * — so a future edit to Settings cannot drop the door again without failing here.
 *
 * 1.1.0 (#46) adds a third row, Developer API, and the same reasoning applies twice over: that
 * screen *is* the automation API's lifetime, so a Settings edit that dropped the row would remove
 * the only way to start the listener at all — there is deliberately no deep link to it.
 */
@RunWith(AndroidJUnit4::class)
class SettingsBackupEntryTest {

    @get:Rule val rule = createComposeRule()

    @Test fun backupRowIsPresentAndInvokesOnBackup() {
        val graph = AppGraph(ApplicationProvider.getApplicationContext())
        var backupTapped = 0

        rule.setContent {
            ServiceTagTheme {
                SettingsScreen(
                    graph = graph,
                    onBack = {},
                    onReadTag = {},
                    onBackup = { backupTapped++ },
                    onDeveloperApi = {},
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Backup and restore").assertIsDisplayed()
        rule.onNodeWithText("Backup and restore").performClick()

        assertEquals(1, backupTapped)
    }

    @Test fun developerApiRowIsPresentAndInvokesOnDeveloperApi() {
        val graph = AppGraph(ApplicationProvider.getApplicationContext())
        var apiTapped = 0

        rule.setContent {
            ServiceTagTheme {
                SettingsScreen(
                    graph = graph,
                    onBack = {},
                    onReadTag = {},
                    onBackup = {},
                    onDeveloperApi = { apiTapped++ },
                )
            }
        }
        rule.waitForIdle()

        // `SettingsScreen` is a `verticalScroll` column (`SettingsScreen.kt:164`, `:167`) and this
        // is the third Utilities row, where the About header used to sit — below the fold on a
        // phone. `performScrollTo()` is the house pattern for exactly this (`AppSmokeTest.kt:219`).
        rule.onNodeWithText("Developer API").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Developer API").performScrollTo().performClick()

        assertEquals(1, apiTapped)
    }
}
