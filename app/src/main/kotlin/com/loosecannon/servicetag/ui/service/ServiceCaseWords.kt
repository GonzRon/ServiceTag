package com.loosecannon.servicetag.ui.service

import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.CaseType

// ------------------------------------------------------------------------------------------------
// #79 (Part B) — the service case surfaces' words, RATIFIED verbatim (plan §6, R79-21), each by its
// P79 id and each in exactly one home. P79-35 and P79-60 are active; P79-38 is `Open`, which it shares
// with the notification's `ACTION_OPEN` (never a button here). The reused words are drawn through
// their shipped homes.
// ------------------------------------------------------------------------------------------------

/** P79-15, the asset detail's section (`SectionHeader`, drawn upper-case). */
const val SERVICE_CASES = "Service cases"

/** P79-16, its empty state. */
const val NO_SERVICE_CASES_YET = "No service cases yet"

/** P79-17, the open count at one; hidden at zero. */
const val ONE_OPEN_SERVICE_CASE = "1 open service case"

/** P79-17 / P79-18, "<n> open service cases": the line above the rows, or null at zero (hidden). */
fun openServiceCasesLine(open: Int): String? = when {
    open <= 0 -> null
    open == 1 -> ONE_OPEN_SERVICE_CASE
    else -> "$open open service cases"
}

/** P79-19, the detail's action and the new-case editor's title. */
const val NEW_SERVICE_CASE = "New service case"

/** P79-20, the Incident detail's menu item, after Edit. */
const val START_SERVICE_CASE = "Start service case"

/** P79-60 (R79-4), the Incident delete confirm's second line, after "Its readings go with it.". */
const val A_SERVICE_CASE_LINKS_THIS_ENTRY = "A service case links this entry. Its documents go with it."

/** P79-21, the case screen's title and the editor's title when editing. */
const val SERVICE_CASE = "Service case"

/** P79-22, the editor's title field. */
const val CASE_TITLE = "Title"

/** P79-23, the label over the type chips. */
const val CASE_TYPE = "Type"

/** P79-24 … P79-26, the type chips. */
const val WARRANTY_SERVICE = "Warranty service"
const val REPAIR = "Repair"
const val OTHER_SERVICE = "Other service"

/** P79-27 … P79-30, the editor's fields. */
const val OPENED_ON = "Opened on"
const val SERVICE_PROVIDER = "Service provider"
const val PHONE_OR_CONTACT = "Phone or contact"
const val CASE_OR_RMA_NUMBER = "Case or RMA number"

/** P79-31, the label over the coverage chips. */
const val COVERAGE = "Coverage"

/** P79-32 … P79-35, the coverage chips and a row's coverage word (P79-35 under R79-6). */
const val COVERAGE_IN_WARRANTY = "In warranty"
const val COVERAGE_OUT_OF_WARRANTY = "Out of warranty"
const val COVERAGE_UNKNOWN = "Unknown"
const val PARTLY_COVERED = "Partly covered"

/** P79-36, under Coverage on a new case only: the suggestion is a form default (C15). */
const val SUGGESTED_FROM_THE_WARRANTY_DATE = "Suggested from the warranty date. Change it if the provider decides otherwise."

/** P79-44 … P79-47: the two legs' tracking, the carrier (both legs, one home) and the cost. */
const val OUTBOUND_TRACKING = "Outbound tracking"
const val RETURN_TRACKING = "Return tracking"
const val CARRIER = "Carrier"
const val COST = "Cost"

/** P79-48, a cost with no currency. */
const val A_COST_NEEDS_A_CURRENCY = "A cost needs a currency"

/** P79-49. */
const val COST_CANNOT_BE_NEGATIVE = "Cost cannot be negative"

/** P79-62, "Enter a cost like <example>": the price's own form, "123.45" for a two-digit currency. */
fun costExample(digits: Int): String =
    "Enter a cost like " + if (digits <= 0) "123" else "123." + "456789".take(digits)

/** P79-51, the editor's save. */
const val SAVE_CASE = "Save case"

/** P79-52, the title's refusal. */
const val GIVE_THE_CASE_A_TITLE = "Give the case a title"

/** P79-61, an unexpected save failure (logged), as "Could not save this asset.". */
const val COULD_NOT_SAVE_THIS_CASE = "Could not save this case."

/** P79-37, the case fact and the label over the update sheet's status chips. */
const val CASE_STATUS = "Status"

/** P79-38 … P79-43, the six status words: value text, chips and a row's word. */
const val STATUS_OPEN = "Open"
const val STATUS_SENT_OUT = "Sent out"
const val STATUS_AT_THE_SERVICE_CENTER = "At the service center"
const val STATUS_RETURNED = "Returned"
const val STATUS_CLOSED = "Closed"
const val STATUS_CANCELLED = "Cancelled"

/** P79-50, the case fact, when set. */
const val CLOSED_ON = "Closed on"

/** P79-53, the case screen's section. */
const val TIMELINE = "Timeline"

/** P79-54, the case screen's action and the update sheet's title. */
const val ADD_UPDATE = "Add update"

/** P79-55, the sheet's note. */
const val WHAT_HAPPENED = "What happened"

/** P79-56, the sheet's save: enabled only with a note or a status. */
const val SAVE_UPDATE = "Save update"

/** P79-57, the originating Incident's link row. */
const val INCIDENT_LINK = "Incident"

/** P79-58, the resolving event's link row. */
const val REPAIR_RECORD = "Repair record"

/** P79-59, the case action and the picker's title (R79-10: link-only). */
const val LINK_REPAIR_RECORD = "Link repair record"

/** A type's chip word. */
fun caseTypeWord(type: CaseType): String = when (type) {
    CaseType.WARRANTY_SERVICE -> WARRANTY_SERVICE
    CaseType.REPAIR -> REPAIR
    CaseType.OTHER_SERVICE -> OTHER_SERVICE
}

/** A coverage's chip and row word. */
fun coverageWord(coverage: CaseCoverage): String = when (coverage) {
    CaseCoverage.IN_WARRANTY -> COVERAGE_IN_WARRANTY
    CaseCoverage.OUT_OF_WARRANTY -> COVERAGE_OUT_OF_WARRANTY
    CaseCoverage.UNKNOWN -> COVERAGE_UNKNOWN
    CaseCoverage.PARTLY_COVERED -> PARTLY_COVERED
}

/** A status's word: the case fact, a chip and a row's word. */
fun caseStatusWord(status: CaseStatus): String = when (status) {
    CaseStatus.OPEN -> STATUS_OPEN
    CaseStatus.SENT_OUT -> STATUS_SENT_OUT
    CaseStatus.AT_SERVICE_CENTER -> STATUS_AT_THE_SERVICE_CENTER
    CaseStatus.RETURNED -> STATUS_RETURNED
    CaseStatus.CLOSED -> STATUS_CLOSED
    CaseStatus.CANCELLED -> STATUS_CANCELLED
}

/** The chips' order: the enum's, which is the ratified order of §6. */
val CASE_TYPES: List<CaseType> = CaseType.entries
val CASE_COVERAGES: List<CaseCoverage> = listOf(
    CaseCoverage.IN_WARRANTY,
    CaseCoverage.OUT_OF_WARRANTY,
    CaseCoverage.UNKNOWN,
    CaseCoverage.PARTLY_COVERED,
)
val CASE_STATUSES: List<CaseStatus> = CaseStatus.entries
