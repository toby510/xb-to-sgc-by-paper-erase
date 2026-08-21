# Task 3 Report: Safe Ink Erase and Deterministic Gates

## Scope

- Added `InkMaskEraser.erase(BufferedImage, PixelRegion)` with fail-closed outcomes.
- Added immutable scope-aware `ApprovedMask`, independent candidate image creation, defensive getters, and source-preserving failure paths.
- Added `BackgroundEstimator` with local RGB color plane fitting for stable and light-gradient backgrounds, plus rejection of insufficient samples, high residual texture, colored non-target content, and gray rule lines.
- Added conservative connected-component and geometry gates for second text lines, long line components, table/crossing lines, broad masks, abnormal ink coverage, and region-boundary contact.
- Added `LineRestorer.restoreHorizontal(...)` for conservative same-row line restoration only when both sides are continuous and consistent; it returns an independent candidate and line mask and never mutates input.
- Added scope-aware `PixelDiffGate` and `ColorSeamGate` deterministic post-erase checks.
- Added Task 3 tests for observable pixels, masks, statuses/reasons, ARGB/TYPE_CUSTOM preservation, defensive copies, invalid region/source mismatch, complex backgrounds, colored stamps, gray lines, second lines, same-line short metadata, long lines/tables/black blocks, broad masks, ragged masks, region-outside masks, interior artifacts, gradient repair, line rejection, pixel diffs, and color seams.

No legacy skill or external project files were modified or referenced.

## RED / GREEN Evidence

All Maven commands were run with:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home
```

### Initial Task 3 RED

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=InkMaskEraserTest,LineRestorerTest,PixelDiffGateTest,ColorSeamGateTest test
```

Expected failure:

```text
COMPILATION ERROR
package LineRestorer does not exist
cannot find symbol: variable ColorSeamGate
cannot find symbol: variable PixelDiffGate
package InkMaskEraser does not exist
```

### Fixture Correction During GREEN

Early GREEN runs failed because the new tests built candidate regions that violated Task 2's already-approved edge-band and mask-boundary validation. I corrected the fixtures to consume valid `PixelRegion` instances instead of weakening Task 2 safety.

### Gray Rule RED

After target tests were initially green, I added the explicit gray-rule negative case required by the brief. It failed correctly because the eraser still treated a 170-gray rule line as approved ink:

```text
Failed tests:
rejectsComplexBackgroundColoredStampInsufficientSamplesAndMaskTouchingRegionEdge
expected:<MANUAL_REVIEW> but was:<SAFE_TO_ERASE>
```

GREEN fix: `InkMaskEraser` now rejects colored non-target pixels and gray rule lines before mask approval.

### Target GREEN

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=InkMaskEraserTest,LineRestorerTest,PixelDiffGateTest,ColorSeamGateTest test
```

Output:

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
```

### Full GREEN

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q test
```

Output:

```text
Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
```

### Review Fix RED

The Task 3 review found additional P1/P2/P3 safety gaps. I added failing tests before fixing them.

Initial review RED:

```text
COMPILATION ERROR
ApprovedMask.from(...) not found
PixelDiffGate.check(... ApprovedMask) incompatible with boolean[][] entrypoint
LineRestoreResult missing getCandidate()/getLineMask()
```

Follow-up RED examples:

```text
approvedMaskDoesNotExposePublicRawBooleanArrayConstructor
expected:<MANUAL_REVIEW> but was:<SAFE_TO_ERASE>
```

The follow-up RED covered raw-mask public constructor exposure, long-line geometry, broad masks, TYPE_CUSTOM/defensive copy handling, background gradient/texture, and source/region mismatch.

### Review Fix GREEN

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=InkMaskEraserTest,PixelDiffGateTest,ColorSeamGateTest,LineRestorerTest test
```

Output:

```text
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
```

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q test
```

Output:

```text
Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
```

## Safety Notes

- `InkMaskEraser` does not modify `source`; success returns a separate candidate image, and all manual paths return original-image semantics.
- Only approved mask pixels inside the validated `PixelRegion` are modified.
- Mask touching the validated region boundary returns `MANUAL_REVIEW`.
- `ApprovedMask` has no public raw `boolean[][]` constructor; callers must use the validated `from(PixelRegion, width, height, mask)` factory.
- `PixelDiffGate` fails dimension/type mismatches, scope mismatches, any mask-outside ARGB change, and no-op approved masks.
- `ColorSeamGate` checks all approved pixels, not only mask boundary pixels, and rejects obvious seams, interior artifacts, empty masks, image mismatches, or complex local variance.
- Line restoration is separate and conservative: inconsistent, crossing, grid/table-like, invalid mask, or no-op restoration is rejected without leaking partial edits.
- Java does not claim semantic understanding. Semantic safety depends on Task 4 providing the final VLM-approved region, risk-triggered local verification, and post-erase VLM audit. Task 3 only enforces morphology, background, and deterministic pixel gates inside that approved region.

## Concerns

- `LineRestorer` is not wired into `InkMaskEraser`. Task 4 must only call it when `on_line=true` and local verify explicitly says the line is safe to restore.
- The Java geometry gates are intentionally conservative; borderline layouts should go to `manual_review` rather than being treated as successful erasures.
