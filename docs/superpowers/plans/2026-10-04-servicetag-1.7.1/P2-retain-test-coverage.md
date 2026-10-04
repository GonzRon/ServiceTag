# P2 — #99: the retain identity check covers every list of the archive

**Read first:** plan.md §1 (#99), §9; `rulings.md` R171-3; #99's body.
**Lane:** A, with P1, branched from `<base>` = `5cd878c`. **State:** executed in its own commit on the branch, before the plan's; closed by controller inspection plus the automated gates (a test-only change, no string).
**Blocked on:** nothing.

## Goal

`TransferGraphRetainTest.retainingNothingIsTheIdentity` means something for **every** list `BackupData` has, today and when a list is added: the fixture it checks has at least one row in each, the encoding it inspects cannot hide an empty defaulted list, and the key set it sees is asserted equal to the archive's own element names. The KDoc says what the check does and why it was vacuous before.

## Files

**Modify**
- `core/src/test/kotlin/com/loosecannon/servicetag/core/transfer/TransferGraphRetainTest.kt` — the one case and its KDoc; imports for `TransferRecord`, `TransferKind`, `assetSupplyOf` and `elementNames`.

**Untouched:** `TransferFixtures.kt` (`estate()` and `seed()`; R171-3), every other case in the class, `TransferGraphTest`, `TransferTableClassificationTest`, the two fixture tests under `core/src/test/…/usecase/`, `TransferSelectionViewModelTest`, and all of `core/src/main`.

## Why the rows are local

`estate()` is the sender's estate for six other suites, two of them pack-shaped (`TransferGraphTest` selects HEATER and asserts what the pack carries; `TransferSelectionViewModelTest` drives the selection over it). A `transferRecords` row names an asset as transferred, and an `assetSuccessions` row names two assets as replaced; either on a fixture asset changes `select`, `droppedBy` and the refusals those suites pin. The issue's purpose — "so the identity check means something for all of them" — is met by filling the identity test's own copy; `resourced()` in the same class already builds its fixture that way for #69. The owner may still rule the rows into `estate()` (R171-3); that is a fix round with every dependent suite re-pinned.

## Test matrix

| assertion | fails without the change by |
|---|---|
| `Json { encodeDefaults = true }` encoding of the fixture has key set == `BackupData.serializer().descriptor.elementNames` | the default `Json` omitting an empty defaulted list (the vacuity); a list added to `BackupData` and not to this fixture |
| no encoded list is empty | a fixture that forgot a list |
| `supplyItems`, `assetSupplies`, `assetSuccessions`, `transferRecords`, `installedComponents` each present | the same, by name, so the failure reads which one |
| `retain(fitted, emptySet()) == Retained(fitted)` | a `retain` that filters or forgets one of the five (mn-2) |

The fixture rows: one `SupplyItem` (`s1`), one `AssetSupply` of the heater naming it, one succession between two staying assets (compressor → opener), one `IN` transfer record of the compressor, one installed component on the heater. All fictional.

## Gate

- `./gradlew :core:test --console=plain`: zero failures, zero skips. **Run in the planning environment** through a scratch settings file aimed at the same `core` and `nfc-core` sources (the root settings cannot resolve there): 2,160 tests / 197 suites, zero failures, zero skips, `TransferGraphRetainTest` 9 of 9. CI's run on the branch is the proof of record.
- `git diff <base> --stat -- core app tools .github` names exactly `core/src/test/kotlin/com/loosecannon/servicetag/core/transfer/TransferGraphRetainTest.kt`.
- `git grep -nE 'encodeDefaults = true' core/src/test/kotlin/com/loosecannon/servicetag/core/transfer/TransferGraphRetainTest.kt` → 1.

## Must NOT

Touch `estate()` or `seed()`; touch production code; add a device case.
