package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.reminders.HealthFinding
import com.loosecannon.servicetag.core.reminders.ProviderId
import com.loosecannon.servicetag.core.reminders.ReconcileReport
import com.loosecannon.servicetag.core.reminders.ReminderProvider
import com.loosecannon.servicetag.core.reminders.ReminderSubject
import com.loosecannon.servicetag.core.reminders.RemoteChange
import com.loosecannon.servicetag.core.reminders.SubjectKey

/**
 * A provider that holds nothing but what it was last told to hold, and remembers every call.
 *
 * It is the whole of the port's behavioural contract with none of a real provider's machinery: what
 * it "holds" is a map from subject to the content hash of the version it holds, which is the only
 * state `reconcile` needs to answer `posted`, `cleared` and `unchanged` honestly. That is also why
 * a second reconcile of the same list is provably inert here — nothing about the fake could make it
 * otherwise, which is what makes it a fair witness for invariant 45.
 *
 * [calls] records every list, in order, so a test can assert on what a caller *asked* for as well
 * as on what came back.
 */
class FakeReminderProvider(
    override val id: ProviderId = ProviderId.LOCAL,
    private val findings: List<HealthFinding> = emptyList(),
) : ReminderProvider {

    val calls = mutableListOf<List<ReminderSubject>>()

    /** Subject to the content hash of the version this provider is currently holding. */
    val held = LinkedHashMap<SubjectKey, String>()

    override suspend fun reconcile(subjects: List<ReminderSubject>): ReconcileReport {
        calls += subjects
        val wanted = subjects.associate { it.key to it.contentHash }
        val unchanged = wanted.count { (key, hash) -> held[key] == hash }
        val cleared = held.keys.count { it !in wanted }
        held.clear()
        held.putAll(wanted)
        return ReconcileReport(
            posted = wanted.size - unchanged,
            cleared = cleared,
            unchanged = unchanged,
            problems = emptyList(),
        )
    }

    /** Nothing changes on this side but what [reconcile] put there. */
    override suspend fun pullChanges(): List<RemoteChange> = emptyList()

    override suspend fun health(): List<HealthFinding> = findings
}
