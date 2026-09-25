# P3 — the editor keeps a QUICK schedule's quick action (#81)

**Read first:** plan.md §1, §2, §4 (P141-3, P141-4), §5; `rulings.md` "#81"; #81's body (the acceptance list).
**Lane:** B, wave 1, branched from master `a8717c2`; it runs beside P1 and, later, P2, and shares no file with either.
**Blocked on:** P141-3 and P141-4 ratified (plan.md §4).

## Goal

Since 1.2 (`bf37e95`) the editor has treated the profile as a FORM-only thing: switching to One tap nulls it (`ScheduleEditViewModel.kt:643`), the command drops it unless the mode is FORM (`:839`), and the picker is drawn only under The full form (`ScheduleEditScreen.kt:410-416`). The domain disagrees — a profile is one asset's quick action (`ScheduleCommands.kt:110`), and `CompleteSchedule` names it on the completion event and takes its event kind in **both** modes (`CompleteSchedule.kt:72`, `:80-81`, `:113`) — and 23 of the 43 real schedules on each phone are QUICK asset schedules carrying a profile: any save from today's editor silently clears them. After P3 the profile survives load, edit and save in either mode; the picker is drawn for every asset-targeted schedule, labelled by mode; a `None` row clears it explicitly (today the only way to clear one was the mode switch, so without the row a chosen profile would become permanent); a group target still offers no picker (D-12, inv. 106).

## Files

**Modify**
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/ScheduleEditViewModel.kt`: `onCompletionMode` (`:637-646`), `command()` (`:839`).
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/ScheduleEditScreen.kt`: the gate at `:410-416`; `ProfilePicker` (`:684-712`) takes its label and gains the first row; two `const val` strings beside `ONE_TAP` / `THE_FULL_FORM` (`:78-79`).
- Tests: `app/src/test/…/ui/maintenance/ScheduleEditViewModelTest.kt`; `app/src/androidTest/…/ui/maintenance/ScheduleEditorTest.kt`.

**Untouched:** `CompleteSchedule.kt`, `ScheduleCommands.kt`, `SaveSchedule.kt`, the provider lines of `command()` (`:842-844`), `filledFrom` (`:530-554`; it already loads `profileId = row.profileId` at `:543`), `MaintenanceSheetViewModel.kt`, `ScheduleDetailScreen.kt`, the API, the MCP, the loader, every ratified label.

## Interfaces

```kotlin
const val QUICK_ACTION = "Quick action"   // P141-3, the picker's label under One tap
const val NONE = "None"                   // P141-4, the picker's first row
private fun ProfilePicker(label: String, profiles: List<Pair<ProfileId, String>>, selected: ProfileId?, onSelect: (ProfileId?) -> Unit, problem: Boolean)
```

## Contracts

- `onCompletionMode(QUICK)` → `form.copy(completionMode = QUICK)`; FORM likewise; the group rule (`isGroup && FORM → form`) and the mark-clearing (`clearing(COMPLETION_MODE, PROFILE)`) unchanged. **Nothing nulls `profileId` but `onProfile(null)`** and the group target (which never has one).
- `command()`: `profileId = profileId.takeIf { group == null }`.
- **The picker** is drawn for an asset target in both modes, after the `ChoiceRow`, labelled `USE_THIS_FORM` under The full form and `QUICK_ACTION` under One tap; its first `DropdownMenuItem` is `NONE` and calls `onSelect(null)`; with no profile the field's text is empty, as today. A group target keeps its branch (`:417-423`) and no picker.
- **No new rule:** QUICK with no profile stays valid; FORM with no profile stays what it is today; `ScheduleField.PROFILE` marks map only `ProfileOnGroupTarget` and `ForeignProfile` (`ScheduleEditViewModel.kt:110-111`), unchanged.
- Everything else the command carries — target, rule, policy, `remindersEnabled`, the one provider row, `completionMode` — is unchanged by this brief; the tests assert the whole command, not the one field.

## Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| the 23-row shape | `ScheduleEditViewModelTest` · `aQuickAssetScheduleWithAProfileSavesItUnchanged`: load a row (QUICK, asset target, `profileId = P`, `remindersEnabled = true`, `[LOCAL enabled]`, a time rule) → `save()` → the command equals the row on every field, `profileId == P` | restore `:839` |
| an unrelated edit | `ScheduleEditViewModelTest` · `editingTheDescriptionKeepsTheProfile` | — |
| the mode switch | `ScheduleEditViewModelTest` · `switchingModesKeepsTheProfile`: FORM → QUICK → FORM, `profileId` never null, and a QUICK save still carries it | restore `:643` |
| no way to clear | `ScheduleEditViewModelTest` · `noneClearsTheProfileExplicitly`: `onProfile(null)` → the command's `profileId == null` | — |
| the group | existing `aGroupTargetOffersNoMeterNoProfileAndNoFollowAssetSeason` (`:222`) green; read `:234-249` (a null profile after `onCompletionMode(FORM)` in the group context stays true because a group has none) | — |
| the provider row | existing `theEditorWritesExactlyOneProviderRowAndNeverLosesIt` (`:406`) green | — |
| the picker under One tap | `ScheduleEditorTest` · `oneTapDrawsTheQuickActionPicker`: under One tap the field labelled `Quick action` is displayed, under The full form `Use this form`; both list the asset's profiles | keep the gate |
| the row | `ScheduleEditorTest` · `noneIsTheFirstRowAndClearsTheChoice`: choose a profile, open, tap `None`, the field is empty and the saved command has no profile | — |
| the group screen | existing `aGroupScheduleIsCreatedAndOffersNoneOfItsThreeForbiddenControls` (`:174`) green; `aScheduleIsCreatedAgainstAnAssetWithEveryRatifiedLabelVerbatim` (`:133`) adapted only if it asserts the picker's absence under One tap | — |

## Gate

- `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --console=plain`: zero failures, zero skips.
- Connected, on `emulator-5554` only, one class: `com.loosecannon.servicetag.ui.maintenance.ScheduleEditorTest`.
- Anchored `git grep -nE` over `app/src/main`: `'"Quick action"'` → 1; `'"None"'` → 1 more than at the base (report the base count); `'"Use this form"'` → 1, unchanged; `'profileId = null'` in `ScheduleEditViewModel.kt` → 0; `'completionMode == CompletionMode\.FORM'` in `ScheduleEditViewModel.kt` → the base's lines minus `:839`.
- `git diff <base> -- core tools docs app/src/main/kotlin/com/loosecannon/servicetag/api app/src/main/kotlin/com/loosecannon/servicetag/reminders app/src/main/kotlin/com/loosecannon/servicetag/di` → empty.
- Hygiene; gitlink `7e0377a`; `git status` clean.

## Strings

P141-3 `Quick action` and P141-4 `None` as ratified, and the ratified `Use this form`, `One tap`, `The full form`; nothing else.

## Must NOT

- touch `CompleteSchedule`, the provider lines, or any validation;
- change the group branch, the sheet, or schedule detail;
- rename or move a ratified label;
- drive the UI outside the Compose test APIs; use any device but `emulator-5554`.

## Review focus

The 23-row test asserting the whole command; `None` behaving the same under both modes; the group branch byte-identical; no ratified label moved.

## Size

Small: two files, two test classes.
