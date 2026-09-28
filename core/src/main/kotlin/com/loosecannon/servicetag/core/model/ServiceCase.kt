package com.loosecannon.servicetag.core.model

@JvmInline
value class ServiceCaseId(val value: String)

@JvmInline
value class ServiceCaseEntryId(val value: String)

/** What kind of outside service a case is (#79, AC 6). */
enum class CaseType { WARRANTY_SERVICE, REPAIR, OTHER_SERVICE }

/**
 * Who pays, as the owner states it (#79, AC 7; R79-6). Four values: a stored fact, never derived — the
 * warranty date only **suggests** one when a case is opened (`suggestCoverage`), and the owner may say
 * otherwise. [PARTLY_COVERED] is the owner's answer when the warranty pays for some of it.
 */
enum class CaseCoverage { IN_WARRANTY, OUT_OF_WARRANTY, UNKNOWN, PARTLY_COVERED }

/**
 * Where a case stands (#79, R79-5). Six values. [CLOSED] is resolved and [CANCELLED] is abandoned —
 * two different exits, since a case is never deleted (R79-9). The stored status moves **only** by a
 * status entry on the case's timeline.
 */
enum class CaseStatus {
    OPEN, SENT_OUT, AT_SERVICE_CENTER, RETURNED, CLOSED, CANCELLED;

    /** A case in either exit carries `closedOn`; one in any other status does not. */
    val isTerminal: Boolean get() = this == CLOSED || this == CANCELLED
}

/**
 * One outside-service case on an asset (#79, R79-1): the **header** of an aggregate whose timeline is
 * [ServiceCaseEntry]. The header is mutable, as an asset is — [updatedAt] moves with every write —
 * but [status] and [closedOn] are moved only by a status entry, never by an edit (R79-5, R79-9).
 * Empty text is `""`.
 *
 * [incidentEventId] and [resolutionEventId] are **soft links** (R79-4, R79-10): no foreign key holds
 * either, so a deleted event leaves a readable dangling id, and a case never writes the events it
 * names. [costMinor] is in [currency]'s minor units: null is no cost recorded, 0 is no charge (R79-7).
 * A case never writes a condition, an event, a schedule or a completion.
 */
data class ServiceCase(
    val id: ServiceCaseId,
    val assetId: AssetId,
    val title: String,
    val type: CaseType,
    /** ISO `YYYY-MM-DD`. */
    val openedOn: String,
    /** ISO `YYYY-MM-DD`; set exactly when [status] is CLOSED or CANCELLED. */
    val closedOn: String?,
    val provider: String,
    val contact: String,
    /** The provider's case or RMA number. */
    val caseRef: String,
    val coverage: CaseCoverage,
    val status: CaseStatus,
    val outboundTracking: String,
    val outboundCarrier: String,
    val returnTracking: String,
    val returnCarrier: String,
    val costMinor: Long?,
    val currency: String?,
    val notes: String,
    val incidentEventId: EventId?,
    val resolutionEventId: EventId?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One fact on a case's timeline (#79, R79-8): **immutable**, the `asset_condition` precedent. There is
 * no `updatedAt`; nothing amends or deletes a row, and a row leaves only by its case's CASCADE. A
 * [status] entry is also what moved the case's stored status; a note-only entry ([status] null)
 * touches nothing but its own row.
 *
 * The timeline order is `(occurredOn, occurredTime nulls first, createdAt, id)`.
 */
data class ServiceCaseEntry(
    val id: ServiceCaseEntryId,
    val caseId: ServiceCaseId,
    /** ISO `YYYY-MM-DD`. */
    val occurredOn: String,
    /** `HH:MM`, or null when only the day is known. */
    val occurredTime: String?,
    val tzId: String,
    /** May be empty only when [status] is set. */
    val note: String,
    val status: CaseStatus?,
    val createdAt: Long,
)
