# Localization

ServiceTag's words are Android string resources (#102). English in `app/src/main/res/values/` is the source;
each `values-<language>/` directory is a language pack carrying the same names. Terminology:
[localization glossary](localization-glossary.md).

## The rule

**Localization is presentation. The domain stays language-neutral.**

- Room, backups, Transfer Packs, the Developer API, MCP and NFC tags carry enum names, ids, ISO dates and the
  owner's own text. No translated word is ever stored, backed up, served or written to a tag, so changing the
  phone's language changes nothing but what the screen says.
- What the owner typed — names, notes, titles, serial and model numbers, borrowers, units — is shown exactly as
  entered, in whatever language they entered it. Nothing is machine-translated.
- A sentence is one resource with positional placeholders (`%1$s`, `%2$d`), never English pieces glued together, so
  a translation can reorder it. A count is an Android `<plurals>` whose every form shows the number through a
  placeholder (Russian's `one` also takes 21; French's takes 0).

## Where the words live

| | |
|---|---|
| `values/strings.xml` | the app name and the language-dependent formats: the display date (`format_date_display`, English `d MMM uuuu`), the day-and-month date, the date and time (`format_date_time_display`, English `d MMM uuuu, HH:mm`), the list separator |
| `values/strings_<area>.xml` | one file per feature area; every name in a file starts with that area's prefix (`ha_`, `maintenance_`, `scan_`, …) |
| `values/plurals.xml` | the health ages and overdue counts |
| `values-<language>/` | the same files, translated |
| `xml/locales_config.xml` | the languages Android's per-app language setting offers |

XML comments above an English string say where it appears, what each placeholder is, and carry the ratification
id (`P16-12`) where the copy was ratified. They are a translator's only context: keep them accurate.

## Reading text in code

