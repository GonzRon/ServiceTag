package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.hapublish.PublishAction
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.RemoteEntity
import com.loosecannon.servicetag.core.hapublish.VALIDATION_MAX_AGE_MILLIS
import com.loosecannon.servicetag.core.hapublish.ValidationResult
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.hapublish.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #113/#114 (C35b, C25a; B5w1 review N5) — the plan store's own checks, apart from the core's: a held plan is found
 * only for its own asset, kind and action and before it expires, and the seventeenth plan evicts the oldest. The
 * route tests prove the same 409 `STALE_PLAN` but the core's recomputed plan id would answer it too, so this pins the
 * store itself. Every name is fictional.
 */
class HaPublishingPlansTest {

    private var now = 1_000_000L
    private val plans = HaPublishingPlans(Clock { now })

    private fun plan(assetId: AssetId = HEATER, kind: PublishKind = PublishKind.EQUIPMENT_STATUS) = HaPublishingPlans.Plan(
        assetId, kind, PublishAction.ENABLE, STATUS,
        Validation.Validated(
            ValidationResult(STATUS, RemoteEntity(STATUS, "UP", listOf("UP", "DEGRADED", "DOWN"), null, null), "example-connection", 1, 0, now),
            null,
        ),
    )

    @Test fun aHeldPlanIsFoundOnlyForItsOwnAssetKindAndAction() {
        val held = plan()
        plans.put("example-plan", held)
        assertEquals(held, plans.find("example-plan", HEATER, PublishKind.EQUIPMENT_STATUS, PublishAction.ENABLE))
        assertNull("another asset", plans.find("example-plan", BOILER, PublishKind.EQUIPMENT_STATUS, PublishAction.ENABLE))
        assertNull("another kind", plans.find("example-plan", HEATER, PublishKind.SEASON_SERVICE_DATE, PublishAction.ENABLE))
        assertNull("another action", plans.find("example-plan", HEATER, PublishKind.EQUIPMENT_STATUS, PublishAction.CHANGE_TARGET))
        assertNull("an unknown id", plans.find("example-other", HEATER, PublishKind.EQUIPMENT_STATUS, PublishAction.ENABLE))
        assertEquals("asking removes nothing", 1, plans.count())
    }

    @Test fun aPlanExpiresTenMinutesAfterItsRead() {
        val held = plan()
        plans.put("example-plan", held)
        now = held.expiresAt - 1
        assertNotNull(plans.find("example-plan", HEATER, PublishKind.EQUIPMENT_STATUS, PublishAction.ENABLE))
        now = held.validated.target.readAt + VALIDATION_MAX_AGE_MILLIS
        assertEquals(held.expiresAt, now)
        assertNull("expired at expiresAt", plans.find("example-plan", HEATER, PublishKind.EQUIPMENT_STATUS, PublishAction.ENABLE))
    }

    @Test fun theSeventeenthPutEvictsTheOldest() {
        repeat(HaPublishingPlans.CAPACITY + 1) { plans.put("example-plan-$it", plan()) }
        assertEquals(HaPublishingPlans.CAPACITY, plans.count())
        assertNull("the oldest went", plans.find("example-plan-0", HEATER, PublishKind.EQUIPMENT_STATUS, PublishAction.ENABLE))
        assertNotNull(plans.find("example-plan-1", HEATER, PublishKind.EQUIPMENT_STATUS, PublishAction.ENABLE))
        assertNotNull(plans.find("example-plan-16", HEATER, PublishKind.EQUIPMENT_STATUS, PublishAction.ENABLE))
    }

    private companion object {
        const val STATUS = "input_select.example_heater_equipment_status"
        val HEATER = AssetId("example-heater")
        val BOILER = AssetId("example-boiler")
    }
}
