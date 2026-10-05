package com.loosecannon.servicetag.ui.service

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.CaseType
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.l10n.localizedPlural
import com.loosecannon.servicetag.ui.asset.amountExample

// ------------------------------------------------------------------------------------------------
// #79 (Part B) — the service case surfaces' words, RATIFIED verbatim (plan §6, R79-21), each by its
// P79 id and each in exactly one home. P79-35 and P79-60 are active; P79-38 is `Open`, which it shares
// with the notification's `ACTION_OPEN` (never a button here). The reused words are drawn through
// their shipped homes.
//
// #102: the English text lives in `res/values/strings_records.xml` (`service_*`); each name here reads it
// when drawn, so a language pack renders it in the owner's language.
// ------------------------------------------------------------------------------------------------

/** P79-15, the asset detail's section (`SectionHeader`, drawn upper-case). */
val SERVICE_CASES: String get() = localized(R.string.service_cases_section)

/** P79-16, its empty state. */
val NO_SERVICE_CASES_YET: String get() = localized(R.string.service_no_cases_yet)

/** P79-17, the open count at one; hidden at zero. */
val ONE_OPEN_SERVICE_CASE: String get() = localizedPlural(R.plurals.service_open_cases, 1, 1)

/** P79-17 / P79-18, "<n> open service cases": the line above the rows, or null at zero (hidden). */
fun openServiceCasesLine(open: Int): String? =
    if (open <= 0) null else localizedPlural(R.plurals.service_open_cases, open, open)

/** P79-19, the detail's action and the new-case editor's title. */
val NEW_SERVICE_CASE: String get() = localized(R.string.service_new_case)

/** P79-20, the Incident detail's menu item, after Edit. */
val START_SERVICE_CASE: String get() = localized(R.string.service_start_case)

/** P79-60 (R79-4), the Incident delete confirm's second line, after "Its readings go with it.". */
val A_SERVICE_CASE_LINKS_THIS_ENTRY: String get() = localized(R.string.service_case_links_this_entry)

/** P79-21, the case screen's title and the editor's title when editing. */
val SERVICE_CASE: String get() = localized(R.string.service_case_screen_title)

/** P79-22, the editor's title field. */
val CASE_TITLE: String get() = localized(R.string.service_field_title)

/** P79-23, the label over the type chips. */
val CASE_TYPE: String get() = localized(R.string.service_field_type)

/** P79-24 … P79-26, the type chips. */
val WARRANTY_SERVICE: String get() = localized(R.string.service_type_warranty_service)
val REPAIR: String get() = localized(R.string.service_type_repair)
val OTHER_SERVICE: String get() = localized(R.string.service_type_other_service)

/** P79-27 … P79-30, the editor's fields. */
val OPENED_ON: String get() = localized(R.string.service_field_opened_on)
val SERVICE_PROVIDER: String get() = localized(R.string.service_field_provider)
val PHONE_OR_CONTACT: String get() = localized(R.string.service_field_phone_or_contact)
val CASE_OR_RMA_NUMBER: String get() = localized(R.string.service_field_case_or_rma)

/** P79-31, the label over the coverage chips. */
val COVERAGE: String get() = localized(R.string.service_field_coverage)

/** P79-32 … P79-35, the coverage chips and a row's coverage word (P79-35 under R79-6). */
val COVERAGE_IN_WARRANTY: String get() = localized(R.string.service_coverage_in_warranty)
val COVERAGE_OUT_OF_WARRANTY: String get() = localized(R.string.service_coverage_out_of_warranty)
val COVERAGE_UNKNOWN: String get() = localized(R.string.service_coverage_unknown)
val PARTLY_COVERED: String get() = localized(R.string.service_coverage_partly_covered)

/** P79-36, under Coverage on a new case only: the suggestion is a form default (C15). */
val SUGGESTED_FROM_THE_WARRANTY_DATE: String get() = localized(R.string.service_coverage_suggested)

/** P79-44 … P79-47: the two legs' tracking, the carrier (both legs, one home) and the cost. */
val OUTBOUND_TRACKING: String get() = localized(R.string.service_field_outbound_tracking)
val RETURN_TRACKING: String get() = localized(R.string.service_field_return_tracking)
val CARRIER: String get() = localized(R.string.service_field_carrier)
val COST: String get() = localized(R.string.service_field_cost)

/** P79-48, a cost with no currency. */
val A_COST_NEEDS_A_CURRENCY: String get() = localized(R.string.service_cost_needs_currency)

/** P79-49. */
val COST_CANNOT_BE_NEGATIVE: String get() = localized(R.string.service_cost_negative)

/**
 * P79-62, "Enter a cost like <example>": the price's own form, "123.45" for a two-digit currency, with the
 * language's decimal separator (#102).
 */
fun costExample(digits: Int): String =
    localized(R.string.service_cost_example, amountExample(digits))

/** P79-51, the editor's save. */
val SAVE_CASE: String get() = localized(R.string.service_save_case)

/** P79-52, the title's refusal. */
val GIVE_THE_CASE_A_TITLE: String get() = localized(R.string.service_title_required)

/** P79-61, an unexpected save failure (logged), as "Could not save this asset.". */
val COULD_NOT_SAVE_THIS_CASE: String get() = localized(R.string.service_could_not_save)

/** P79-37, the case fact and the label over the update sheet's status chips. */
val CASE_STATUS: String get() = localized(R.string.service_field_status)

/** P79-38 … P79-43, the six status words: value text, chips and a row's word. */
val STATUS_OPEN: String get() = localized(R.string.service_status_open)
val STATUS_SENT_OUT: String get() = localized(R.string.service_status_sent_out)
val STATUS_AT_THE_SERVICE_CENTER: String get() = localized(R.string.service_status_at_service_center)
val STATUS_RETURNED: String get() = localized(R.string.service_status_returned)
val STATUS_CLOSED: String get() = localized(R.string.service_status_closed)
val STATUS_CANCELLED: String get() = localized(R.string.service_status_cancelled)

/** P79-50, the case fact, when set. */
val CLOSED_ON: String get() = localized(R.string.service_field_closed_on)

/** P79-53, the case screen's section. */
val TIMELINE: String get() = localized(R.string.service_timeline)

/** P79-54, the case screen's action and the update sheet's title. */
val ADD_UPDATE: String get() = localized(R.string.service_add_update)

/** P79-55, the sheet's note. */
val WHAT_HAPPENED: String get() = localized(R.string.service_what_happened)

/** P79-56, the sheet's save: enabled only with a note or a status. */
val SAVE_UPDATE: String get() = localized(R.string.service_save_update)

/** P79-57, the originating Incident's link row. */
val INCIDENT_LINK: String get() = localized(R.string.service_link_incident)

/** P79-58, the resolving event's link row. */
val REPAIR_RECORD: String get() = localized(R.string.service_link_repair_record)

/** P79-59, the case action and the picker's title (R79-10: link-only). */
val LINK_REPAIR_RECORD: String get() = localized(R.string.service_link_repair_record_action)

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