| Where | How |
|---|---|
| a `@Composable` body | `stringResource(R.string.x, args…)` |
| everywhere else — a view model's state, a notification, a read model, a callback | `localized(R.string.x, args…)` from `l10n/Localized.kt` |
| a count | `localizedPlural(R.plurals.x, count, args…)` |
| a date the owner reads | `localizedDate(date)` / `localizedMonthDay(date)` — never `ofPattern("d MMM uuuu")` |
| a moment the owner reads (the last backup, the last check) | `localizedDateTime(dateTime)` — never an ISO stamp or a fixed `yyyy-MM-dd HH:mm` |
| a number the owner reads | `localizedDecimal(value)` (or `decimals = n`) — never `Locale.US` or a bare `toString()` |
| a number the owner types | `parseLocalizedDecimal(text)` — never `toDoubleOrNull()`, which refuses "0,5"; hand `:core` the parsed number, not the typed text |
| names joined into one run | `localizedList(names)` |
| a rule that differs by language but is not words | a `<bool>` in `values/bools.xml`, read with `localizedFlag`; a pack overrides it in its own `bools.xml` only where its grammar differs (German keeps an action name's capital in "Wassertest eintragen") |

`ServiceTagApp` installs the Android resources before anything can render. Text is read when it is drawn and never
cached in a top-level or companion `val`, which would keep the first language it was read in: a catalog entry is a
getter (`val HA_TITLE: String get() = localized(R.string.ha_title)`) or a function. A view model that already holds a
rendered sentence (a notice on screen) shows it in the old language until it next renders, after a language change.

Not words, and not localized: log lines, exception and `check` messages, test diagnostics, wire and API values,
MIME types, URLs, file names (Transfer Pack and backup names stay ISO and language-neutral). A literal that reads
like prose but is one of these carries `// l10n-ok: <reason>` on its line.

**Starter content stays English in this release.** The seed templates (`core/journal/SeedTemplates`) and the built-in
categories (`CategorySuggestions`) become the owner's own records when applied, and a built-in category is matched by
its English spelling (#74). Localizing them needs language-neutral keys for the built-ins first.

## Adding a string

1. Add it to the area's `values/strings_<area>.xml` with the area prefix, a comment saying where it appears and
   what each placeholder is.
2. Read it with `stringResource` / `localized`; never write English in Kotlin.
3. Add it to **every** shipped language pack in the same change — a pack must never fall back to English. Use the
   glossary's terms; add a term there first if it is new.

## Adding a language

1. Create `values-<language>/` (or `values-b+<script tag>/`, as Simplified Chinese does) with every
   `strings*.xml` and `plurals.xml` translated, every plural form the language needs, and its own
   `format_date_display`, `format_date_month_day`, `format_date_time_display`, `format_list_separator` and
   `format_language` (its ISO 639
   code, which picks plural rules). Check `values/bools.xml` and override any rule its grammar needs.
2. Add its tag to `xml/locales_config.xml`.
3. Add its plural rule to `PluralRules` in the unit tests and a column to the glossary.
4. Run the unit tests: `LocalizationCoverageTest` fails until all of the above agree.

## Languages

| Language | Directory | Notes |
|---|---|---|
| English | `values` | source |
| Spanish | `values-es` | |
| Portuguese | `values-pt` | Brazilian usage; serves every Portuguese locale until a `pt-PT` pack exists |
| Simplified Chinese | `values-b+zh+Hans` | a script, not a region: serves zh-CN, zh-SG and any Hans locale. Traditional Chinese (`zh-Hant`) is a separate pack, not yet shipped, so a Traditional-Chinese device shows English rather than Simplified |
| French | `values-fr` | |
| Japanese | `values-ja` | |
| Russian | `values-ru` | |
| German | `values-de` | |
| Italian | `values-it` | |
| Hindi | `values-hi` | the first India-focused language; others may follow as their own packs |

Haitian Creole is deferred from the initial programme (#102).

**Translation status.** The first packs were drafted with the glossary and checked mechanically (coverage,
placeholders, plural forms, product names). They have not yet had a native speaker's review; permission, security
and network text (Home Assistant, Location, notifications, the Developer API) should be reviewed first.

## Choosing the language

The app follows the phone's language. On Android 13 and later the owner can also give ServiceTag its own language in
Android's settings (Settings → System → Languages → App languages, or the app's info page), which lists exactly the
languages in `locales_config.xml`. There is no in-app language picker: Android's per-app setting is the mechanism, and
an in-app entry point to it can be added later without a custom locale framework. A language change renames the
notification channels in system settings.

## Layout

Text in another language is often longer than the English. Screens scroll rather than clip, buttons wrap rather than
truncate, and nothing positions by left or right: layouts use start/end and directional icons are
`Icons.AutoMirrored`, so a right-to-left pack needs only its strings and `android:supportsRtl="true"`
(`LayoutDirectionGuardTest`).

Debug builds carry Android's pseudolocales. To check a screen for expansion, enable developer options on the
emulator, add **English (XA)** under Languages and open the screen: every string is accented and about a third
longer, so anything that clips or overlaps shows at once. The shipped packs are checked the same way by choosing the
language, ideally German or Russian for length and Japanese or Chinese for line breaking.

## Keeping new work localized

The unit tests are the gate, and CI runs them on every pull request: a change that writes English in Kotlin, adds an
English string without every pack, compares rendered words or draws a date or number in a fixed format fails
`UiLiteralGuardTest` or `LocalizationCoverageTest` before review. What the tests cannot see, a plan has to carry:

- **Copy is ratified as resources.** A plan that ratifies new words (a `P<n>-<m>` id) lands them in
  `values/strings_<area>.xml` with the id in the comment, and in every pack in the same change.
- **A branch cut before the resource layer** (1.7.1 was) brings its words in on merge: each new constant becomes a
  resource and a getter, with nine translations, before the merge is pushed.
- **A translation is a draft until a native speaker reviews it**; say so in the pull request when a change adds one.

## Tests

| Test | Proves |
|---|---|
| `LocalizationCoverageTest` | every pack has every English name and no other; placeholders match; each language's plural forms exist and show their number; every string formats in its language; date patterns are valid; product names and URL schemes survive; `locales_config.xml` lists exactly the shipped packs |
| `UiLiteralGuardTest` | no user-visible English literal is left in Kotlin outside `api/` and `data/`; the API and Room never read UI text; no behaviour compares rendered text (a typed value decides, the words only draw it); no date pattern, fixed locale or `toDoubleOrNull()` outside `l10n/` without an `// l10n-ok:` reason |
| `LocalizedFormatsTest` | dates, moments and decimals follow the language (English and German side by side); typed decimals read back what was drawn, and "45.000" is refused in a comma language rather than read as 45 |
| `LayoutDirectionGuardTest` | no layout assumes left-to-right |
| `EnglishResources` / `ResourcePack` | not tests: the unit tests read `res/values` (or any pack) the way Android does, so a view-model test asserts the same ratified English it did before the words moved |
