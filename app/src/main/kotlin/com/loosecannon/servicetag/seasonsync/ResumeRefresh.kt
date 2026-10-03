package com.loosecannon.servicetag.seasonsync

/**
 * #16 (C23, N-8) — the nav shell's resume hook: called on every resume, it starts the stale-only refresh on the
 * graph's scope and returns at once, so it never blocks or draws a frame. `AppGraph` provides it; `ServiceTagRoot`
 * takes it as a parameter defaulting to the graph's, so a Compose test passes a fake.
 */
fun interface ResumeRefresh {
    fun onResume()
}
