package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.usecase.SeasonStartRefusal
import com.loosecannon.servicetag.core.usecase.SeasonStartRefused
import com.loosecannon.servicetag.core.usecase.SeasonStartStrandCause
import com.loosecannon.servicetag.core.usecase.SeasonStartStrands
import com.loosecannon.servicetag.core.usecase.StrandedSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #114 (A2; C2) — the two Season Start codes each have their own `is` arm in `mapDomainFailure`, never the `else`
 * (#47 E-18): 409 `SEASON_START_REFUSED` naming the `SeasonStartRefusal` and 409 `SEASON_START_STRANDED` naming the
 * `SeasonStartStrandCause` and the role schedules, `SEASON_MODE_STRANDS_POLICY`'s shape. A4 and A4w document them.
 */
class SeasonStartCodesTest {

    private fun error(e: Exception, status: Int): ApiErrorDetail {
        val response = mapDomainFailure(e)
        assertEquals(status, response.status)
        return ApiJson.decodeFromString(ApiErrorBody.serializer(), response.body.decodeToString()).error
    }

    @Test fun everyRefusalIs409SeasonStartRefusedByItsName() {
        val heater = AssetId("heater")
        val cases = listOf(
            SeasonStartRefusal.ScheduleArchived to "SeasonStartRefused(reason=SCHEDULE_ARCHIVED)",
            SeasonStartRefusal.QuickNotElected to "SeasonStartRefused(reason=QUICK_NOT_ELECTED)",
            SeasonStartRefusal.NotMaintainedHere(heater) to "SeasonStartRefused(reason=NOT_MAINTAINED_HERE, assetId=heater)",
            SeasonStartRefusal.YearRound(heater) to "SeasonStartRefused(reason=YEAR_ROUND, assetId=heater)",
            SeasonStartRefusal.LeafChild(heater) to "SeasonStartRefused(reason=LEAF_CHILD, assetId=heater)",
            SeasonStartRefusal.AlreadyCovered(heater, ScheduleId("s1"), "Example annual service") to
                "SeasonStartRefused(reason=ALREADY_COVERED, assetId=heater, scheduleId=s1, title=Example annual service)",
            SeasonStartRefusal.GroupArchived to "SeasonStartRefused(reason=GROUP_ARCHIVED)",
            SeasonStartRefusal.GroupHasNoMembers to "SeasonStartRefused(reason=GROUP_HAS_NO_MEMBERS)",
            SeasonStartRefusal.DateInFuture to "SeasonStartRefused(reason=DATE_IN_FUTURE)",
        )
        for ((reason, named) in cases) {
            val error = error(SeasonStartRefused(reason), 409)
            assertEquals("SEASON_START_REFUSED", error.code)
            assertEquals(listOf(named), error.problems)
            assertNull(error.field)
        }
    }

    @Test fun everyStrandIs409SeasonStartStrandedNamingTheCauseAndTheSchedules() {
        val schedules = listOf(StrandedSchedule(ScheduleId("s1"), "Example annual service"))
        for (cause in SeasonStartStrandCause.entries) {
            val error = error(SeasonStartStrands(AssetId("heater"), cause, schedules), 409)
            assertEquals("SEASON_START_STRANDED", error.code)
            assertEquals(
                listOf(
                    "SeasonStartStrands(cause=${cause.name}, assetId=heater)",
                    "StrandedSchedule(id=s1, title=Example annual service)",
                ),
                error.problems,
            )
            assertNull(error.field)
        }
        val group = error(SeasonStartStrands(null, SeasonStartStrandCause.ROLE_SCHEDULE_RETARGET, schedules), 409)
        assertEquals("a group schedule names no asset", "SeasonStartStrands(cause=ROLE_SCHEDULE_RETARGET)", group.problems.first())
    }
}
