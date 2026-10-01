package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.model.SupplySpecification
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * #15 (C15; R15-2, R15-11) — creates or edits one SupplyItem with its ordered specifications, the [SaveGroup] shape:
 * every problem collected before the one `uow.write`, so a refusal opens no transaction and mints no id.
 *
 * - **Text:** every field trimmed; the name may never be blank ([SupplyItemProblem.NameRequired]); per row, the label
 *   and the value may not be blank; the unit may (`""` is none).
 * - **Ids:** a row's id is kept only when this item owns it, and only once; anything else is minted fresh
 *   ([SaveProfile]'s rule, made stricter: a repeat is a new row). `sortOrder` is the row's index, so the command's order is the stored order.
 * - **Keys (R15-11), the definition slug and never a second rule** ([KEY_PATTERN], [slugify], [dedupedKey]): a typed
 *   key must match the pattern and must not be another row's key in this command; a kept row with no typed key — or
 *   typed as the key it already holds — keeps its stored key, so a label edit never re-keys it; a new row with no
 *   typed key takes the slug of its label, or `"spec"` when the label slugs to nothing (a "Ø"), deduped against the
 *   keys already taken (typed, then kept, then derived, in row order). The phone never sees a key, so no phone save
 *   can meet a key problem.
 * - **Unchanged:** an edit whose result equals the stored item in every field but `updatedAt` writes nothing and
 *   answers the stored item, marked [SavedSupplyItem.unchanged] (the [UpdateReference] rule): a write on every call
 *   would make a re-imported archive differ on the next merge.
 *
 * A save never archives or unarchives ([ArchiveSupplyItem] does), and it writes the item and its specifications only:
 * no material line is read or written, so a linked line's name stays the snapshot it was (C20). Nothing deletes a
 * SupplyItem (R15-5).
 */
class SaveSupplyItem(
    private val items: SupplyItemRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun run(id: SupplyId?, cmd: SupplyItemCommand): SavedSupplyItem {
        val saved = prepare(id, cmd)
        if (!saved.unchanged) uow.write { items.upsert(saved.item) }
        return saved
    }

    /** The same reads, refusals and row as [run], written inside the caller's transaction (the [SaveGroup] variant). */
    internal suspend fun saveInTransaction(id: SupplyId?, cmd: SupplyItemCommand): SavedSupplyItem =
        prepare(id, cmd).also { if (!it.unchanged) items.upsert(it.item) }

    /** [run]'s reads and refusals, and the item it would write. */
    private suspend fun prepare(id: SupplyId?, cmd: SupplyItemCommand): SavedSupplyItem {
        val existing = id?.let { items.get(it) ?: throw NoSuchSupplyItem(it) }
        val own = existing?.specifications.orEmpty().associateBy { it.id }
        val rows = rowsOf(cmd.specifications, own)

        val problems = problemsOf(cmd, rows)
        if (problems.isNotEmpty()) throw SupplyItemValidation(problems)

        val now = clock.nowMillis()
        val itemId = existing?.id ?: SupplyId(ids.newId())
        val keys = keysOf(rows)
        val candidate = SupplyItem(
            id = itemId,
            name = cmd.name.trim(),
            category = cmd.category.trim(),
            manufacturer = cmd.manufacturer.trim(),
            model = cmd.model.trim(),
            partNumber = cmd.partNumber.trim(),
            preferredUnit = cmd.preferredUnit.trim(),
            notes = cmd.notes.trim(),
            archivedAt = existing?.archivedAt,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            specifications = rows.mapIndexed { index, row ->
                SupplySpecification(
                    id = row.keptId ?: ids.newId(),
                    key = keys[index],
                    label = row.label,
                    value = row.value,
                    unit = row.unit,
                    sortOrder = index,
                )
            },
        )
        if (existing != null && candidate.copy(updatedAt = existing.updatedAt) == existing) {
            return SavedSupplyItem(existing, unchanged = true)
        }
        return SavedSupplyItem(candidate, unchanged = false)
    }

    /**
     * One command row, trimmed and resolved against the stored rows: [keptId] when this item owns the sent id and no
     * earlier row claimed it; [fixedKey] the typed key, else the kept row's stored key, else null (to be derived).
     */
    private class Row(
        val keptId: String?,
        val typedKey: String?,
        val fixedKey: String?,
        val label: String,
        val value: String,
        val unit: String,
    )

    private fun rowsOf(inputs: List<SpecificationInput>, own: Map<String, SupplySpecification>): List<Row> {
        val claimed = mutableSetOf<String>()
        return inputs.map { input ->
            val keptId = input.id?.takeIf { it in own && claimed.add(it) }
            val storedKey = keptId?.let { own.getValue(it).key }
            // A kept row sent with the key it already holds is a kept key, not a typed one.
            val typedKey = input.key.trim().takeIf { it.isNotEmpty() && it != storedKey }
            Row(
                keptId = keptId,
                typedKey = typedKey,
                fixedKey = typedKey ?: storedKey,
                label = input.label.trim(),
                value = input.value.trim(),
                unit = input.unit.trim(),
            )
        }
    }

    /** Everything wrong with [cmd], collected: the name, then each row's label, value and typed key, in row order. */
    private fun problemsOf(cmd: SupplyItemCommand, rows: List<Row>): List<SupplyItemProblem> {
        val problems = mutableListOf<SupplyItemProblem>()
        if (cmd.name.isBlank()) problems += SupplyItemProblem.NameRequired
        rows.forEachIndexed { index, row ->
            if (row.label.isEmpty()) problems += SupplyItemProblem.SpecLabelRequired(index)
            if (row.value.isEmpty()) problems += SupplyItemProblem.SpecValueRequired(index)
            val typed = row.typedKey ?: return@forEachIndexed
            when {
                !KEY_PATTERN.matches(typed) -> problems += SupplyItemProblem.SpecKeyInvalid(index)
                rows.withIndex().any { (other, it) -> other != index && it.fixedKey == typed } ->
                    problems += SupplyItemProblem.SpecKeyTaken(index)
            }
        }
        return problems
    }

    /** Each row's key: its fixed key, or its label's slug (`"spec"` when empty) deduped against every key taken. */
    private fun keysOf(rows: List<Row>): List<String> {
        val taken = (rows.mapNotNull { it.typedKey } + rows.filter { it.typedKey == null }.mapNotNull { it.fixedKey })
            .toMutableSet()
        return rows.map { row ->
            row.fixedKey ?: dedupedKey(slugify(row.label).ifEmpty { SPEC_KEY_FALLBACK }, taken).also { taken += it }
        }
    }

    private companion object {
        /** The base key of a row whose label slugs to nothing ("Ø", "±"): R15-11. */
        const val SPEC_KEY_FALLBACK = "spec"
    }
}
