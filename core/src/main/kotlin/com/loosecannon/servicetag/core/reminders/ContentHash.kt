package com.loosecannon.servicetag.core.reminders

import java.security.MessageDigest
import java.time.LocalDate

/**
 * The hash a [ReminderSubject] carries, over exactly the fields that change what a provider should
 * show.
 *
 * It is a **narrow** hash on purpose. Hashing the schedule row would make every unrelated edit — a
 * note, a description, a re-save that changed nothing — look like a change, and a provider told a
 * subject changed has to act on it. So the input is the six display-bearing fields and nothing
 * else: no id, no timestamp, no provenance, nothing about where a measurement came from.
 *
 * The canonical form is the fields in a fixed order separated by the ASCII unit separator, with an
 * explicit marker for every absent value. The separator is the reason `title` and `body` cannot be
 * confused for one another by concatenation, and the markers are the reason a null date and the
 * empty string do not collide.
 */
object ContentHash {

    private const val SEP = '\u001F'
    private const val ABSENT = "\u0000"

    fun of(
        title: String,
        body: String,
        dueOn: LocalDate?,
        leadDays: Int,
        state: SubjectState,
        rule: RuleFacts?,
    ): String = sha256(
        listOf(
            title,
            body,
            dueOn?.toString() ?: ABSENT,
            leadDays.toString(),
            render(state),
            rule?.let(::render) ?: ABSENT,
        ).joinToString(SEP.toString()),
    )

    /**
     * States render by name, and a parked one by name **and** re-entry date: two parked subjects
     * that come back on different days are not the same thing to show.
     *
     * The two cleared states render **identically**, and deliberately. Both mean the provider must
     * stop holding the subject, so there is nothing a provider shows that differs between them, and
     * a hash that invented a difference would report a change no provider could act on — invariant
     * 46's failure in the other direction. It is also the branch that keeps the reserved member
     * unconstructable here.
     */
    private fun render(state: SubjectState): String = when (state) {
        SubjectState.Active -> "ACTIVE"
        is SubjectState.Parked -> "PARKED:${state.reentryOn?.toString() ?: ABSENT}"
        SubjectState.Completed, SubjectState.Withdrawn -> "CLEARED"
    }

    private fun render(rule: RuleFacts): String = listOf(
        rule.basis?.name ?: ABSENT,
        rule.interval?.toString() ?: ABSENT,
        rule.unit?.name ?: ABSENT,
        rule.hasMeter.toString(),
        rule.seasonal.toString(),
    ).joinToString("|")

    private fun sha256(canonical: String): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
