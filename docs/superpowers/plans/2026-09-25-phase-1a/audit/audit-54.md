# Audit — Issue #54: CI workflow action SHA pinning

Repository: ServiceTag (GonzRon/ServiceTag), audited at master 6a7a161.

## Classification

**STILL RELEVANT BUT NEEDS REFRAMING**

## Original purpose

Filed 2026-09-22, noted during the 1.1.0 release review: `ci.yml` "disagrees with
itself" on supply-chain pinning. The job that runs unit tests (issue calls it the
"unit job") pins its five actions to floating major tags (`@v5`, `@v5`, `@v4`, `@v6`,
`@v7`), while the `mcp` job added in 1.1.0 pins `actions/checkout` and
`astral-sh/setup-uv` to full 40-character commit SHAs with a trailing `# vX.Y.Z`
comment. The ask: pin the floating job's five actions the same way, and give
`release.yml` the same treatment "if it floats too."

## Current reality

Every external `uses:` line under `.github/workflows/*.yml`, master 6a7a161. No
reusable-workflow `uses:` lines and no `docker://` images exist in either file.

| File | Line | Job | Action | Pin state |
|---|---|---|---|---|
| ci.yml | 9 | `build` | `actions/checkout` | floating — `@v5` |
| ci.yml | 13 | `build` | `actions/setup-java` | floating — `@v5` |
| ci.yml | 17 | `build` | `android-actions/setup-android` | floating — `@v4` |
| ci.yml | 21 | `build` | `gradle/actions/setup-gradle` | floating — `@v6` |
| ci.yml | 26 | `build` | `actions/upload-artifact` | floating — `@v7` |
| ci.yml | 38 | `mcp` | `actions/checkout` | pinned — `fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09` `# v5.1.0` |
| ci.yml | 41 | `mcp` | `astral-sh/setup-uv` | pinned — `c18668ad3cf93ea998bef934396af7bb5c839dc7` `# v10.2.0` |
| ci.yml | 52 | `bundle` | `actions/checkout` | pinned — same SHA `# v5.1.0` |
| ci.yml | 55 | `bundle` | `astral-sh/setup-uv` | pinned — same SHA `# v10.2.0` |
| ci.yml | 66 | `schedules` | `actions/checkout` | pinned — same SHA `# v5.1.0` |
| ci.yml | 69 | `schedules` | `astral-sh/setup-uv` | pinned — same SHA `# v10.2.0` |
| release.yml | 16 | `release` | `actions/checkout` | pinned — `fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09` `# v5.1.0` |
| release.yml | 27 | `release` | `actions/setup-java` | pinned — `b6effb05e454b25005698d916606bdc6ffcbf961` `# v5.7.0` |
| release.yml | 31 | `release` | `android-actions/setup-android` | pinned — `be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd` `# v4.0.4` |
| release.yml | 35 | `release` | `gradle/actions/setup-gradle` | pinned — `9c971963bec38e04b3d30dcc455b5382be2fdbfb` `# v6.3.0` |

