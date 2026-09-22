package com.loosecannon.servicetag.core.links

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.nfc.TagPayload
import com.loosecannon.servicetag.core.nfc.TagRoute

/** The `servicetag://` contract (D3 §13): navigation only, validated by shape here and by existence in the UI. */
sealed interface DeepLink {
    data class Asset(val id: AssetId) : DeepLink
    data class Tag(val payload: TagPayload) : DeepLink

    /**
     * 1.2, D-17: one maintenance schedule, in full. Added because a notification's "Open" action
     * has to name a destination and a `PendingIntent` carrying a URL is how it does so without the
     * notification layer knowing anything about the back stack.
     *
     * **No group host joins it.** Maintenance reaches every group in-app, so a `servicetag://group`
     * link would be a permanent contract with no 1.2 consumer.
     */
    data class Schedule(val id: ScheduleId) : DeepLink

    data class Malformed(val reason: String) : DeepLink
}

object DeepLinkRoute {
    const val SCHEME = "servicetag"
    private val canonicalUuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun parse(scheme: String?, host: String?, pathSegments: List<String>): DeepLink? {
        if (scheme != SCHEME) return null
        return when (host) {
            TagRoute.HOST -> TagRoute.parse(scheme, host, pathSegments)?.let { p ->
                if (p is TagPayload.Malformed) DeepLink.Malformed(p.reason) else DeepLink.Tag(p)
            }
            "asset" -> single(pathSegments)?.let { DeepLink.Asset(AssetId(it)) } ?: DeepLink.Malformed("servicetag://asset needs one tag id segment")
            // The same `single` as the host above it, deliberately: one shape rule for every host
            // is what stops a second, looser check letting a malformed id reach a repository.
            "schedule" -> single(pathSegments)?.let { DeepLink.Schedule(ScheduleId(it)) } ?: DeepLink.Malformed("servicetag://schedule needs one schedule id segment")
            else -> null
        }
    }

    private fun single(segments: List<String>): String? = segments.singleOrNull()?.takeIf { canonicalUuid.matches(it) }
}
