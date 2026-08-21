# 试卷维度页码安全擦除 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建独立 `xb-to-sgc-by-paper-erase` skill，以试卷级共性、风险触发局部复核、Java 墨迹掩码和强制回退安全擦除页码，并生成逐页证据与两份 Word。

**Architecture:** Java 8 独立工程扫描并聚合同卷页面，通过四角色 qwen VLM 完成共性、定位、风险复核和擦后审核；所有修改由确定性掩码、像素差分和色差门禁约束。旧 skill 的 Word 写入逻辑复制到新工程，不建立运行时依赖。

**Tech Stack:** Java 8、Maven、Jackson、ImageIO、Apache POI/JUnit 4、DashScope/OpenAI-compatible vision API。

**Spec:** `docs/specs/2026-08-21-exam-page-erase-design.md`

## Global Constraints

- 只创建 `/Users/longxuebin/.claude/skills/xb-to-sgc-by-paper-erase`，不修改旧 skill。
- 所有生产 Java 代码遵守测试先行；每个测试必须先因缺失行为正确失败，再写最小实现。
- 默认视觉模型为 `qwen3.8-max`，角色 prompt 独立且模型可配置。
- 正文零损伤优先；任何不确定、协议异常或门禁失败均回退原图。
- 禁止裁白边、隐式扩框和框外残点扫描。
- 90°/270°正常化允许宽高互换，禁止缩放、裁切和补白。
- WordMerge 代码允许从旧 skill 复制，但新 skill 运行时不得引用旧目录。
- 第一阶段只跑 74 张 bad 图涉及的完整试卷，完成后停下供用户查看。

---

### Task 1: Skill 骨架、协议、提示词和输入聚合

**Files:**
- Create: `SKILL.md`
- Create: `config/vlm-providers.json`
- Create: `config/word-template.json`
- Create: `references/pattern-prompt-v1.md`
- Create: `references/locate-prompt-v1.md`
- Create: `references/verify-prompt-v1.md`
- Create: `references/audit-prompt-v1.md`
- Create: `java/pom.xml`
- Create: `java/src/main/java/com/xb/sgc/papererase/model/ExamModels.java`
- Create: `java/src/main/java/com/xb/sgc/papererase/input/ExamScanner.java`
- Create: `java/src/main/java/com/xb/sgc/papererase/input/PageBatcher.java`
- Test: `java/src/test/java/com/xb/sgc/papererase/input/ExamScannerTest.java`
- Test: `java/src/test/java/com/xb/sgc/papererase/input/PageBatcherTest.java`

**Interfaces:**
- Produces: `ExamScanner.scan(Path root): List<ExamInput>`，按目录 ID 聚合并按页序排序。
- Produces: `PageBatcher.overlapping(List<PageInput>, int max, int overlap): List<List<PageInput>>`。
- Produces: 四个严格 JSON 协议模型和四份独立 prompt。

- [ ] **Step 1: 写失败测试**：用临时目录构造 `15065_2305721916932448794_2.png` 等文件，断言目录 ID、学校 ID、页序、重复拒绝、缺号标记和输出排序；构造 18 页列表，断言批次为 `1-8、8-15、15-18`。
- [ ] **Step 2: 运行 `mvn -q -Dtest=ExamScannerTest,PageBatcherTest test`**，确认因类或方法缺失失败，而非测试装配错误。
- [ ] **Step 3: 实现最小协议模型、扫描器和分批器**，使测试通过；文件中间 ID 不一致只记录 anomaly，重复/不可解析页序抛出整卷错误。
- [ ] **Step 4: 写四份提示词**：定位提示词基于旧 v6 的通用语义，但修正最小框高自相矛盾、旋转安全轴、双页多安全区和“同行元数据/审核”冲突；共性 prompt 禁止输出最终坐标。
- [ ] **Step 5: 写 `SKILL.md`**：触发条件、preflight、TEST 流程、失败回退、输出目录和本轮门禁命令必须明确，正文不超过 500 行。
- [ ] **Step 6: 运行目标测试及 `mvn -q test`**，记录通过证据。

### Task 2: 方向标准化、风险门禁与 ROI 映射

**Files:**
- Create: `java/src/main/java/com/xb/sgc/papererase/image/OrientationNormalizer.java`
- Create: `java/src/main/java/com/xb/sgc/papererase/safety/RegionValidator.java`
- Create: `java/src/main/java/com/xb/sgc/papererase/safety/RiskGate.java`
- Create: `java/src/main/java/com/xb/sgc/papererase/image/RoiTransform.java`
- Test: `java/src/test/java/com/xb/sgc/papererase/image/OrientationNormalizerTest.java`
- Test: `java/src/test/java/com/xb/sgc/papererase/safety/RegionValidatorTest.java`
- Test: `java/src/test/java/com/xb/sgc/papererase/safety/RiskGateTest.java`
- Test: `java/src/test/java/com/xb/sgc/papererase/image/RoiTransformTest.java`

