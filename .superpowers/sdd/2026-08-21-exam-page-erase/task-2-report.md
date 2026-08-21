# Task 2 Report: Orientation, Risk Gate, ROI

## Scope

- Added deterministic right-angle orientation normalization.
- Added region validation with immutable pixel bounds for Task 3.
- Added fail-closed risk gate for deciding whether local VLM verification is required.
- Added ROI transforms between full image pixels, full normalized coordinates, and local ROI normalized coordinates.
- Added `.gitignore` for `java/target/`.

No old skill or `homeworkservice` files were modified.

## RED / GREEN Evidence

All Maven commands were run with:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home
```

Initial environment check:

```bash
mvn -q -Dtest=OrientationNormalizerTest test
```

Output:

```text
No compiler is provided in this environment. Perhaps you are running on a JRE rather than a JDK?
```

Resolution: re-ran all Maven verification with explicit JDK 8 `JAVA_HOME`.

### OrientationNormalizer

RED:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=OrientationNormalizerTest test
```

Output:

```text
COMPILATION ERROR
package OrientationNormalizer does not exist
cannot find symbol: variable OrientationNormalizer
```

GREEN:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=OrientationNormalizerTest test
```

Output:

```text
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

### RegionValidator

RED:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=RegionValidatorTest test
```

Output:

```text
COMPILATION ERROR
package RegionValidator does not exist
cannot find symbol: variable RegionValidator
```

GREEN:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=RegionValidatorTest test
```

Output:

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

### RiskGate

RED:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=RiskGateTest test
```

Output:

```text
COMPILATION ERROR
package RiskGate does not exist
cannot find symbol: variable RiskGate
```

GREEN:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=RiskGateTest test
```

Output:

```text
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

### RoiTransform

RED:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=RoiTransformTest test
```

Output:

```text
COMPILATION ERROR
cannot find symbol: class RoiTransform
cannot find symbol: variable RoiTransform
```

GREEN:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=RoiTransformTest test
```

Output:

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

### Confidence finite follow-up

RED:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=RegionValidatorTest,RiskGateTest test
```

Output:

```text
Failed tests:
validateRejectsInvalidCoordinatesAndDuplicateRegionIds
requiresLocalVerifyWhenAnyRiskConditionFails
```

GREEN:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=RegionValidatorTest,RiskGateTest test
```

Output:

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

## Review Fix Pass

### RED

After adding tests for the requested review findings:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=RiskGateTest,RegionValidatorTest,RoiTransformTest,OrientationNormalizerTest test
```

Output:

```text
Tests run: 14, Failures: 7, Errors: 0, Skipped: 0

Failed tests:
publicEntrypointsRejectInvalidDimensionsCoordinatesAndMargins
fromEdgeAllowsNullBoundaryButRejectsNullEdgeAndNegativeMargin
normalizedImageDoesNotExposeMutableInternalImage
validateRejectsUnsafeStatusMissingPageIdAndEmptyOrNullRegions
validateRejectsInvalidCoordinatesAndDuplicateRegionIds
validateRejectsNonEdgeRegionInsufficientBodyGapAndInkTouchingBox
requiresLocalVerifyWhenAnyRiskConditionFails
```

### GREEN

After the minimal fixes:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=RiskGateTest,RegionValidatorTest,RoiTransformTest,OrientationNormalizerTest test
```

Output:

```text
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
```

### ROI overflow follow-up

RED:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=RoiTransformTest test
```

Output:

```text
Tests run: 5, Failures: 1, Errors: 0, Skipped: 0
Failed tests:
publicEntrypointsRejectInvalidDimensionsCoordinatesAndMargins
```

GREEN:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=RoiTransformTest test
```

Output:

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

## Final Verification

Task 2 target tests:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=OrientationNormalizerTest,RegionValidatorTest,RiskGateTest,RoiTransformTest test
```

Output:

```text
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
```

Full Maven test suite:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q test
```

Output:

```text
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
```

## Changed Files

- `.gitignore`
- `java/src/main/java/com/xb/sgc/papererase/image/OrientationNormalizer.java`
- `java/src/main/java/com/xb/sgc/papererase/image/RoiTransform.java`
- `java/src/main/java/com/xb/sgc/papererase/safety/RegionValidator.java`
- `java/src/main/java/com/xb/sgc/papererase/safety/RiskGate.java`
- `java/src/test/java/com/xb/sgc/papererase/image/OrientationNormalizerTest.java`
- `java/src/test/java/com/xb/sgc/papererase/image/RoiTransformTest.java`
- `java/src/test/java/com/xb/sgc/papererase/safety/RegionValidatorTest.java`
- `java/src/test/java/com/xb/sgc/papererase/safety/RiskGateTest.java`

## Self-review

- Orientation accepts only `0/90/180/270`; 90/270 swap dimensions and preserve the exact input pixel set.
- `NormalizedImage` records original dimensions, normalized dimensions, and applied rotation; source images and getter-returned images cannot mutate internal state.
- `RegionValidator` rejects non-`safe_to_erase` status, missing page ID, null/empty regions, duplicate canonical region IDs, non-finite coordinates/confidence, confidence outside `0..1`, out-of-range coordinates, non-positive area, non-edge regions, invalid required-axis body boundary, insufficient body gap, out-of-bounds mappings, and ink touching the candidate border.
- `ValidationResult` and validated `PixelRegion` lists are immutable; Task 3 can consume the returned pixel bounds without recomputing or expanding.
- `RiskGate` requires local verification unless every skip condition passes, including nonblank context page ID, nonblank pattern group ID, exact `stable` consensus, matching validation page IDs, confidence threshold, stable pattern, matching edge, Java blank gap, non-rotated/non-double-page/non-heterogeneous page, no body conflict, and no missing-page risk.
- `RoiTransform` uses floor for left/top and ceil for right/bottom for valid local rectangles; public entrypoints now reject invalid dimensions, invalid local/full normalized coordinates, invalid body boundaries, negative margins, zero/out-of-bounds ROI, and ROI int-overflow edge cases instead of clamping them into apparently valid values.

## Concerns

- The rotation convention is now explicit in tests and code: `readingRotation` means clockwise degrees needed to normalize the page. Task 4 prompts/parsers should preserve that convention.
- Region ink detection is intentionally minimal for Task 2: it only treats dark pixels touching the candidate border as unsafe. Task 3 must provide the real mask extraction and must not expand these validated bounds.
- ROI sizing is deterministic but conservative. Task 4 can build no-candidate ROIs through `RoiTransform.fromEdge`, but VLM orchestration and common-pattern conflict handling are still Task 4 scope.
