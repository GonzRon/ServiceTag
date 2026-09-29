package com.loosecannon.servicetag.core.fetch

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

class FetchDocument(
    private val transport: DocumentTransport,
    private val hops: HopPolicy,
    private val staging: StagingArea,
    private val limits: FetchLimits = FetchLimits(),
    private val io: CoroutineContext = Dispatchers.IO,
) {
    @Suppress("UNUSED_PARAMETER")
    suspend fun run(url: String, onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> }): FetchOutcome =
        FetchOutcome.Refused(FetchProblem.Unreachable)
}
