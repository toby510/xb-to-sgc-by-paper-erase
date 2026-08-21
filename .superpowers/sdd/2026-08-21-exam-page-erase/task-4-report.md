# Task 4 Report: VLM Client and Exam Pipeline

## Scope

- Added `VlmConfig` for `config/vlm-providers.json` with four fail-closed roles: `pattern`, `locate`, `verify`, and `audit`.
- Added strict `ResponseParser` for pattern/locate/verify/audit JSON, including unknown field rejection, finite coordinate checks, legal enum checks, duplicate/missing page and region ID checks, and safe raw summaries.
- Added `VlmClient` with a real OpenAI-compatible HTTP implementation and a fake-friendly interface for tests. Requests include stable `PAGE_ID` labels, preview images, and ROI images.
- Added `ExamPipeline` and `ExamOutcome` for 8-page overlapping pattern batches, Java orientation normalization, per-page locate, risk-triggered verify, edge ROI verify, ink-mask erase, mandatory audit for modified pages, page-level fallback, and whole-exam fallback on pattern protocol failure.
- Added v2 prompts for all four VLM roles and updated config to point to v2, without overwriting v1 prompts.

No old `xb-to-sgc-by-erase` skill or `homeworkservice` files were modified.

## RED Evidence

Initial Task 4 RED:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=VlmConfigTest,ResponseParserTest,ExamPipelineTest test
```

Key output:

```text
COMPILATION ERROR
cannot find symbol class VlmClient
cannot find symbol variable ResponseParser
cannot find symbol class ExamOutcome
cannot find symbol class ExamPipeline
cannot find symbol class VlmConfig
```

Follow-up on-line RED:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=ExamPipelineTest test
```

Key output:

```text
expected:<[p2:r1, p9:r1]> but was:<[p2:r1]>
```

This verified that `on_line=true` pages were incorrectly allowed to skip local verify before the fix.

## GREEN Evidence

Target Task 4 tests:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=VlmConfigTest,ResponseParserTest,ExamPipelineTest test
```

Key output:

```text
Running com.xb.sgc.papererase.pipeline.ExamPipelineTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
Running com.xb.sgc.papererase.vlm.ResponseParserTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
Running com.xb.sgc.papererase.vlm.VlmConfigTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
Results :
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
```

Final full Java gate:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q test
```

Key output:

```text
Results :
Tests run: 51, Failures: 0, Errors: 0, Skipped: 0
```

Whitespace check:

```bash
git diff --check
```

Output: no issues.

## Covered Behaviors

- Pattern batches for 18 pages are `1-8`, `8-15`, and `15-18`.
- Pattern protocol page mapping failure falls back the whole exam.
- Locate/model failure falls back only the affected page.
- Low direction confidence falls back the affected page.
- Rotation is handled by Java before locate/verify/audit.
- Stable pages take the fast path only when all risk gates pass.
- Low confidence, rotated, and `on_line=true` pages go to local verify.
- No-candidate pages with strong consensus trigger edge ROI verify.
- Verify denial, erase failure, and audit failure all fall back per page.
- Every page that reaches actual erase is audited.

## Concerns

- The HTTP client is implemented but not integration-tested against the real qwen endpoint in Task 4; all Task 4 tests use fake VLM responses to avoid API calls.
- Task 4 does not implement Task 5 output files, CLI, run writer, Word generation, or manual-review preview images.
- Line restoration remains conservative and is not wired as a separate restoration path; `on_line=true` now forces verify, and unsafe cases fall back rather than attempting speculative restoration.
