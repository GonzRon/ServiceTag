package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.CategoryRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.LinkRepository
import com.loosecannon.servicetag.core.ports.TagRepository

/**
 * Whether this phone holds any records at all (2.7.1, issue #40).
 *
 * The one caller is the Backup screen, deciding which confirmation a picked data archive gets. The
 * typed `REPLACE` word exists to make the owner spell out that they accept losing what is here
 * (R-9); on a phone with nothing here it authorises the loss of nothing and warns about data that
 * does not exist. So "empty" has to mean *nothing at all*, and a single row of any kind is enough
 * to make the answer false.
 *
 * **Six kinds, and exactly six.** Assets, tag bindings, journal events, attachment rows, the 2.6
 * tombstone link rows — because nothing in ServiceTag displays a link any more, and a row nobody can
 * see is still a record a restore would delete — and, since #74, the owner's own categories: a
 * category row exists **without any asset by design** (it outlives the last asset that used it),
 * so [assets] cannot answer for it, and a restore deletes it like any other record. `MeasurementDefinition` and
 * `EventProfile` are deliberately not read: both carry a non-null `assetId` and the schema's
 * foreign key enforces it, so neither can exist without the asset it names and [assets] already
 * answers for them.
 *
 * That same argument applies to [events] today — `asset_event.asset_id` is non-null and cascades
 * from `asset` — so the events clause is defence in depth against a schema that later relaxes the
 * constraint, not a row that can exist without its asset now. [attachments] is not in that position
 * and is genuinely load-bearing: an attachment's asset and event columns are both nullable with no
 * `CHECK`, so an owner-less row is representable.
 *
 * **Cheapest query each, and short-circuiting.** [AttachmentRepository.count] is a count; the other
 * five ports expose no count at all, so `all()` it is — and for [LinkRepository], narrowed to four
 * members in 2.6, `all()` is the only row-returning member there is. The `&&` chain means the usual
 * answer on a populated phone is one query that comes back non-empty and five that never run.
 *
 * There is no read transaction: the question is asked once, on a store nothing else is writing to,
 * and no invariant spans the six reads. A transaction would force all six and buy nothing.
 */
class StoreIsEmpty(
    private val assets: AssetRepository,
    private val tags: TagRepository,
    private val events: EventRepository,
    private val attachments: AttachmentRepository,
    private val links: LinkRepository,
    private val categories: CategoryRepository,
) {
    suspend fun run(): Boolean =
        assets.all().isEmpty() &&
            tags.all().isEmpty() &&
            events.all().isEmpty() &&
            attachments.count() == 0 &&
            links.all().isEmpty() &&
            categories.all().isEmpty()
}
