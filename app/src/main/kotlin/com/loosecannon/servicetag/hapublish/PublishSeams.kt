package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.core.hapublish.HaServiceAction
import com.loosecannon.servicetag.core.hapublish.RemoteRead
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.Secret
import java.time.LocalDate

/**
 * #113/#114 (C30, C31) — the runner's only edge to the projector: recompute every binding's desired value as of
 * [today] in one write, with no HTTP inside, and answer whether anything changed. The runner calls it after a valid
 * boundary read (C9a) and before a date kind's send that waits for a projection (C17's `Skip(PENDING, reproject)`);
 * `PublishProjector` implements it (B4b1).
 */
fun interface DesiredStateReconciler {
    suspend fun reconcileDesired(today: LocalDate): Boolean
}

/**
 * #113/#114 (C22) — what the runner asks of Home Assistant: one helper read, or one allowlisted service call, at the
 * stored connection. Values only, never a throw for a transport failure; a cancellation propagates. Production is
 * [ClientTransport] over B2's client; a JVM test scripts it.
 */
interface HaPublishTransport {
    suspend fun read(connection: HaConnection, entityId: String, token: Secret): RemoteRead

    suspend fun call(
        connection: HaConnection,
        action: HaServiceAction,
        entityId: String,
        value: String,
        token: Secret,
    ): CallOutcome
}

/** The production transport: [client] (B2), every rule of its own (C21–C23). */
class ClientTransport(private val client: HomeAssistantPublishClient) : HaPublishTransport {
    override suspend fun read(connection: HaConnection, entityId: String, token: Secret): RemoteRead =
        client.read(connection, entityId, token)

    override suspend fun call(
        connection: HaConnection,
        action: HaServiceAction,
        entityId: String,
        value: String,
        token: Secret,
    ): CallOutcome = client.call(connection, action, entityId, value, token)
}

/**
 * #114 (C9a) — the boundary cache's own write: [value] read at [readAt] becomes the delivery's
 * `boundary_value` / `boundary_read_at` / `boundary_binding_revision`, only while the binding is still at
 * [bindingRevision]; answers whether it wrote. The sender's record never writes these columns (B3), so this is their
 * one writer outside a command.
 */
fun interface BoundaryCacheWriter {
    suspend fun recordBoundary(bindingId: String, bindingRevision: Long, value: String, readAt: Long): Boolean
}
