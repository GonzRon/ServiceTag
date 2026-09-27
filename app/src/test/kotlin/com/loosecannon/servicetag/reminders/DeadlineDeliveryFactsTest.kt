package com.loosecannon.servicetag.reminders

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.reminders.DeadlineKind
import com.loosecannon.servicetag.core.reminders.SubjectKey
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #79 (C6): the facts a warranty warning is built from — the asset's name and today — or none once it is gone. */
class DeadlineDeliveryFactsTest {

    @Test
    fun theOwnerIsTheAssetsNameTodayIsTodaysAndAGoneAssetHasNone() = runTest {
        val assets = FakeAssetRepository()
        assets.upsert(Asset(id = AssetId("a1"), name = "Example Heater", createdAt = 1_000L, updatedAt = 1_000L))
        val facts = DeadlineDeliveryFacts(assets, Today { LocalDate.parse("2031-06-10") })

        assertEquals(
            DeadlineFacts("Example Heater", LocalDate.parse("2031-06-10")),
            facts.factsFor(SubjectKey.Deadline(DeadlineKind.WARRANTY_EXPIRY, "a1")),
        )
        assertNull(facts.factsFor(SubjectKey.Deadline(DeadlineKind.WARRANTY_EXPIRY, "gone")))
    }
}
