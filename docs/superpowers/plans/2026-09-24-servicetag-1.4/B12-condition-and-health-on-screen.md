# B12 — condition and health on screen: the vocabulary, the scan sheet, Change condition and the offers

**Read first:** master plan §1, §9 (condition and the offer), §10.2 (driver-line data), §13.1 (`scanSheetContent`), §13.4 (D12), §14, §19.
**Spec:** §5.4, §10.1 (the scan sheet), §10.6 (accessibility and the families; O-2), §3.3 (the season offer), §6.6; strings S1–S20, S22, S23, S25, S27, S40, S41, S51–S53, S95–S106, S108–S110, S139, S142, S143.
**Wave 7, lane B,** beside B11. **After B07** (and B04, B06). Gates B13 and B14. Device first in wave 7.

## Goal

Give condition and health their one presentation, and put it where the owner stands at the equipment. Add the D12 tokens and icons (operational and nominal cool blue; degraded and warning amber with **DEGRADED on its own token**, distinguishable from Due and Due soon; down and critical in the error family; not recorded and not tracked neutral) and the pure word functions and badges every later screen reuses, so each state is told apart by **word, icon and position** with colour only reinforcing. Rebuild the scan sheet into spec §10.1's seven blocks from `scanSheetContent`, add the shared **Change condition** sheet and the **Mark operational** confirmation, and offer — never apply — "Mark operational?" after a completion or a maintenance or replacement event on a DOWN or DEGRADED asset, and "Start the season now?" / "End the season now?" after a season event on a MANUAL asset. The scan still writes nothing except through an explicit tap.

## Files

**Create**

