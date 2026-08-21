# Task 3 Report: Safe Ink Erase and Deterministic Gates

## Scope

- Added `InkMaskEraser.erase(BufferedImage, PixelRegion)` with fail-closed outcomes.
- Added immutable `ApprovedMask`, independent candidate image creation, and source-preserving failure paths.
- Added `BackgroundEstimator` for stable local background median repair and rejection of insufficient samples, complex variance, colored non-target content, and gray rule lines.
- Added `LineRestorer.restoreHorizontal(...)` for conservative same-row line restoration only when both sides are continuous and consistent.
- Added `PixelDiffGate` and `ColorSeamGate` deterministic post-erase checks.
- Added Task 3 tests for observable pixels, masks, statuses/reasons, ARGB preservation, complex backgrounds, colored stamps, gray lines, boundary-touch masks, line rejection, pixel diffs, and color seams.

No old skill, old `PaperEraser`, or `homeworkservice` files were modified or referenced.

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

## Safety Notes

- `InkMaskEraser` does not modify `source`; success returns a separate candidate image, and all manual paths return original-image semantics.
- Only approved mask pixels inside the validated `PixelRegion` are modified.
- Mask touching the validated region boundary returns `MANUAL_REVIEW`.
- `PixelDiffGate` fails dimension/type mismatches, mask shape mismatches, any mask-outside ARGB change, and no-op approved masks.
- `ColorSeamGate` uses local boundary samples and rejects obvious seams or complex local variance.
- Line restoration is separate and conservative: inconsistent, crossing, or grid/table-like lines are rejected.

## Concerns

- Gradient repair is currently covered through seam acceptance for slight monotonic gradients; `InkMaskEraser` itself uses stable median fill only. This is intentionally conservative and may route more pages to `manual_review` until a tested plane-fit repair is added.
- `LineRestorer` is not wired into `InkMaskEraser`; Task 4 can choose whether to call it after local verification confirms a page number is on a safe standalone line.
