package com.loosecannon.servicetag.ui.nav

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.loosecannon.servicetag.MainActivity
import com.loosecannon.servicetag.ui.awaitText
import org.junit.Rule
import org.junit.Test

/**
 * Compiled in Task 4, executed on the phone in Task 8.
 *
 * The bottom bar has three items as of 1.2 (spec §2.6, the navigation ruling): Dashboard, Assets
 * and Maintenance. Scan is still a pushed destination reached from Settings' "Read / inspect tag"
 * row, not a tab, so it never shows a clickable "Scan" node on the bar. The dashboard's own title
 * is the app's name, which only ever appears once.
 */
class NavigationSmokeTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun dashboardIsTheStartDestination() {
        rule.onNode(hasText("ServiceTag") and hasNoClickAction()).assertIsDisplayed()
    }

    @Test fun bottomBarHasThreeItems() {
        rule.onNode(hasText("Dashboard") and hasClickAction()).assertIsDisplayed()
        rule.onNode(hasText("Assets") and hasClickAction()).assertIsDisplayed()
        rule.onNode(hasText("Maintenance") and hasClickAction()).assertIsDisplayed()
        rule.onAllNodes(hasText("Scan") and hasClickAction()).assertCountEquals(0)
    }

    @Test fun settingsOpensReadInspectTag() {
        rule.onNodeWithContentDescription("Settings").performClick()

        rule.awaitText("Read / inspect tag")
        rule.onNodeWithText("Read / inspect tag").performClick()

        rule.awaitText("READY TO SCAN")
        rule.onNodeWithText("READY TO SCAN").assertIsDisplayed()
    }
}
