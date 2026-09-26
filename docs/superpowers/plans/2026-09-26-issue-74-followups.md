# #74 follow-ups — two failure sentences and the invisible-character key hazard (owner rulings 2026-09-26)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review
> budget in `docs/superpowers/planning-policy.md`. One brief, one implementer, one task review, one batched
> fix round. Both items were ruled by the owner on 2026-09-26 after #74 merged (2306929); both land before
> the first signed release that carries backup format 9 (`docs/release-proofs.md`, the schema-9 gate).

**Spec:** the #74 plan (`docs/superpowers/plans/2026-09-26-issue-74-durable-categories.md`) §2 C2 (the key rule —
this brief amends it), §5 C17 and §11 (the open items); the owner's two rulings below.

## 1. Two ratified failure sentences (owner, 2026-09-26)

| id | where | text |
|---|---|---|
| P74-18 | under the rename field, the dialog stays open | `Could not rename that category.` |
| P74-19 | the screen's snackbar, after a delete that failed | `Could not delete that category.` |

These are **unexpected-operation failures** — any exception from `RenameCategory.run` / `DeleteCategory.run`
other than the named refusals (`CategoryValidation`, `CategoryIsBuiltIn`, `CategoryExists`, `CategoryInUse`,
`NoSuchCategory`) and other than `CancellationException` (rethrown). Today a failed rename leaves the dialog
open with no line and a failed delete closes silently.

- **F1.** `CategoriesViewModel.rename`: on such a failure the draft stays open with `renaming = false` and
  `refusal = P74-18`, drawn exactly as P74-11/12 are (`supportingText` + `isError`; the owner may change the
  text and try again or cancel). Editing the text clears it, as it clears the other refusals.
- **F2.** `CategoriesViewModel.confirmDelete`: on such a failure the confirm dialog closes, the row stays, and
  P74-19 is emitted on `messages` (the snackbar). Nothing else changes.
- **F3.** The two literals are `const val`s beside the other P74 consts, each exactly once across `app/src/main`.
- **Tests.** `CategoriesViewModelTest` · `anUnexpectedRenameFailureKeepsTheDialogAndSaysSo` (a `RenameCategory`
  over a repository whose upsert throws: `refusal == P74-18`, `renaming == false`, the draft's text kept;
  RED: keep the old `else -> null`) and `anUnexpectedDeleteFailureSaysSoOnTheSnackbar` (`messages` receives
  P74-19, the row is still listed; RED: swallow). `CategoriesScreenTest` · `aFailedRenameShowsItsLineUnderTheField`
  (P74-18 displayed, the dialog still on screen; RED: emit to the snackbar instead).

## 2. The invisible-character hazard in the key rule (owner, 2026-09-26)

The ratified `CategoryKey` rule (NFC, trim, whitespace collapsed, `lowercase(Locale.ROOT)`) does not remove
zero-width and other invisible format characters, so a pasted `AppU+200Bliance` makes a second category that
looks identical to `Appliance`. The rule is persisted (a Room primary key and a format-9 archive field), so
this closes **before** format 9 ships. The owner's constraint: **do not strip every Unicode `FORMAT`
character** — some carry meaning; strip or reject only the non-semantic invisible characters that can create
visually duplicate identities, with explicit tests.

- **K1, removed from both `display` and `of`** (so the stored spelling is the visible text and the key follows):
  U+00AD SOFT HYPHEN; U+034F COMBINING GRAPHEME JOINER; U+180E MONGOLIAN VOWEL SEPARATOR; U+200B ZERO WIDTH
  SPACE; U+200E LEFT-TO-RIGHT MARK and U+200F RIGHT-TO-LEFT MARK; the bidi controls U+202A–U+202E and
  U+2066–U+2069; U+2060 WORD JOINER and the invisible operators U+2061–U+2064; the deprecated format
  characters U+206A–U+206F; U+FEFF ZERO WIDTH NO-BREAK SPACE; the tag characters U+E0000–U+E007F.
- **K2, kept:** U+200C ZERO WIDTH NON-JOINER and U+200D ZERO WIDTH JOINER (meaningful in Persian, Indic and
  emoji sequences), variation selectors U+FE00–U+FE0F and U+E0100–U+E01EF, every combining mark, and every
  whitespace character (still collapsed to one space). Nothing else in `Cf` is touched.
- **K3, order:** NFC first, then the removal, then trim and collapse; `of` lowercases the result. A text that
  is nothing but removed characters and whitespace is blank: no key, never promoted.
- **K4, consequences, all already in place and now exercised:** the content check's fourth clause refuses an
  archive row whose `display` carries a removed character (it is not in `CategoryKey.display` form); the
  migration's backfill and the replace/merge chooser go through `display`; `templateFor` by key. No migration
  and no format bump: format 9 has not shipped and no phone holds schema 9.
- **K5, docs:** `CategoryKey`'s KDoc names the removed set and the kept set with the reason; the #74 plan's C2
  gets an errata line pointing here.