- `app/src/main/kotlin/com/loosecannon/servicetag/ui/condition/ConditionWords.kt` — S1–S4, S8–S13, S22, S23, S27 and `conditionWord`, `conditionHelper`, `sinceLine`, `componentLine`.
- `app/.../ui/condition/ConditionBadge.kt` — word + icon + since; the not-recorded form.
- `app/.../ui/condition/ChangeConditionSheet.kt`, `ChangeConditionViewModel.kt` — S5, S8–S16, S25 and Cancel.
- `app/.../ui/condition/MarkOperationalDialog.kt` — S17, S18, S7 and Cancel.
- `app/.../ui/condition/Offers.kt` — the operational offer (S17 title, S19 body, S7, S20) and the season offer (S51 / S52 title, S53 body, S40 / S41, the shipped "Not now").
- `app/.../ui/health/HealthWords.kt` — S95–S98, the driver lines S99–S106, S142, S143, the aggregate S108, the critical line S109, the dashboard row S110; the two quantity-bearing lines through `HealthPlurals` (below).
- `app/.../ui/health/AndroidHealthPlurals.kt` — `class AndroidHealthPlurals(resources: Resources) : HealthPlurals`, the production glue: `getQuantityString` over the two plural ids of `plurals.xml`, with the quantity passed as the selector.
- `app/src/main/res/values/plurals.xml` — **the plurals resource** (controller ruling, dec. 29): `health_age_days` for S99's `<age>` (`one` "1 day", `other` "%d days") and `health_days_overdue` for S102 (`one` "%1$s is 1 day overdue", `other` the ratified S102 text). The `other` forms are the ratified text verbatim; S104 is singular-safe and stays a plain string.
- `app/.../ui/health/HealthBadge.kt` — band word + score + bar icon; the NOT TRACKED form.
- `app/src/main/res/drawable/` — `ic_task_alt.xml`, `ic_trending_down.xml`, `ic_block.xml`, `ic_radio_button_unchecked.xml`, `ic_signal_cellular_alt.xml`, `ic_signal_cellular_alt_2_bar.xml`, `ic_signal_cellular_alt_1_bar.xml`, `ic_signal_cellular_nodata.xml`, `ic_event_available.xml` (spec §10.6's proposed icons).
- Tests: `app/src/test/kotlin/com/loosecannon/servicetag/ui/condition/ConditionWordsTest.kt`, `…/ui/condition/ChangeConditionViewModelTest.kt`, `…/ui/condition/OffersTest.kt`, `…/ui/health/HealthWordsTest.kt`, `…/ui/PresentationGrayscaleTest.kt`; `app/src/androidTest/kotlin/com/loosecannon/servicetag/ui/condition/ChangeConditionSheetTest.kt`; `app/src/androidTest/.../ui/health/HealthPluralsContractTest.kt` (a framework contract test with no navigation: it drives **`AndroidHealthPlurals`** over `InstrumentationRegistry.getInstrumentation().targetContext.resources` — the app's resources, not the test APK's — calling both methods with n = 1 and n = 5).

**Modify**

- `app/.../ui/theme/SemanticColors.kt` — tokens `conditionOperational`, `conditionDegraded`, `conditionDown`, `conditionNotRecorded`, `healthNominal`, `healthWarning`, `healthCritical`, `healthNotTracked`, light and dark.
- `app/.../ui/components/ServiceTagIcons.kt` — the nine icons.
- `app/.../ui/maintenance/MaintenanceSheet.kt`, `MaintenanceSheetViewModel.kt` — the seven blocks; the two actions.
- `app/.../ui/maintenance/CompletionFlow.kt` — after a completion, the operational offer per completed DOWN or DEGRADED asset, one at a time (master §9).
- `app/.../ui/journal/EventEntryViewModel.kt`, `EventEntryScreen.kt` — after a saved `MAINTENANCE` or `REPLACEMENT` event, the operational offer; after a saved `SEASON_START` / `SEASON_END` event, the season offer.
- `app/.../di/AppGraph.kt` — only where `CompletionFlow`'s construction needs the `conditions` repository or the graph's `acceptOperationalOffer` (B06) / `acceptSeasonOffer` (B04) fields; every such edit is **mirrored in `app/src/test/.../testing/FakeGraph.kt` in this brief** (master §1). An `AndroidHealthPlurals` over `plurals.xml` is built where the screens need it, not stored in the graph.
- `app/src/test/.../ui/theme/ContrastTest.kt`, `app/src/test/.../ui/maintenance/MaintenanceSheetViewModelTest.kt`, `app/src/androidTest/.../ui/maintenance/ScanSheetTest.kt` — extended.

**Untouched:** `tools/**` (B11's, same wave); `ui/dashboard/**`, `ui/asset/**` (B13, B14); `DueReadModel.kt`, `ScanSheetContent.kt`, `AttentionReadModel.kt`, `AssetHealthReadModel.kt` (B07; consumed, not changed); `core/**`; `api/**`.

## Interfaces

**Consumes:** `scanSheetContent`, `ScanSheetContent`, `ConditionView`, `ComponentCondition`, `AssetHealthReadModel`, `SubjectBandFact` (B07); `DriverLine`, `SubjectHealth`, `HealthBand` (B05); `RecordCondition`, `ConditionCommand`, `operationalOfferFor`, `AcceptOperationalOffer` (B06); `seasonOfferFor`, `AcceptSeasonOffer` (B04).

**Produces** (B13 and B14 reuse these and define none of their own):

- `conditionWord(condition: OperationalCondition?): String`, `conditionHelper(condition: OperationalCondition): String` (S9 / S11 / S13), `sinceLine(since: LocalDate, format: (LocalDate) -> String): String`, `componentLine(component: ComponentCondition): String`; `@Composable ConditionBadge(view: ConditionView?, modifier: Modifier = Modifier)`.
- `bandWord(band: HealthBand?): String` (null = S98), `driverLineText(line: DriverLine, plurals: HealthPlurals, format: (LocalDate) -> String): String?` (null for no line), `aggregateLine(score: Int, contributors: List<SubjectHealth>): String`, `criticalLine(subject: SubjectHealth): String`, `dashboardHealthRow(fact: SubjectBandFact): String`; `@Composable HealthBadge(band: HealthBand?, score: Int?, modifier: Modifier = Modifier)` (null band = NOT TRACKED).
- `interface HealthPlurals { fun ageDays(n: Long): String; fun daysOverdue(title: String, n: Long): String }` — `AndroidHealthPlurals(resources: Resources)` is the Android implementation over `plurals.xml` (B14 builds one from its screen's resources); JVM tests pass a fake, because `:app` JVM tests have no Robolectric and no dependency is added.
- `ChangeConditionSheet(assetId, onDone)` and `MarkOperationalDialog(assetId, current, onDone)` — B14 opens the same composables from asset detail.
- The S40 and S41 constants (B14 uses them for Start/End season).
- The eight tokens and nine icons.

### The scan sheet (spec §10.1, in order)

1. The asset name and #49's caption (shipped).
2. The condition block — `ConditionBadge`: word (S1–S3), icon, the reason or **S23**, and **S22** "since <date>"; **S4** when nothing is recorded.
3. **S7 "Mark operational"** and **S6 "Change condition"** for DOWN or DEGRADED, otherwise S6 alone.
4. Every DOWN or DEGRADED component, as **S27**.
5. Every CRITICAL subject, as **S109**; then the aggregate as **S108** when it is WARNING or CRITICAL.
6. "Maintenance" with the items, or **S139 "Nothing due"**.
7. "Open asset".

Snooze and Postpone keep their 1.2 behaviour on the sheet. The sheet opens only when `ScanSheetContent.opens` (B07) and writes only after an explicit tap.

### Change condition and Mark operational (spec §5.4, §10.1)

- **Change condition** — title **S5**; the three options **S8, S10, S12**, each with its helper **S9, S11, S13**, none preselected; **S14 "What is wrong? (optional)"**; **S15 "When did this change?"**, today by default, past dates allowed, a future date answered by **S25**; **S16 "Save condition"** calls `RecordCondition` once; Cancel writes nothing.
- **Mark operational** — **S17** title and **S18** body (with the current word substituted), **S7** confirms and records OPERATIONAL dated today; Cancel writes nothing. The earlier row stays.

### The offers (spec §3.3, §5.4; inv. 81, 93)

- **Operational:** when `operationalOfferFor` holds after a completion (for each completed member asset, one at a time) or after a saved `MAINTENANCE` / `REPLACEMENT` event: **S17** title, **S19** body (event title, asset name), **S7** accepts through `AcceptOperationalOffer`, **S20 "Not yet"** writes nothing.
- **Season:** when `seasonOfferFor` answers after a saved `SEASON_START` / `SEASON_END` event: **S51** or **S52** title, **S53** body (event title), **S40** or **S41** accepts through `AcceptSeasonOffer`, "Not now" writes nothing.
- An API write never offers (there is nobody to ask).

### Words and substitutions

`<date>` uses the shipped display shape (`d MMM uuuu`, as the snooze line). **Ruled (dec. 29, changed):** S99's `<age>` and S102's `<n>` go through the plurals resource — `other` is the ratified text, `one` drops the unit's "s" ("1 day", "… is 1 day overdue"). These `one` forms are ratified inflections, not new strings (master §17.2). S104 needs no plural. S108 lists the non-archived contributors in `sortOrder`; S109 names one subject.

## Invariants this brief must hold

**(UI)** **57** (the scan never mutates), **81** (only Save condition, the confirmation and an accepted offer write a condition), **93** (the season offer writes only when accepted), **118** (NOT TRACKED drawn as S98, never 100), **119** (the sheet lists every CRITICAL subject and every DOWN or DEGRADED component whatever the aggregate), **123**. The D12 separation of spec §10.6 (O-2).

## Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| a state indistinguishable without colour | `PresentationGrayscaleTest` · `noTwoStatesShareAWordAndAnIcon` (OPERATIONAL, DEGRADED, DOWN, not recorded, NOMINAL, WARNING, CRITICAL, NOT TRACKED, DEFERRED, IN SEASON) | give DEGRADED the warning icon |
| DEGRADED looks like Due | `PresentationGrayscaleTest` · `degradedHasItsOwnTokenDistinctFromDueAndDueSoon` (light and dark) | alias `conditionDegraded` to `dueSoon` |
| unreadable tokens | `ContrastTest` · the eight new pairs meet the shipped threshold | lighten `conditionDown`'s foreground |
| "1 days overdue" ships (dec. 29) | `HealthWordsTest` · `oneAndOtherGoThroughThePlurals` (a fake `HealthPlurals` sees `n = 1` and `n = 5` for S99 and S102) and `HealthPluralsContractTest` (connected) · `androidHealthPluralsSaysOneDayAndNDays` (`AndroidHealthPlurals` over the target context's resources; `ageDays` and `daysOverdue` at n = 1 and n = 5) | `AndroidHealthPlurals` returns the `other` form for every n |
| words drift | `ConditionWordsTest` · `everyWordIsItsRatifiedString` (S1–S4, S8–S13, S22, S23, S27 with an empty reason); `HealthWordsTest` · `bandsDriverLinesAndSummaries` (S95–S106, S108–S110, S142, S143 — Postponed while not late, Up to date otherwise; the archived link draws no line) | render NOT TRACKED as "100" |
| Change condition writes wrong | `ChangeConditionViewModelTest` · `nothingIsPreselectedAndSaveWritesOnce`, `aFutureDateShowsS25AndWritesNothing`, `cancelWritesNothing`, `thePastIsAllowed` | preselect OPERATIONAL |
| the offers apply themselves | `OffersTest` · `anOperationalOfferAfterACompletionOnADownAsset`, `oneOfferPerDownMemberInTurn`, `aMaintenanceOrReplacementEventOffersAnInspectionDoesNot`, `notYetWritesNothing`, `theSeasonOfferOnlyOnAManualAssetInTheOppositePhase`, `notNowWritesNothing` | write on the predicate |
| the sheet's order or content | `MaintenanceSheetViewModelTest` · `theSevenBlocksInOrder`, `markOperationalOnlyForDownOrDegraded`, `nothingDueWhenNoItem` | put maintenance before condition |
| the screens | `ScanSheetTest` (connected) · `aDownAssetWithNothingDueShowsConditionAndNothingDue`, `averageNominalPlusOneCriticalShowsS109`, `aDownComponentLine`, `cancelWritesNothing`; `ChangeConditionSheetTest` (connected) · `threeOptionsHelpersAndSave`, `cancel` | drop the component block |

## Edge cases

- **Nothing recorded:** the block shows S4 and the actions show S6 alone.
- **An empty reason** is drawn as S23 wherever a reason would appear (the block, S27, history).
- **Recording the same condition again** (DOWN → DOWN with a new reason) is allowed and adds a row; "since" stays the start of the run.
- **A CRITICAL subject on an asset whose aggregate is NOMINAL** shows its S109 line and no aggregate line.
- **An aggregate that is NOT TRACKED** is not drawn on the sheet (only WARNING or CRITICAL are).
- **A group round completed for three DOWN members** offers three times in turn; declining one does not cancel the others.
- **A completion from a notification quick action** offers nothing: there is no screen to ask on (the same rule as an API write).
- **A season event dated before the latest activation** is clamped to that date by `AcceptSeasonOffer`; the dialog shows no date of its own.
- **The Change condition date** defaults to today in the device zone; `occurredTime` is left null and `tzId` is the device zone.

## Gate

- `./gradlew :app:testDebugUnitTest --console=plain` → zero failures, zero skips; counts recorded.
- `./gradlew :app:compileDebugAndroidTestKotlin --console=plain`; connected, one class each: `com.loosecannon.servicetag.ui.maintenance.ScanSheetTest`, `com.loosecannon.servicetag.ui.condition.ChangeConditionSheetTest`, `com.loosecannon.servicetag.ui.health.HealthPluralsContractTest`. The JVM `CompletionFlowTest` and `EventEntryViewModelTest` stay green with the offer cases added.
- The review reads every added string literal against spec §10.7 (no grep can prove a sentence is ratified). Anchored: `grep -rnE 'Color\(0x' app/src/main/kotlin/com/loosecannon/servicetag/ui/condition app/src/main/kotlin/com/loosecannon/servicetag/ui/health` → no output (colours only through tokens); `git diff --stat <base> -- core/src/main app/src/main/kotlin/com/loosecannon/servicetag/api app/src/main/kotlin/com/loosecannon/servicetag/ui/dashboard app/src/main/kotlin/com/loosecannon/servicetag/ui/asset tools` → empty.

## Strings

**S1–S20, S22, S23, S25, S27, S40, S41, S51–S53, S95–S106, S108–S110, S139, S142, S143**, verbatim; the shipped "Maintenance", "Open asset", "Not now" and Cancel. S143 (ratified under O-9) is the postponed driver line.

## Must NOT

- let health open the sheet, or an aggregate hide a CRITICAL subject or a DOWN component;
- preselect a condition, write one from a scan or a completion, or apply an offer without its tap;
- distinguish a state by colour alone, or give DEGRADED a Due / Due soon token;
- draw NOT TRACKED as a number;
- touch the read models, the dashboard, the asset screens or `:core`.

## Review focus

- Every added literal is an S-number's exact text; the reviewer reads each against spec §10.7. The two `one` forms in `plurals.xml` are the only accepted inflections (dec. 29, master §17.2) and must differ from their `other` forms only by the unit's "s".
- Colour never carries a state alone: the grayscale test is the check, and the DEGRADED token is compared by value against Due and Due soon in both themes.
- No write path exists from the sheet except Save condition, the Mark operational confirm, an accepted offer and the shipped completion.

## Size

Large. Split seam: **B12a** tokens, icons, words, badges and the scan sheet; **B12b** Change condition, Mark operational and the offers. B12a lands first; B13 and B14 need only B12a plus the Change condition composable.

## Carry-forward from B05's review (controller, 2026-09-24)

- A baseline dated after today reads as age 0 in the engine (B05's fix round), so S99 never shows a negative age; no wording is needed. One Compose case: a future-dated replacement draws "0 days ago" through the plurals resource (RED: pass the raw negative through).
- `HealthRow`, `HealthState`, `HealthAction` stay the reminder-health names in `ui.maintenance`; asset-health presentation types take other names.

## Carry-forward from B06 (controller, 2026-09-25)

- The "Mark operational?" offer (decision 12) is not made for an event dated after today (the command would refuse it with `CONDITION_DATE_IN_FUTURE`; no clamp exists). One Compose case (RED: offer it anyway).
- The offer is made once: the button disables on tap and the offer is never shown again for the same event, so a double tap cannot write a second OPERATIONAL row. One view-model case (RED: leave the button enabled).

## Carry-forward from B06's review (controller, 2026-09-25)

- The "Mark operational?" offer is never made for an event whose zone does not resolve on this asset (the command would refuse it); one view-model case (RED: offer it).

## Carry-forward from B06's follow-up (controller, 2026-09-25)

- A restored condition may name a zone this device cannot resolve (B06's content pass judges a zone by its form within the archive, never against the device). Any surface that shows a condition's zone shows it as text and never resolves it without a null-safe guard; nothing crashes or hides the row. One test row where it is drawn (RED: resolve unguarded).
