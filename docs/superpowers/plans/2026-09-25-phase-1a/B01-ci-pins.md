# B01 — pin the five floating actions in `ci.yml`'s `build` job (#54)

**Read first:** plan.md §2, §3 and §8 (#54); `audit/audit-54.md`; issue #54's rewritten body.
**Lane:** first in Phase 1A, alone, branched from master `6f4438e`.

## Goal

`ci.yml`'s `build` job is the only place in the repository that still runs third-party action code by a floating major tag. Pin its five `uses:` lines to the full 40-character commit SHA of the **current release in the major line the file already uses**, each with a trailing `# vX.Y.Z` comment, exactly as the `mcp`, `bundle` and `schedules` jobs and `release.yml` already do. The job's behaviour, steps, inputs and runner stay as they are.

## Files

**Modify:** `.github/workflows/ci.yml` — lines 9, 13, 17, 21 and 26 at the base, and nothing else in the file.

**Untouched:** `.github/workflows/release.yml` (fully pinned since `26c83d5`); the `mcp`, `bundle` and `schedules` jobs; every `with:`, `run:`, `name:`, `if:` and `runs-on:` line; everything outside `.github/`. No Dependabot or Renovate configuration is added.

## The five lines

| line | today | major line to stay in | a candidate to *verify*, never to copy |
|---|---|---|---|
| 9 | `actions/checkout@v5` | v5 | `release.yml` pins `fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09 # v5.1.0` |
| 13 | `actions/setup-java@v5` | v5 | `release.yml` pins `b6effb05e454b25005698d916606bdc6ffcbf961 # v5.7.0` |
| 17 | `android-actions/setup-android@v4` | v4 | `release.yml` pins `be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd # v4.0.4` |
| 21 | `gradle/actions/setup-gradle@v6` | v6 (repository `gradle/actions`) | `release.yml` pins `9c971963bec38e04b3d30dcc455b5382be2fdbfb # v6.3.0` |
| 26 | `actions/upload-artifact@v7` | v7 | no existing pin anywhere; a fresh lookup |

The pinned form is `- uses: <owner>/<repo>[/<path>]@<40 hex> # vX.Y.Z`, with one space before the `#` like the pinned lines already in the file.

## How each SHA is chosen and verified

Do this for each of the five, and record every command's output (the tag, the object type and the SHA) in the report.

1. **Find the intended release.** This is the newest published, non-prerelease `vX.Y.Z` release inside the current major line. Look it up on the action's GitHub release page, or with `gh release list --repo <owner>/<repo> --exclude-pre-releases --limit 20` and then `gh release view vX.Y.Z --repo <owner>/<repo>`. **Stay in the current major.** A major bump changes inputs and runtime, so it is a separate decision and out of scope.
2. **Resolve that exact tag to its commit:** `gh api repos/<owner>/<repo>/git/ref/tags/vX.Y.Z`.
   - If `object.type` is `commit`, `object.sha` is the pin.
   - If `object.type` is `tag`, it is an annotated tag. Dereference it with `gh api repos/<owner>/<repo>/git/tags/<object.sha>` and pin **that** response's `object.sha`, which is the commit.
   - A tag-object SHA is not a commit, and it is the classic wrong pin.
   - A second, independent read must agree: `git ls-remote https://github.com/<owner>/<repo> 'refs/tags/vX.Y.Z' 'refs/tags/vX.Y.Z^{}'`. The peeled `^{}` line is the commit when the tag is annotated.
3. **Cross-check the major tag, but never pin from it.** The owner's rule is never to infer a SHA from the major tag. Resolve `refs/tags/v<major>` the same way and compare. If it names a different commit from the chosen release, do not follow it. Pin the release's commit, and report the difference.
4. **Compare with `release.yml`.** Where the verified release differs from `release.yml`'s pin of the same action, report the pair. Do not edit `release.yml`: aligning the two is a follow-up for the controller.

Every lookup is read-only. `gh` has two accounts on this workstation; keep `GonzRon` active. Never paste a token into a command or a report.

## Invariants

- **I1.** Every `uses:` line in every workflow file is `@<40 lowercase hex> # vX.Y.Z`.
- **I2.** Each SHA is the commit that the named `vX.Y.Z` tag resolves to, verified both ways (steps 2 and 3).
- **I3.** Each pin stays inside the major line it replaces.
- **I4.** The diff is exactly five changed lines, all in `ci.yml`.

## Test matrix

This brief has no JVM or device layer. Its tests are the anchored greps below and the CI run.

| hazard | proof | RED mutation |
|---|---|---|
| a floating line left behind, or a pin without its comment | grep G1 → no output | leave `upload-artifact@v7`: G1 prints it |
| a tag-object SHA pinned in place of the commit | step 2's dereference plus the `ls-remote` peeled line, both in the report; CI resolves the ref | pin an annotated tag's own SHA: the two reads disagree |
| a pin that is not the named release | step 2's output beside the comment in the report | swap two actions' SHAs: `gh api repos/<owner>/<repo>/commits/<sha>` finds no such commit in that repository |
| a change outside the five lines | G2 | touch `release.yml`: G2 shows it |
| a pin that does not run | one green CI run on the pinned commit | a typo in any SHA: `build` fails at "Set up job" |

## Gate

- **G1:** `grep -nE '^\s*-?\s*uses:' .github/workflows/*.yml | grep -vE '@[0-9a-f]{40} # v[0-9]+\.[0-9]+\.[0-9]+$'` prints nothing.
- **G2:** `git diff --stat <base> -- .` shows one file, `.github/workflows/ci.yml`, with five insertions and five deletions.
- **G3:** `git diff <base> -- .github/workflows/ci.yml` changes only lines whose text starts with `- uses:`.
- **Acceptance:** one green run of the `ci` workflow (all four jobs) **on the exact commit that carries the pins**. The implementer does not push. The controller pushes the lane branch, or master after the merge, and records the run id and the commit SHA in the ledger and on #54. A red run is a fix round, not a re-plan.

## Strings

None. No user-visible text, and no contract text.

## Must NOT

- change `release.yml`, or any job other than `build`;
- move any action to a new major version, or to a prerelease;
- take a SHA from the floating major tag, from a blog post or from another repository's workflow;
- add Dependabot, Renovate or any other automation;
- reorder steps, or change `with:` inputs, the runner or the Gradle command;
- push, tag, or open a pull request (the controller does these).

## Review focus

- For each line: does the comment name the release the SHA really resolves to, and was the annotated-tag case dereferenced?
- The diff is five lines in one file.

## Size

Tiny: one file, five lines. A reviewer can check every SHA by hand in minutes.
