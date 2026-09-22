package com.loosecannon.servicetag.core.ports

/**
 * The device-local calendar date, `T`, as a port.
 *
 * [Clock] supplies `nowMillis()` and the scheduling engine needs a **date**: every due date in 1.2
 * is a date, the engine never sees an instant, and a zone change therefore never changes *what* is
 * due — only when the phone says so. A port rather than a bare parameter because the digest and
 * backstop runs need the same value the screens read, and because a fake `Today` is how every
 * deterministic date test is written without touching a real clock.
 *
 * `rebuild` still takes `T` as a parameter, so the pure function stays pure: nothing inside the
 * engine reads this port, only the callers that hand it `T`.
 */
fun interface Today {
    fun localDate(): java.time.LocalDate
}