**Note on the job name**: there is no job literally named `unit` in `ci.yml`'s
history — it has always been `build` (the step inside it is named "unit tests and
debug build"). The issue's "unit job" is descriptive shorthand for `build`, not a
literal job id.

**When pinning was introduced** (`git log -S`):
- `release.yml`: pinned first at `6896562` ("release: pin the privileged actions to
  commits, and ask git which tags point at head", 2026-09-17), then its pinned SHAs
  were bumped forward (still pinned, not un-pinned) at `26c83d5` ("workflows: move
  off the deprecated node20 actions, pin the runner to 24.04", 2026-09-18).
- `ci.yml`'s `build` job: that same commit `26c83d5` bumped `build`'s actions from
  `v4`/`v4`/`v3`/`v4` to `v5`/`v5`/`v4`/`v6` as floating tags — in the same commit
  that pinned `release.yml`'s SHAs. That is the literal origin of the inconsistency
  the issue names. `upload-artifact` was later floated to `@v7` by `dbe4cc2`
  ("ci: upload-artifact moves to v7 too").
- `ci.yml`'s `mcp`/`bundle`/`schedules` jobs: added pre-pinned via SHA at `47f4c5d`,
  `6dcc46d`, `3f38eba` respectively (all part of the 1.1.0 automation-API work,
  2026-09-21).

So `release.yml` has been fully SHA-pinned since **before the issue was even
filed** (2026-09-18 vs. filed 2026-09-22) — the issue's own conditional ("if it
floats too") already anticipated this and correctly resolves to no action for that
file. Only `ci.yml`'s `build` job still floats, unchanged since 26c83d5/dbe4cc2.

**Dependabot/Renovate**: no `.github/dependabot.yml` and no renovate config
anywhere in the tree — nothing would auto-bump these SHAs once pinned; the trailing
`# vX.Y.Z` comments are the only manual-refresh aid.

**Protected environment / tag rulesets vs. the risk named**: `release.yml` only
triggers on a pushed tag matching `servicetag-v*`, runs under the `release`
GitHub Environment (one required reviewer, `GonzRon`, plus a branch policy), and
that job alone touches the signing secrets. A "release tags" repository ruleset
(target: tag, enforcement: active) additionally restricts who can create/move
those tags. Together these two controls bound the blast radius of a compromised
*release* action to "an attacker who can push a `servicetag-v*` tag and then get a
human approval" — but `release.yml` is already fully pinned regardless, so this is
belt-and-suspenders, not a substitute for the pinning already done there. `ci.yml`'s
floating `build` job carries none of that protection (it runs unconditionally on
every push/PR, no environment, no secrets) — so it is genuinely the only place
where a moved floating tag could swap in unreviewed action code, though the job
itself has no privileged credentials to steal (test/debug build only, no signing
secrets), which somewhat bounds the practical impact even there.

## Remaining work

Exactly five lines, one file, one job:

- `.github/workflows/ci.yml:9` — `actions/checkout@v5` → pin to the SHA already used
  at lines 38/52/66 (`fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09 # v5.1.0`) if that SHA
  is in fact what `@v5` currently resolves to; otherwise the SHA for whatever `v5.x`
  is current.
- `.github/workflows/ci.yml:13` — `actions/setup-java@v5` → pin to a SHA (candidate:
  the same `v5.7.0` SHA release.yml uses, `b6effb05e454b25005698d916606bdc6ffcbf961`,
  if `@v5` currently resolves to `v5.7.0`).
- `.github/workflows/ci.yml:17` — `android-actions/setup-android@v4` → pin to a SHA
  (candidate: release.yml's `v4.0.4` SHA,
  `be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd`, if `@v4` currently resolves there).
- `.github/workflows/ci.yml:21` — `gradle/actions/setup-gradle@v6` → pin to a SHA
  (candidate: release.yml's `v6.3.0` SHA,
  `9c971963bec38e04b3d30dcc455b5382be2fdbfb`, if `@v6` currently resolves there).
- `.github/workflows/ci.yml:26` — `actions/upload-artifact@v7` → pin to a SHA; no
  existing pin of this action exists anywhere in the repo to reuse, so this one
  needs a fresh lookup of the current `v7.x` release's commit SHA.

Each candidate SHA above needs verifying against what the floating tag currently
resolves to before reuse — it is not safe to assume `@v5` == the same minor/patch
release.yml happens to pin, only that it is very likely given both files were
touched together historically. `release.yml` needs **no changes** — already fully
pinned since 2026-09-18, predating the issue. The `mcp`/`bundle`/`schedules` jobs
need no changes — already pinned since introduction.

## Size + files

**Size: small.** One file (`.github/workflows/ci.yml`), one job (`build`), five
`uses:` lines to edit plus one green CI run on the exact commit to verify (as the
issue itself specifies). No design decisions, no new tooling — mechanical,
same pattern already proven three times over in this same file.

**Files touched:** `.github/workflows/ci.yml` only.

## Recommendation

**rewrite-narrow.** The core ask (pin the `build` job's five floating actions) is
100% valid, unstarted, and correctly scoped — do it. The reframing needed before
closing is cosmetic but worth doing so the ticket doesn't mislead future readers:
drop the "unit job" name (there is no job called `unit`; it's `build`) and drop or
resolve the "and give release.yml the same treatment ... if it floats too" clause,
since `release.yml` has been fully SHA-pinned since 2026-09-18 — three commits
before this issue was even filed. Narrow the title/body to "pin `ci.yml`'s `build`
job's five actions" and the remaining work is exactly the five-line diff above.
