---
name: xb-to-sgc-by-paper-erase
description: Use when processing 校本融合试卷图片 by full exam, removing only page numbers and same-line non-body metadata while preserving all body content.
---

# 试卷维度页码安全擦除

## Scope

This skill processes a dataset shaped as:

```text
<测试根>/<学科>/<试卷ID>/<学校ID>_<试卷ID>_<图片顺序>.<png|jpg|jpeg|webp>
```

It only targets page numbers and same-line, clearly non-body metadata in an independent header/footer band. Body ink protection has priority over removal. If safety and removal conflict, keep the original page and mark `manual_review`.

Do not modify or runtime-reference `xb-to-sgc-by-erase`; copied code or config must be independently maintained here.

## Preflight

1. Confirm the working directory is this skill.
2. Run Java with a JDK, not the browser JRE. On this machine use:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q test
```

3. Load `config/vlm-providers.json` and the four role prompts from `references/`.
4. Scan inputs with `ExamScanner.scan(Path root)`.

Input rules:

- Directory name is authoritative `exam_id`.
- Filename middle exam ID mismatch is an anomaly; continue with directory ID.
- Unparseable or duplicate page order rejects the whole exam.
- Missing page order continues with `page_sequence_incomplete=true`.
- Use stable `page_id`; do not align model responses only by array index.

## TEST Flow

1. Build the gate set from bad-image exam IDs, then find complete exams globally in the full dataset by exam ID.
2. Aggregate each exam with `ExamScanner`.
3. Split pages for pattern analysis with `PageBatcher.overlapping(pages, 8, 1)`.
4. Pattern role analyzes reading direction and page-number layout only.
5. Java will rotate pixels in later tasks; VLM never rotates output coordinates for Java.
6. Locate role returns per-page candidate regions in the normalized working image.
7. Risky or conflicting pages must go to verify role before erase.
8. Every modified page must go to audit role after erase.
9. Stop after the first-stage gate output; do not run the full 100-exam production gate in this phase.

## Failure Fallback

- Model/network failure: do not erase; mark `manual_review`.
- Pattern JSON parse failure or page mapping disorder: whole exam falls back.
- Single-page verify/audit failure: only that page falls back.
- Pixel gate failure: discard erased output, copy original as formal erased PNG, and create a separate manual-review preview.

Formal `_原图.png` and `_擦除后.png` must not contain watermarks. Only `_人工审核预览.png` may contain the red manual-review mark.

## Output

```text
<测试根>/xb-to-sgc-by-paper-erase-output/
└── exam-page-only/
    └── runs/<模型>@<时间戳>/
        ├── erased/<学科>/<试卷ID>/
        ├── consensus/<学科>/<试卷ID>/exam_consensus.json
        ├── word_output/<学科>/<试卷ID>/
        ├── _audit.ndjson
        ├── 测试报告/测试报告.md
        └── run.json
```

## Gate Commands

Task-level gate:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q -Dtest=ExamScannerTest,PageBatcherTest test
```

Full Java gate:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home mvn -q test
```
