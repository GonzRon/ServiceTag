package com.loosecannon.servicetag.reminders

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.DeadlineLocalDelivery
import com.loosecannon.servicetag.core.ports.DeadlineLocalDeliveryRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.reminders.DeadlineKind
import com.loosecannon.servicetag.core.reminders.SubjectKey

/** #79 (C6): what a deadline's warning needs about its subject that the port does not carry. */
fun interface DeadlineFactsSource {
    suspend fun factsFor(key: SubjectKey.Deadline): DeadlineFacts?
}

/**
 * The [DeadlineFactsSource] over the real store: a warranty's owner is its asset, named in the
 * title's `<asset>` slot, and the window is measured on [Today] — the same day the sweep built the
 * subjects on. An asset gone between the list being built and this read has no facts, and its
 * warning is forgotten.
 */
class DeadlineDeliveryFacts(
    private val assets: AssetRepository,
    private val today: Today,
) : DeadlineFactsSource {
    override suspend fun factsFor(key: SubjectKey.Deadline): DeadlineFacts? = when (key.kind) {
        DeadlineKind.WARRANTY_EXPIRY ->
            assets.get(AssetId(key.subjectId))?.let { DeadlineFacts(it.name, today.localDate()) }
    }
}

/**
 * The stamps of a provider handed no deadline facts: it never announces a warning, so it has
 * nothing to remember and nothing to forget.
 */
internal object NoDeadlineStamps : DeadlineLocalDeliveryRepository {
    override suspend fun get(kind: String, subjectId: String): DeadlineLocalDelivery? = null
    override suspend fun upsert(row: DeadlineLocalDelivery) = Unit
    override suspend fun delete(kind: String, subjectId: String) = Unit
    override suspend fun all(): List<DeadlineLocalDelivery> = emptyList()
    override suspend fun deleteAll() = Unit
}
