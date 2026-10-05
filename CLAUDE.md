# Rules for every session

Every clone, session and agent working here follows these. They override any tool's default
commit or pull request attribution. Product design lives in `docs/`, not here.

## Commits

- Before the first commit in a clone or a session, set the owner's identity:
  `git config user.name GonzRon` and
  `git config user.email "$(git log -1 --format=%ae servicetag-v1.7.0)"` — the address of the
  owner's own release commit (`git fetch origin tag servicetag-v1.7.0` first if the clone lacks it).
  Never write the address itself into a file.
- It must be the author **and** the committer. `GIT_AUTHOR_*` and `GIT_COMMITTER_*` in the
  environment beat the config: unset them. Check with `git var GIT_COMMITTER_IDENT`.
- A commit message is its subject line alone: no body, no `Co-Authored-By`, no `Claude-Session`,
  no `Generated-by` or "Generated with", no session links.
- No tool or model name anywhere in a commit message, a branch name, a pull request title or a pull
  request body. Every commit reads as the owner's alone.
- Subjects are casual, terse and lowercase-leaning, as in
  `git log servicetag-v1.6.0..servicetag-v1.7.0`: `supply row can hide its chevron`,
  `#16 plan: rev 1`, `delete asset sweeps its installed components' file bytes too`.

## Pull requests

- Name the branch for the work, `fix-…` or `issue-123-…`. Never start it with a tool's name.
- Merge with a merge commit whose subject carries no tool-named branch. Never squash or rebase
  away the owner's history.
- The `hygiene` workflow runs `tools/check-commit-hygiene.sh` over the pull request's own commits and
  master requires it green (ruleset "master: hygiene required"; repository admins can bypass). Run
  it before pushing: `bash tools/check-commit-hygiene.sh origin/master HEAD`.

## The gates CI cannot run

- The connected suite runs only on `emulator-5554`, by the controller, before a release cut
  (`docs/release-proofs.md` R2). A pull request that touches `app/src/androidTest` or a screen
  says so in its description.
- CI compiles the instrumented sources and runs lint. Both stay green.

## Never in the repository

E-mail addresses, home paths, device serials other than `emulator-5554`, real endpoints, host
names or tokens. Use fictional ones only: `192.168.0.10`, `ha.example`, `example-…`, and names
under the reserved `.invalid` domain such as `manuals.example.invalid`.

## Where the rest lives

- `docs/versioning.md`: classify the change before choosing the number.
- `docs/release-proofs.md`: the release runbook.
- `docs/superpowers/planning-policy.md`: plans specify, implementers author.
- `docs/localization.md`: every owner-facing string is a resource; `UiLiteralGuardTest` holds it.
- Issue #104: the roadmap of record.
