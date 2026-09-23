# Planning policy (owner ruling, 2026-09-21)

Adopted after the 1.1.0 plan grew to 8,277 lines, 84% of them source code written in Markdown before being written into the tree. That plan stands and is being executed as committed; from Phase 3 onward the rules below apply to every new plan in `docs/superpowers/plans/`.

## What a plan is

A plan specifies; it does not implement. It owns:

- architecture and the contracts between components (interfaces, signatures where they pin a boundary, wire shapes);
- invariants and forbidden behaviour, stated so a test can be written against each;
- the test matrix: every required case named, with the behaviour it must prove and the mechanism by which it fails without the change;
- edge cases, error mapping, limits;
- file ownership per task, dependency ordering, and what stays untouched;
- acceptance criteria and the proof commands for the release-level gate.

**Plans shall not contain complete production implementations or complete test files.** A code fragment is allowed when it pins an interface or an algorithm and stays under about 30 lines. A complete class: normally no. A complete test suite: no. Hundreds of lines meant to be transcribed: the plan has crossed into implementation.

The implementer authors the code: writes the tests from the matrix, sees them fail, writes the implementation, gets them green. The reviewer reviews the real diff against the specification. That separation is the independent engineering judgement the pipeline exists to buy; transcription is not independence.

## Shape for a multi-component feature

One **master plan** (about 20–40 pages) owning architecture, contracts, ordering, cross-cutting invariants and release acceptance, plus one **task brief** per component owning files, interfaces, required tests, edge cases and proof commands. Each is reviewed on its own.

## Size guardrails (warning signals, not laws)

| Lines | Reading |
|---|---|
| under ~2,000 | a normal detailed plan |
| 2,000–3,500 | consider splitting |
| over ~3,500 | almost certainly split |
| 8,000+ | the abstraction boundary is wrong |

## Two review jobs, kept distinct

- **Plan review** asks: are the semantics right, are all constraints identified, does the test matrix cover the hazards, are the component boundaries sane, is anything forbidden reachable.
- **Code review** asks: does the implementation satisfy the design, does it introduce new bugs, are the tests meaningful and non-vacuous, does anything on the untouched list move.

## Unchanged

Everything else in the process stands: owner ratification of every user-visible string before execution, the security and hygiene minimums, one implementer and one independent reviewer per task with scoped re-reviews of fixes, the whole-branch review, the release-level proofs, the operator's manual release gate. Grep expectations in proofs are written as anchored patterns from the start, so a comment that names a grep can never match it.

## Review budget (owner ruling, 2026-09-23)

The automated gates (lane gate, merge gate, CI, release gate) are unchanged and run as often as
needed; they are cheap, deterministic and objective. Independent model reviews exist to find what
the gates cannot, not to re-certify every correction, so from 2026-09-23:

- **one task review per brief**;
- if it finds substantive issues, the fixes are **batched** and the brief gets **at most one**
  scoped re-review;
- mechanical fixes — comments, already-ratified wording, renames, test tidies, paths, formatting,
  simple assertions — of roughly fifty lines or fewer close by **controller inspection plus the
  automated gates**, with no reviewer dispatch;
- **no re-review is required merely because a fix round occurred**;
- **one whole-branch release review** at the final integrated tip, with at most one scoped
  follow-up and only for a substantive release issue.

The spec and plan keep one independent review each and at most one consolidated re-review. For a
feature the size of #43 the expected total is ten to fifteen judgment passes, not twenty-plus.