- **Tests.** `CategoryKeyTest` · a table: `AppU+200Bliance` / `U+FEFFAppliance` / `AppliU+00ADance` /
  `U+200EApplianceU+200F` / `AppU+2060liance` / a tag-character sequence → all `appliance` with display
  `Appliance`; a ZWNJ inside a Persian word and a ZWJ emoji sequence keep their joiner (the key with the
  joiner differs from the key without it); `U+200BU+200B` → blank, no key; a bidi override inside a name is
  removed; NFC still applies after removal (`E` + U+0301 with a ZWSP between → `é`). RED: drop the removal.
  `BackupContentCheckTest` · a category row with a ZWSP in its display is refused. `PromoteCategoryTest` ·
  `AppU+200Bliance` after `Appliance` reuses the row.

## 3. The brief (one implementer)

**Files.** Modify `core/journal/CategoryKey.kt`, `app/…/ui/settings/CategoriesViewModel.kt`,
`app/…/ui/settings/CategoriesScreen.kt` (the consts, if they live there), the #74 plan (one C2 errata line);
tests `CategoryKeyTest`, `BackupContentCheckTest`, `PromoteCategoryTest`, `CategoriesViewModelTest`,
`CategoriesScreenTest`. **Untouched:** everything else — no schema, format, migration, planner or API change;
no other string.
**Gate.** `:core:test :app:testDebugUnitTest --rerun` zero failures/skips; `:app:compileDebugAndroidTestKotlin`;
connected on `emulator-5554`, one class per invocation: `CategoriesScreenTest`, `AssetEditorCategoryPickerTest`;
anchored `git grep -nF` over `app/src/main`: `"Could not rename that category."` → 1, `"Could not delete that
category."` → 1; `git grep -nE 'Cf|FORMAT|getType\(' -- core/src/main/kotlin/com/loosecannon/servicetag/core/journal/CategoryKey.kt`
→ 0 (an explicit set, never the whole category); the assert sweep empty; gitlink `7e0377a`; `git status` clean.
**Must NOT.** Strip ZWJ/ZWNJ, variation selectors or combining marks; use `Character.getType == FORMAT` as the
rule; add a string beyond P74-18/19; touch the dashboard, the planner, the migration or the API.

## 4. Errata (2026-09-26, from the build and its reviews; the final rule)

The task review overturned two rulings with evidence and the fix round applied the final sets below;
the plan's §2 K1–K2 read as superseded by this section. Every code point is written as `U+XXXX` here
and in every test source: the tool that wrote §2 turned escapes into the characters themselves, which is
the Trojan-Source hazard this brief exists to close.

- **Removed** (an explicit list, never a whole Unicode category): U+00AD; U+034F; U+061C; U+17B4–U+17B5
  (zero-width, deprecated — a controller ruling for the owner's knowledge: Khmer text never needs them);
  U+180E; U+200B; U+200E–U+200F; U+202A–U+202E; U+2060–U+206F (word joiner, invisible operators, the
  unassigned U+2065, the bidi isolates, the deprecated format characters); U+FEFF; U+FFF0–U+FFF8;
  U+1D173–U+1D17A; U+E0000–U+E001F (unassigned, and the deprecated language tag U+E0001); the tag
  characters U+E0020–U+E007F except inside a subdivision flag; U+E0080–U+E00FF and U+E01F0–U+E0FFF
  (unassigned).
- **Space-like, replaced by a space** (they take visible width; dropping them would join two words the
  owner saw apart): U+115F, U+1160, U+3164, U+FFA0, U+2800. Trade-off for the owner: in old-Hangul jamo
  spelling U+115F/U+1160 stand for a missing letter, so a category typed that way would split at them.
- **Kept, because they carry meaning:** U+200C and U+200D (the joiners); the variation selectors
  U+FE00–U+FE0F and U+E0100–U+E01EF; every other combining mark; whitespace (collapsed); every other
  format character; and the tags of the **three standard subdivision flags only** — England, Scotland and Wales: the
  exact tag sequences spelling `gbeng`, `gbsct` and `gbwls`, each closed by U+E007F, directly after U+1F3F4
  in the text as given (the scoped re-review's S7: keeping *any* tag run after a flag left a hidden-text
  channel behind a bare-looking flag). Any other tag run after a flag is removed like a stray one and the
  flag reads bare; a removed character pasted between the flag and its tags leaves the bare flag, which is
  what such a text shows; the U+FE0F variant form is not kept (no standard flag uses it).
- **Order:** NFC; step 2 (remove / space-like); NFC again (a removed character may have held a mark
  apart from its letter); trim and collapse. `of` lowercases the result with `Locale.ROOT`.
- **Why tags are not simply kept:** a tag after a letter has zero width in HarfBuzz, so a name plus tags
  looks identical to the name and keys apart, and tags are an ASCII-smuggling channel toward the MCP's
  LLM clients. Why fillers are not simply removed: they are 920–1500 units wide in common fonts.
- **The failure mapping (S5):** a rename maps every exception that is not `CategoryExists`,
  `CategoryIsBuiltIn`, `CategoryValidation`, `NoSuchCategory` or a cancellation to P74-18; a delete maps
  every exception that is not `CategoryInUse`, `NoSuchCategory` or a cancellation to P74-19; each pinned
  by its own RED test.
- **`docs/api/v1.md`** describes the first-save spelling as the rule above, in two sentences.
