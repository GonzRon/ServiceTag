package com.loosecannon.servicetag.testing

import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cancels every one of [jobs] and returns only once each has finished, so the caller may then close the database
 * they read or write. `cancel()` alone does not wait: a job on a real dispatcher can still be inside a bundled-SQLite
 * call when `close()` frees the connection, and the test JVM dies in native code (CI run 40510579302, SIGSEGV in
 * `sqlite3DbMallocRawNN`).
 *
 * The jobs' dispatcher is [context]'s. A real one (`Dispatchers.Default`) needs nothing from the caller's thread, so
 * the join holds even when the caller is inside `runTest` or `runBlocking`. A test dispatcher runs its work only when
 * someone runs its scheduler, so the scheduler's current tasks are run here, on the caller's thread, until the jobs
 * are done; a cancelled job resumes at the current virtual time, so this never moves the clock.
 *
 * A job still running [timeoutMillis] after its cancel fails the call by name, and the caller never reaches its
 * `close()`: an open database leaks, a closed one under a running job crashes the JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun cancelAndJoinBeforeClose(
    jobs: Map<String, Job>,
    context: CoroutineContext = EmptyCoroutineContext,
    timeoutMillis: Long = 5_000,
) {
    jobs.values.forEach { it.cancel() }
    val scheduler = (context[ContinuationInterceptor] as? TestDispatcher)?.scheduler
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    runBlocking {
        jobs.forEach { (name, job) ->
            while (!job.isCompleted) {
                scheduler?.runCurrent()
                val left = (deadline - System.nanoTime()) / 1_000_000
                check(left > 0) { "$name still running $timeoutMillis ms after its cancel; the database stays open" }
                // On a test scheduler the job may be waiting on a real thread that hands back to the scheduler,
                // so the wait is short and the loop runs the scheduler again.
                withTimeoutOrNull(if (scheduler == null) left else minOf(left, 10L)) { job.join() }
            }
        }
    }
}

/** [scope]'s job and every child it has, on [scope]'s own dispatcher; for `runTest`'s `backgroundScope`, say. */
fun cancelAndJoinBeforeClose(name: String, scope: CoroutineScope) =
    cancelAndJoinBeforeClose(mapOf(name to scope.coroutineContext.job), scope.coroutineContext)
