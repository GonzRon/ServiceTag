package com.loosecannon.servicetag.core.hapublish

/**
 * #113/#114 (C14a, A11) — the allowlist: the only two Home Assistant service actions this app ever calls, each a
 * helper's own setter. The client builds its path and body from these members alone, never from a string parameter,
 * so no other domain, service or key can be reached.
 */
enum class HaServiceAction(val domain: String, val service: String, val valueKey: String) {
    SELECT_OPTION("input_select", "select_option", "option"),
    SET_DATE("input_datetime", "set_datetime", "date"),
}

/** The one action that writes [kind]'s helper. */
fun actionOf(kind: PublishKind): HaServiceAction = when (kind) {
    PublishKind.EQUIPMENT_STATUS -> HaServiceAction.SELECT_OPTION
    PublishKind.SEASON_SERVICE_DATE -> HaServiceAction.SET_DATE
}
