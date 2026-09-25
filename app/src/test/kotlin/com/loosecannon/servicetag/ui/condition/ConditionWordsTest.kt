package com.loosecannon.servicetag.ui.condition

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.ui.health.ComponentCondition
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The condition words are the ratified strings of spec §10.7, by number and verbatim — the one place
 * every surface reads them from, so a drifted word here is a drifted word everywhere.
 */
class ConditionWordsTest {

    private fun battery(condition: OperationalCondition, reason: String) = ComponentCondition(
        assetId = AssetId("pack"),
        name = "Battery pack",
        condition = condition,
        reason = reason,
        occurredOn = LocalDate.parse("2026-04-02"),
    )

    @Test fun everyWordIsItsRatifiedString() {
        // S1–S4.
        assertEquals("OPERATIONAL", conditionWord(OperationalCondition.OPERATIONAL))
        assertEquals("DEGRADED", conditionWord(OperationalCondition.DEGRADED))
        assertEquals("DOWN", conditionWord(OperationalCondition.DOWN))
        assertEquals("Condition not recorded", conditionWord(null))

        // S8–S13: each option with its helper.
        assertEquals("Operational", conditionOption(OperationalCondition.OPERATIONAL))
        assertEquals("Available for normal use.", conditionHelper(OperationalCondition.OPERATIONAL))
        assertEquals("Degraded", conditionOption(OperationalCondition.DEGRADED))
        assertEquals("Works, but with a known problem.", conditionHelper(OperationalCondition.DEGRADED))
        assertEquals("Down", conditionOption(OperationalCondition.DOWN))
        assertEquals("Not available for its intended use.", conditionHelper(OperationalCondition.DOWN))

        // S22, with the shipped display shape.
        assertEquals("since 2 Apr 2026", sinceLine(LocalDate.parse("2026-04-02"), ::displayDate))

        // S23, wherever a reason would appear.
        assertEquals("No reason given", reasonLine(""))
        assertEquals("Battery failed", reasonLine("Battery failed"))

        // S27, with a reason and with an empty one (S23 in its place).
        assertEquals(
            "Battery pack DOWN — Cells swollen",
            componentLine(battery(OperationalCondition.DOWN, "Cells swollen")),
        )
        assertEquals(
            "Battery pack DEGRADED — No reason given",
            componentLine(battery(OperationalCondition.DEGRADED, "")),
        )
    }
}