**Interfaces:**
- Produces: `OrientationNormalizer.normalize(BufferedImage, int): NormalizedImage`。
- Produces: `RegionValidator.validate(PageLocateResult, BufferedImage): ValidationResult`。
- Produces: `RiskGate.requiresLocalVerify(PageContext, ValidationResult): boolean`。
- Produces: ROI 原图坐标与局部 0-1 坐标的双向映射。

- [ ] **Step 1: 写旋转失败测试**：手工构造不同颜色角点图，断言 90/180/270 后角点位置、宽高和全部输入像素均被保留。
- [ ] **Step 2: 写安全失败测试**：断言 NaN、Infinity、越界、零面积、非边缘区域、掩码触边和正文空白不足均被拒绝。
- [ ] **Step 3: 写风险失败测试**：断言 `confidence<0.97`、mixed、模式冲突、旋转页、双页、缺号和正文边界冲突会触发二检；普通稳定页可跳过。
- [ ] **Step 4: 写 ROI 映射失败测试**：使用字面量坐标验证局部坐标映回全图，无舍入越界。
- [ ] **Step 5: 逐项运行测试确认 RED，再实现最小代码并运行到 GREEN**。

### Task 3: 安全墨迹擦除、直线恢复和确定性审核

**Files:**
- Create: `java/src/main/java/com/xb/sgc/papererase/erase/InkMaskEraser.java`
- Create: `java/src/main/java/com/xb/sgc/papererase/erase/BackgroundEstimator.java`
- Create: `java/src/main/java/com/xb/sgc/papererase/erase/LineRestorer.java`
- Create: `java/src/main/java/com/xb/sgc/papererase/safety/PixelDiffGate.java`
- Create: `java/src/main/java/com/xb/sgc/papererase/safety/ColorSeamGate.java`
- Test: `java/src/test/java/com/xb/sgc/papererase/erase/InkMaskEraserTest.java`
- Test: `java/src/test/java/com/xb/sgc/papererase/erase/LineRestorerTest.java`
- Test: `java/src/test/java/com/xb/sgc/papererase/safety/PixelDiffGateTest.java`
- Test: `java/src/test/java/com/xb/sgc/papererase/safety/ColorSeamGateTest.java`

**Interfaces:**
- Produces: `InkMaskEraser.erase(BufferedImage, PixelRegion): EraseOutcome`，失败时不修改输入。
- Produces: `PixelDiffGate.check(original, candidate, approvedMask)`，掩码外任一变化即失败。
- Produces: `ColorSeamGate.check(original, candidate, approvedMask)`，复杂背景或明显接缝失败。

- [ ] **Step 1: 写失败测试**：构造纸色背景、页码墨迹、邻近正文和框外深色点，断言只改变框内页码掩码，正文与框外深色点逐像素不变。
- [ ] **Step 2: 写复杂背景失败测试**：纹理、印章色和样本不足时必须返回人工复核，不得回退整块平均色。
- [ ] **Step 3: 写直线失败测试**：两侧连续同色同宽直线可以恢复；不一致或表格样式必须拒绝。
- [ ] **Step 4: 写差分和色差失败测试**：手工篡改掩码外像素时门禁失败；平稳修复通过，明显接缝失败。
- [ ] **Step 5: 逐项确认 RED，最小实现到 GREEN；禁止复制旧 PaperEraser 的扩框和残点逻辑**。

### Task 4: VLM 四角色客户端与试卷级编排

**Files:**
- Create: `java/src/main/java/com/xb/sgc/papererase/vlm/VlmConfig.java`
- Create: `java/src/main/java/com/xb/sgc/papererase/vlm/VlmClient.java`
- Create: `java/src/main/java/com/xb/sgc/papererase/vlm/ResponseParser.java`
- Create: `java/src/main/java/com/xb/sgc/papererase/pipeline/ExamPipeline.java`
- Test: `java/src/test/java/com/xb/sgc/papererase/vlm/ResponseParserTest.java`
- Test: `java/src/test/java/com/xb/sgc/papererase/pipeline/ExamPipelineTest.java`

**Interfaces:**
- Produces: `VlmClient.pattern/locate/verify/audit`，四角色默认 `qwen3.8-max` 并可独立覆盖。
- Produces: `ExamPipeline.process(ExamInput, RunContext): ExamOutcome`，逐页隔离、整卷协议错误回退。

