# SDD ledger — plan: docs/plans/2026-08-21-exam-page-erase.md

## Setup rulings

- Ruling: use the newly initialized target repository itself as the isolated workspace — the requested skill directory did not exist, the old skill is a separate repository and remains untouched — cost if wrong: no disposable worktree, but every change is still isolated and committed in the new repository.
- Ruling: all implementers and reviewers use `gpt-5.5` with medium reasoning — this is the user's latest explicit coding-model requirement.
- Ruling: do not run skill-creator baseline VLM calls — the user capped API evaluation at the 74 bad-image exams and requested qwen3.8-max for all API calls — cost if wrong: no baseline agent benchmark, offset by TDD plus the requested real-data gate.
- Ruling: this plan remains in the independent skill repository; if a later task writes any code in `homeworkservice`, first verify its current branch is exactly `dev_xb_to_sgc_lxb55555` and stop without switching or writing when it is not — user corrected the initially dictated branch name during Task 1.

## Task status

- Task 1: complete — commits `1fa221b`, `28daa2c`, `226b80a`; 7 tests passed; independent review APPROVED after two fix rounds.
- Task 2: complete — commits `6a939cf`, `06cbfd4`, `0a8d965`; 24 tests passed with explicit JDK 8 `JAVA_HOME`; independent review APPROVED after two fix rounds.
- Task 3: complete — commit created; 33 tests passed with explicit JDK 8 `JAVA_HOME`.

## Pre-flight interface scan

| Task(s) | Shared interface or file | Finding / ruling |
|---|---|---|
| 1 | `ExamInput`, `PageInput`, prompt schemas | Task 2/4/5 consume Task 1 models; names and normalized-coordinate contract are consistent. |
| 1, 4 | four prompt roles and `VlmClient` | Task 1 owns prompt/schema text; Task 4 owns transport and parsing. No file overlap. |
| 2, 3 | `ValidationResult`, pixel region, ROI transforms | Task 3 consumes validated pixel bounds; Task 2 must expose immutable validated bounds rather than recomputing coordinates. |
| 2, 4 | `RiskGate`, ROI mapping | Task 4 invokes Task 2 decisions; local verification never bypasses Task 2 revalidation after coordinate mapping. |
| 3, 4 | `EraseOutcome`, deterministic gates | Task 4 may publish only outcomes that pass both pixel and VLM audit. |
| 4, 5 | `ExamOutcome`, page statuses | Task 5 writes artifacts without reinterpreting status; manual/error pages use original bytes for formal PNG. |
| 5 | copied Word component | Copy behavior, not runtime path; package renamed to new namespace. |
| 6 | gate dataset selection | Task 5 selector produces exactly the input Task 6 runs; selection searches exam ID across subjects. |
| 1 | task self-check | Tests precede `ExamScanner` and `PageBatcher`; prompt prose does not need source-text tests. |
| 2 | task self-check | Each rotation/risk/ROI behavior has a literal-fixture test before production code. |
| 3 | task self-check | Tests assert observable changed pixels and rejection behavior, not private thresholds. |
| 4 | task self-check | External VLM is replaced only at the transport boundary; orchestration and parsing remain real. |
| 5 | task self-check | Word/watermark/output tests use generated images and inspect produced artifacts. |
| 6 | task self-check | Runtime gate begins only after all offline tests and preflight pass. |
