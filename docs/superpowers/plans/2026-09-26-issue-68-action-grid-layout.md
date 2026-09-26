# #68 — the asset quick-action grid keeps every word of its labels: plan and brief (rev 2, reviewed and RATIFIED 2026-09-26)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the
> review budget in `docs/superpowers/planning-policy.md`. One brief (§8), one implementer, one task
> review, one batched fix round, at most one scoped re-review. Planned from issue #68's body under the
> owner's GO of 2026-09-26; rev 2 folds in the independent brief review (two blockers: Compose's
> non-linear font scaling and Material's 48dp minimum interactive box) and one controller finding
> (the one-pixel width split of an intrinsic row). Owner ruling 2026-09-26: R68-2 K = 6 ems and R68-6 the
> 44dp/54dp rhythm RATIFIED; R68-1, R68-3, R68-4, R68-5 stand as written; no strings to ratify; GO.

**Goal:** every quick action on the asset detail screen renders its whole label — a data-driven
profile name such as `Log Descale and flush`, the utility label `Readings & actions`, or a much
longer user-defined name — legibly, inside its own button, on any supported width and at any Android
font scale, while short labels keep exactly the appearance, rhythm and tap target they have today.
The grid adapts; no label is shortened, ellipsised or special-cased.

**Spec:** D12 (`docs/design/12-visual-design-apollo-service-binder.md`) §7 "Buttons" (secondary =
outlined; utility = tonal; the theme's 6dp corner, not Material's pill) and §8 (a "horizontal or 2×2
utility-action region", whose own sketch is a vertical list of icon + label rows); G1
(`docs/design/g1/01-g1-visual-gate-report.md` §1.1 row "Quick actions": outlined logging verbs, tonal
navigation, "44dp, 6dp radius, icon + label", no FAB); issue #68's acceptance criteria 1–6; the
owner's framing of 2026-09-26 ("a responsive-layout problem, not a content workaround").

## Global constraints

- The labels are the labels. `quickActionLabel()` (`ui/journal/JournalFormat.kt:106`) and the four
  utility strings in `AssetDetailScreen.detailActions()` are untouched; no `maxLines`, no
  `TextOverflow`, no font-size change, no abbreviation, no branch on any particular string.
- No new user-visible string (§5). A state that seems to need words is NEEDS_CONTEXT.
- D12/G1 stay true: outlined versus tonal as production has it today, the 6dp `ControlShape`,
  icon + label, `labelLarge`, two actions to a row whenever the width allows it, a 10dp rhythm.
- Tests are Compose instrumented tests on `emulator-5554` only (`feedback-testing-hierarchy`); no
  UI-driving harness; one connected class per Gradle invocation; the JVM suite stays green.
- Hygiene: no e-mail addresses, `/home/<user>` paths, serials, private equipment names, locations or
  household nouns in any file or report. Gitlink `libs/nfc-tag-core` stays `7e0377a`. Commits: one
  casual subject, no body, no trailers.

## 1. Audit (controller, read-only, done 2026-09-26; values confirmed by the review against material3 1.4.0 / ui 1.12.0)

`ui/components/ActionGrid.kt` (69 lines) is the only grid; `AssetDetailScreen.kt:320` is its only
production caller (plus `Previews.kt:148`). Its shape today:

- `ActionGrid` chunks the actions in pairs; each `Row` gives both buttons `weight(1f)` with a 10dp
  gap; an odd last action keeps half the width beside a `Spacer`.
- `ActionButton` draws `OutlinedButton` / `FilledTonalButton` with **`Modifier.height(44.dp)`** — a
  fixed height, not a floor — and a `Text(action.label, labelLarge)` with no wrapping policy.
- `labelLarge` is 14sp Medium on a 20sp line height (`ui/theme/Type.kt:18`).

Why the words disappear. A Material 3 button pads its content 24dp horizontally and 8dp vertically
(`ButtonDefaults.ContentPadding`; icon 18dp, `IconSpacing` 8dp, `MinHeight` 40dp), so under the
fixed height the label's box is at most **28dp tall** whatever the font scale. On a 412dp-wide phone
the screen's 16dp gutters and the 10dp gap leave each button about 185dp, and after the padding, the
icon and its spacing the label has about **111dp**. Both cited labels are wider than that at 14sp
Medium, so both wrap — correctly — onto a second line; the `Text` is then coerced to its 28dp box
and its own default `TextOverflow.Clip` cuts the second line (`flush`, `actions`); the button's
shape clip sits behind that. At larger font scales the label grows non-linearly (14sp reads 17.2 /
18.8 / 22 / 26dp at 1.2 / 1.3 / 1.5 / 2.0, line height in proportion: 26.9dp at 1.3, 31.4dp at 1.5,
37.1dp at 2.0), so from 1.5 even a one-line label is cut. This is a layout defect in one composable;
nothing in the view models, the labels or the theme is wrong. (The issue's `Readings & Options` is
today's `Readings & actions`, the same button.)

Two facts that shape the fix (both from the review, both verified against the resolved artifacts):

- **Material's minimum interactive box.** A clickable material3 `Surface` (so both button types)
  applies `minimumInteractiveComponentSize()`: its layout box is at least 48dp tall unless the
  `LocalMinimumInteractiveComponentSize` local disables it. Today's exact `height(44.dp)` coerces
  that box back to 44dp, which is why the visible rhythm is 44dp buttons with 10dp gaps (54dp row
  pitch). A naive `heightIn(min = 44.dp)` would silently widen every gap to 14dp and, under an
  intrinsic levelling, stretch short buttons to 48dp. The 48dp **touch** target does not come from
  that box: Compose's own minimum-touch-target expansion gives a 44dp clickable a 48dp touch
  height, and keeps doing so.
- **Non-linear font scaling.** From ui 1.12.0, `sp → dp` goes through the platform's font-scale
  tables whenever the scale is ≥ 1.03 — in production and in a test's `Density(density, fontScale)`
  alike. Large sp values barely grow (84sp is 84dp at 1.3 and about 86dp at 2.0) while the label
  face does (14sp → 18.8 / 26dp). A threshold written as a raw sp constant would never engage.

Precedents in the tree: `ui/attachments/DocumentsDescriptionLineTest.kt:124-127` measures a `Text`
node through the unmerged tree; `ComponentsSmokeTest.kt` is the package's `createComposeRule` +
`ServiceTagTheme` harness.

## 2. The treatment (the brief's contract)

- **C1, the button grows.** The fixed height becomes a floor of 44dp. The label wraps onto as many
  lines as it needs (`softWrap` true, no `maxLines`, no overflow policy) and the button's height
  follows it. The grid composes its buttons under
  `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified)` so the
  button's layout box is its visible surface, as it effectively is today: a one-line label at font
  scale 1.0 measures exactly 44dp, rows sit on a 54dp pitch, gaps stay 10dp.
- **C2, a pair stays level, and the derived height is a minimum.** In a two-column row both buttons
  are as tall as the taller of the two. The grid is one custom `Layout` (C7) that, per row, splits
  the width into the two column widths itself (gap subtracted, the odd pixel to one fixed side),
  asks each button's `maxIntrinsicHeight` **at the column width it will be measured at**, takes
  `h = max(44dp, both)`, and measures each button with its column width fixed and `minHeight = h`,
  `maxHeight` unbounded; the row's height is the taller measured button. Why not
  `Row(height(IntrinsicSize.Max))` + `fillMaxHeight()`: a weighted row rounds its intrinsic split
  and distributes the measure-pass remainder differently, so on an odd-pixel width (a 412dp phone
  is one) the first button is measured one pixel narrower than the width its height came from, and
  a label that fits by exactly that pixel wraps in measure only and is clipped again — the defect
  this plan removes. Same width in both passes, and a minimum rather than an exact height, close
  that door: in the worst case a pair is stepped, never cut. Levelling applies to two-column rows
  only; a one-column row and the odd last half-width button take the floor alone.
- **C3, the columns adapt, in ems of the label face.** Two columns are drawn when each column can
  give its label at least **K ems of `labelLarge`**, with the em taken **after** conversion:
  `minLabel = labelLarge.fontSize.toDp() × K` (converting `fontSize × K` first reproduces the
  non-linear bug). A column's fixed chrome is the button's horizontal content padding, the icon
  and its spacing, read from `ButtonDefaults` (`ContentPadding` start + end, `IconSize`,
  `IconSpacing`; 74dp at today's values, never typed as 74). Two columns when
  `availableWidth ≥ 2 × (chrome + minLabel) + 10dp`, else one. With **K = 6** (84dp at scale 1.0;
  R68-2) the thresholds are 326 / 364.4 / 383.6 / 422 / 470dp at scales 1.0 / 1.2 / 1.3 / 1.5 / 2.0:

  | available width (phone width − 32dp gutters) | 1.0 | 1.2 | 1.3 | 1.5 | 2.0 |
  |---|---|---|---|---|---|
  | 380dp (a 412dp phone) | two | two | one | one | one |
  | 328dp (a 360dp phone) | two | one | one | one | one |
  | 300dp (a narrow window) | one | one | one | one | one |

  (K = 5.8 — thresholds 320.4 / 357.5 / 376.1 / 413.2 / 459.3 — keeps a 412dp phone on two
  columns through 1.3 by 3.9dp; the owner picks K in R68-2.) In one column every button spans the
  full width, in list order (the profiles first, then `Write tag`, `Edit`, `Readings & actions`,
  `Backup`, and `Set up from template` only on an asset with no profiles — `detailActions()` is
  unchanged). In two columns an odd last action keeps its half width, as today.
- **C4, nothing else moves.** Colours, border, shape, typography, icon size, content padding, the
  outlined/tonal split as production has it, the 10dp gaps, the 54dp pitch and the order are
  unchanged; no `textAlign` (a wrapped label's lines keep the default start alignment inside the
  centred icon + label group); a short label at scale 1.0 produces the same layout as the base
  commit (44dp tall, two per row, same widths, same pitch).
- **C5, nothing is cut.** The load-bearing fact is the label's `TextLayoutResult`:
  `didOverflowHeight` and `didOverflowWidth` are false for every label (AC 3's "no vertical
  clipping"). The label's layout box lying inside its button's box is asserted too, as a
  containment check, but it is not what detects the defect (a coerced 28dp box is still inside).
- **C6, tap targets.** Every button keeps its click action and its callback, and its touch height
  stays 48dp through Compose's minimum-touch-target expansion (AC 5).
- **C7, the mechanism.** One custom `Layout` in `ActionGrid.kt` owning the column decision (from
  `constraints.maxWidth` and the `MeasureScope` density) and the row measurement of C2. No
  `BoxWithConstraints` (a subcompose layout, needless here), no `IntrinsicSize` row, no new
  composable outside `ActionGrid.kt`, no dependency.

## 3. Files (indicative; the implementer owns the placement)

**Modify**
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/components/ActionGrid.kt` — C1–C3, C7; the
  KDoc rewritten: the 44dp is a floor, how the columns are chosen (the em rule and why ems), why the
  width is split by hand (C2), why the interactive-size local is unset (C1); cite D12 §8, G1 §1.1,
  issue #68.
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/components/Previews.kt:148` — the
  `ActionGridPreview` gains one long invented label beside short ones so the design tooling shows
  the wrap (not a production string).

**Create**
- `app/src/androidTest/kotlin/com/loosecannon/servicetag/ui/components/ActionGridTest.kt` — §4.

**Untouched:** `AssetDetailScreen.kt`, `JournalFormat.kt`, `ui/theme/*`, every string, every view
model, `core`, the API, the MCP, the tools, the schema, the backup format.

## 4. Test matrix (hazards; the brief fixes names)

One class, `ActionGridTest`, `junit4.v2.createComposeRule`, `ServiceTagTheme`. A helper composes
`ActionGrid(actions, Modifier.fillMaxWidth())` inside `Box(Modifier.width(w))` under
`CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = s))`
— the frame width stands in for a phone's content width, the density override for the Android
font-size setting, and it uses the same non-linear tables as a device. Frame widths: 380dp (a 412dp
phone after its gutters), 328dp (a 360dp phone), 300dp (narrow). Scales: 1.0, 1.2, 2.0. Every
expected column count is **hard-coded from the C3 table for the chosen K** (the test never calls
the rule, or the RED mutations would be mirrored into the oracle). Cases sit on margins of ≥ 15dp.

Reading a node: the label is found in the **unmerged** tree (a button merges its descendants); its
`TextLayoutResult` comes from the `SemanticsActions.GetTextLayoutResult` action on that node; its
button is the merged node with the click action and that text. Bounds via `getBoundsInRoot()` /
`getUnclippedBoundsInRoot()`; a half-dp tolerance on equalities. "Column count" = the number of
distinct button lefts in a row. The production-shaped fixture is
`[Log Descale and flush, Write tag, Edit, Readings & actions, Backup]` (a profile, then the four
utility actions in production order; the odd fifth exercises the half-width row).

| hazard | case | assertions | RED mutation |
|---|---|---|---|
| the reported defect (AC 1, 2) | `theProductionShapedGridRendersEveryWord`: 380dp, 1.0, the production-shaped fixture | every label: no overflow in either axis; its box inside its button. Two columns. The first pair level: equal tops, equal heights, > 44dp; `Write tag` as tall as its neighbour. `Backup` alone on the third row at half width, left | restore `height(44.dp)` → `didOverflowHeight` |
| a long user-defined name (AC 3) | `aLongUserDefinedLabelStaysInsideItsButton`: 380dp, 1.0; a 55–65-character `Log …` label beside `Edit` | no overflow; inside its button; the pair level; the button taller than 44dp | measure the pair without the derived minimum → the heights differ |
| narrow width (AC 4, 6) | `aNarrowWidthDrawsOneColumn`: 300dp, 1.0; the fixture | one column: every button's left equal, width ≈ 300dp; no overflow anywhere | hard-code two columns |
| font scale, still two columns (AC 4, 6) | `aLargerFontKeepsTwoColumnsWhileTheyFit`: 380dp, 1.2; the fixture | two columns; no overflow; each pair level; every button ≥ 44dp | one column whenever `fontScale > 1` |
| font scale, one column (AC 3, 4, 6) | `aLargeFontDrawsOneColumn`: 380dp at 2.0 and 328dp at 1.2, the fixture plus the long label of case 2 | one column in both; no overflow; every button ≥ 44dp and full width | a threshold converted as `(fontSize × K).toDp()` or as a raw sp constant → two columns |
| short labels unchanged (AC 5) | `shortLabelsKeepTheirHeightRhythmAndTaps`: 380dp, 1.0; `Write tag`, `Edit`, `Backup`, `History`, `Set up from template` | each button 44dp tall (±0.5); two per row, widths ≈ 185dp; row-2 top − row-1 top == 54dp (±0.5); the fifth alone at half width, left; `assertTouchHeightIsEqualTo(48.dp)` on one outlined and one tonal button; `performClick` on each of those reaches its callback; every button `hasClickAction()` | drop the interactive-size local → the pitch becomes 58dp; change the floor to 48dp |
| regression | `ComponentsSmokeTest`, `AssetDetailConditionHealthSeasonTest`, `SeasonReconciliationNavigationTest` unchanged and green on the branch (the last two drive `Edit` / `Readings & actions` on the real page) | — | — |

## 5. Strings — none

No new user-visible string; every existing label verbatim. Nothing to ratify.

## 6. Rulings (controller, 2026-09-26; the owner reads these before dispatch and may override any)

- **R68-1, the treatment.** Grow the button (C1), level the pair with a derived minimum (C2), fall
  back to one column by a width-and-font-scale rule (C3) — D12 §8's own sketch of the region is a
  vertical list, so one column is inside the design. Rejected: ellipsis or `maxLines` (drops the
  verb/object — forbidden by the issue and the owner); a smaller label style at wrap (`labelLarge`
  is the button face); label-aware layout (deciding columns from the actual labels would make the
  grid jump when a profile is renamed).
- **R68-2, the threshold: K = 6 ems (recommended).** Six ems of `labelLarge` — 84dp at scale 1.0,
  about eleven characters — keeps a 360dp phone on D12's two columns at 1.0 and a 412dp phone on
  two columns at 1.0 and 1.15, and gives one full-width column from the 1.3 step up, where a
  two-column three-word label would already need three lines. Alternative **K = 5.8** keeps a 412dp
  phone on two columns at 1.3 (by 3.9dp). Either is one named constant with the arithmetic in its
  KDoc; the tests' hard-coded expectations do not change between the two (their cases avoid the
  margins). **RATIFIED 2026-09-26: K = 6** — the owner: at 412dp and scale 1.3, K = 5.8 keeps two
  columns by only 3.9dp; prefer the full-width fallback there, since #68's objective is legibility
  under larger text, not two-column persistence.
- **R68-3, no maximum.** A very long profile name makes a tall button, never a truncated one.
- **R68-4, the 44dp.** G1's "44dp" is read as the height of a one-line action and becomes the floor;
  the G1 report is a historical gate record and is not edited.
- **R68-5, proof surface.** Compose instrumented tests with a fixed frame width and a `LocalDensity`
  font-scale override (no device configuration changes, no screenshots); the production page's
  own test classes stay as the regression check.
- **R68-6, the rhythm.** The grid unsets Material's minimum interactive component size so its
  buttons' layout boxes stay their visible 44dp and the 10dp rhythm of G1 is kept exactly as today;
  touch targets stay 48dp through Compose's touch expansion (C6, asserted). Alternative: accept
  48dp boxes — then the pitch becomes 58dp and C4, G1's rhythm and the short-label case change to
  say so. **RATIFIED 2026-09-26: the existing 44dp visible button / 54dp row pitch**, with the 48dp
  effective touch target through Compose's touch-target expansion, as specified and tested.

## 7. What this plan does not do

No change to which actions appear or their order; no change to the profile-name rules; no
dashboard or other grid (there is none); no correction of production's pre-existing outlined/tonal
split versus G1's row (profile log actions are tonal today; C4 freezes it, out of scope); no version
bump (#68 rides with the next feature-bearing release with Phase 1A, #83 and #78); no real-phone
run (the emulator proves it; the owner sees it on the phones with that release).

## 8. The brief (one implementer)

**Read first:** §1–§7 above; issue #68's body (AC 1–6); D12 §7–§8; G1 §1.1 row "Quick actions".
**Lane:** alone, branched from master after this plan's rev 2 commit. **Blocked on:** nothing.

### Files

**Modify**
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/components/ActionGrid.kt` — the whole treatment
  (C1–C3, C7) and its KDoc.
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/components/Previews.kt` — `ActionGridPreview`:
  one of its four labels becomes a long invented one.

**Create**
- `app/src/androidTest/kotlin/com/loosecannon/servicetag/ui/components/ActionGridTest.kt`.

**Untouched:** everything else (§3).

### Interfaces

```kotlin
data class ActionSpec(label, icon, outlined, onClick)                                  // unchanged
@Composable fun ActionGrid(actions: List<ActionSpec>, modifier: Modifier = Modifier)   // unchanged signature
// ActionGrid.kt, private: one named constant, the em count K of C3 (the value from R68-2);
// one custom Layout whose measure policy decides 1 or 2 columns from constraints.maxWidth,
// the chrome from ButtonDefaults and labelLarge.fontSize.toDp() × K, and measures each
// two-column row as C2 says (same width in both passes, derived height as a minimum).
```

### Contracts

C1–C7 of §2 verbatim; the C3 table for the ruled K is the expected behaviour at each width and scale.

### Test matrix

§4 verbatim: six cases in `ActionGridTest`, each with its RED mutation shown and quoted in the
report; the three regression classes run unchanged.

### Gate

- `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --console=plain`: zero
  failures, zero skips; the report quotes counts.
- Connected, on `emulator-5554` only, one class per invocation:
  `com.loosecannon.servicetag.ui.components.ActionGridTest`, then
  `com.loosecannon.servicetag.ui.components.ComponentsSmokeTest`, then
  `com.loosecannon.servicetag.ui.asset.AssetDetailConditionHealthSeasonTest`, then
  `com.loosecannon.servicetag.ui.asset.SeasonReconciliationNavigationTest`.
- `git grep -nE '^[^*/]*\b(maxLines|overflow|fontSize) *=|^[^*/]*\.height\(44\.dp\)|BoxWithConstraints|IntrinsicSize' -- app/src/main/kotlin/com/loosecannon/servicetag/ui/components/ActionGrid.kt`
  → no output (the KDoc may name them; code may not); `git grep -n 'Descale' -- app/src/main` → no output.
- `git diff <base> --stat -- core tools docs/api app/src/main/kotlin/com/loosecannon/servicetag/ui/asset app/src/main/kotlin/com/loosecannon/servicetag/ui/journal app/src/main/kotlin/com/loosecannon/servicetag/ui/theme` → empty.
- `grep -rnE '(^|[^.[:alnum:]_])assert\(' app/src/androidTest` → no output.
- Hygiene; gitlink `7e0377a`; `git status` clean.

### Strings

None new. Test fixtures use `Log Descale and flush` and `Readings & actions` (AC 1–2 name them)
plus invented long labels; production code names no label.

### Must NOT

Shorten, ellipsise, abbreviate, re-style or branch on any label; add `maxLines` or an overflow
policy; touch `quickActionLabel()` or `detailActions()`; change colours, shape, padding, icon size
or the outlined/tonal split; convert `fontSize × K` before `toDp()`; use a raw `sp` constant as the
threshold; measure a button at a width other than the one its height was derived at, or with an
exact derived height; add a screenshot test or a UI-driving harness; change a device
configuration; use any device but `emulator-5554`; add a dependency.

### Review focus

The floor plus the unset interactive-size local (44dp boxes, 54dp pitch); the custom row
measurement (same width both passes, `minHeight = h`, `maxHeight` unbounded); the em rule
converting `labelLarge.fontSize` **before** multiplying; the chrome from `ButtonDefaults`, not
typed; the tests reading the label from the unmerged tree and its `TextLayoutResult` (the
bounds checks are containment only); expected columns hard-coded from the table; the touch-height
assertion; short labels byte-for-byte the base layout; nothing outside `ActionGrid.kt`, the
preview and the new test.

### Size

Small: one composable with one custom layout, one preview line, one test class of six cases.

## 9. Errata (2026-09-26, found in implementation; the contract stands)

- **C5 / §4, `didOverflowWidth`.** For a plain-`String` `Text`, the `GetTextLayoutResult` semantics
  action rebuilds its result at the node's full available width (foundation / ui-text 1.12.0), so
  `didOverflowWidth` reads true for every one-line label narrower than its column and proves
  nothing. A horizontal cut is impossible for a soft-wrapped label whose box is its column; the
  load-bearing fact is `didOverflowHeight`, which is red on the base code and green after the fix.
  The tests assert height overflow and layout-box containment only.
- **§4 case 6, the fixture.** `Set up from template` is not a one-line label at half of 380dp at
  scale 1.0: it wraps (a 52dp button) and the base code clipped it too — a third production
  instance of #68, on assets with no profiles, fixed by the same change. Case 6 pins the 44dp
  height and the 54dp pitch on its four one-line labels and checks the fifth as whole, at least
  44dp, half width and on the left; case 1 pins the odd half-width `Backup` at exactly 44dp.
- **Intrinsics (deferred).** The grid's measure policy answers no intrinsic query of its own; a
  parent asking for `IntrinsicSize` would see one-line rows at Material's 40dp minimum rather
  than the 44dp floor. No caller asks today; recorded in the KDoc, not fixed.