- [ ] **Step 1: 写解析失败测试**：严格拒绝缺页、重复 page ID、非有限坐标、非法状态和 Markdown 包裹外的垃圾输出；保存安全长度的原始摘要。
- [ ] **Step 2: 写流水线失败测试**：使用本地 fake client 验证共性分批、Java 旋转、稳定页快路径、风险页二检、无候选但同卷有页码时的边缘 ROI、审核失败回退和单页隔离。
- [ ] **Step 3: 运行测试确认 RED**。
- [ ] **Step 4: 实现客户端和最小编排**：每张图片附稳定 page ID；网络失败按配置重试，最终失败不擦；所有实际修改页必须 audit。
- [ ] **Step 5: 运行目标测试与全量单元测试到 GREEN**。

### Task 5: 产物、人工水印、Word、报告和门禁选择器

**Files:**
- Copy then adapt: `java/src/main/java/com/xb/sgc/papererase/output/WordMergeComponent.java`
- Create: `java/src/main/java/com/xb/sgc/papererase/output/ManualReviewWatermarker.java`
- Create: `java/src/main/java/com/xb/sgc/papererase/output/RunWriter.java`
- Create: `java/src/main/java/com/xb/sgc/papererase/output/ReportWriter.java`
- Create: `java/src/main/java/com/xb/sgc/papererase/input/GateDatasetSelector.java`
- Create: `java/src/main/java/com/xb/sgc/papererase/Main.java`
- Create: `scripts/run.sh`
- Create: `evals/evals.json`
- Test: `java/src/test/java/com/xb/sgc/papererase/output/ManualReviewWatermarkerTest.java`
- Test: `java/src/test/java/com/xb/sgc/papererase/output/WordMergeComponentTest.java`
- Test: `java/src/test/java/com/xb/sgc/papererase/output/RunWriterTest.java`
- Test: `java/src/test/java/com/xb/sgc/papererase/input/GateDatasetSelectorTest.java`

**Interfaces:**
- Produces: `Main run <test-root>` 和 `Main gate <bad-root> <full-root>`。
- Produces: 设计规范中的完整输出目录、两份 Word、逐页/全卷 JSON、NDJSON 和报告。

- [ ] **Step 1: 写水印失败测试**：正式原图/擦后图字节不被水印修改，只有审核预览含红色变化。
- [ ] **Step 2: 写 Word 失败测试**：原图 Word 使用全部原图；擦后 Word 对安全页用擦后图、人工页用审核预览；页数和顺序相同。
- [ ] **Step 3: 写输出失败测试**：断言固定顶级目录、run 隔离、逐页证据、consensus、audit、report 和 run.json 齐全。
- [ ] **Step 4: 写门禁选择失败测试**：按 exam ID 跨学科映射完整卷，排除历史 output，覆盖 bad 学科误标情况。
- [ ] **Step 5: 确认 RED 后实现最小代码**；Word 类复制到新包并删除任何旧路径引用。
- [ ] **Step 6: 写 3 个 skill eval 用例**：正常多页、页码贴正文、旋转/双页，断言产物和回退协议；不额外调用 baseline VLM，避免重复 API 成本。
- [ ] **Step 7: 运行 `mvn -q test`、`bash scripts/run.sh echo` 和离线两页合成测试**。

### Task 6: 74 张 bad 图对应完整试卷门禁

**Files:**
- Runtime output only: `<完整集>/xb-to-sgc-by-paper-erase-output/exam-page-only/runs/...`

**Interfaces:**
- Consumes: bad 根目录与完整 100 份试卷根目录。
- Produces: 仅相关完整试卷的擦除结果、Word、审核账本和报告。

- [ ] **Step 1: preflight**：校验 qwen API key/endpoint、模型名 `qwen3.8-max`、Maven 测试通过、输入 74 张 bad 图和映射试卷数；任何失败阻塞 API 调用。
- [ ] **Step 2: 执行 gate 命令**，仅处理 bad 图涉及的完整试卷；记录实际图片数、调用失败和人工复核数。
- [ ] **Step 3: 校验产物完整性**：输入/输出页数、两份 Word、逐页 JSON、consensus、audit 和报告必须对齐。
- [ ] **Step 4: 抽查高风险结果**：正文贴近页码、旋转、双页、同行长元数据和 manual_review 水印。
- [ ] **Step 5: 停下并向用户交付 run 目录与报告，不继续跑 400 页全量门禁**。

## Self-review

- Spec coverage: 输入、分批、多模式、旋转、风险二检、墨迹掩码、色差、审核回退、人工水印、Word、门禁集和默认模型均有对应任务。
- Placeholder scan: 计划不含 TBD/TODO/“以后实现”；每项行为均有文件、接口和测试。
- Type consistency: `ExamInput/PageInput` 从 Task 1 流向 Task 2/4/5；`EraseOutcome` 从 Task 3 流向 Task 4；`ExamOutcome` 从 Task 4 流向 Task 5。
