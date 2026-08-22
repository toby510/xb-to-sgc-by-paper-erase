# xb-to-sgc-by-paper-erase 项目说明

## 项目目标

按**整份试卷**而不是单张图片处理页码：自动擦除可明确证明安全的页码，正文像素零误伤。页码擦除率目标为 99% 以上；正文保护是不可降级的硬门禁，背景色差仅记录告警。

本 skill 只处理页码及经模型明确确认、与页码同处独立页眉/页脚带内的非正文元数据。当前默认只擦页码本体。不得裁切或擦除题干、选项、图表、公式、答题区、标题、密封线、表格、正文编号或不确定文字。

## 核心原理

### 1. 试卷级共性 + 逐页确认

同卷多数页页码通常有共同的阅读方向、边缘和对齐形式。`pattern` 先从代表页建立这种共性，但它**不拥有擦除权限**。每一页仍必须由 `locate` 明确判断有无页码；`no_pagenum` 表示保留原图，不得因同卷共性强行擦除。

`defaults.pattern_sample_max_pages` 控制 pattern 的代表页上限（默认 6）：

- `0` 或大于等于试卷页数：全页 pattern；
- 正数：按页序均匀抽取首、中、尾代表页；
- 只有代表页阅读方向一致、单一高置信度模式稳定时，才将该模式继承给未采样页；
- 代表页出现异构、无页码、方向冲突或低置信度时，回退为全页 pattern，禁止猜测。

### 2. 粗窗口驱动的高清定位

`pattern` 输出的是正常阅读方向下的宽松 `locate_window`，必须覆盖页码、空白带和最近正文边界。它只是取图范围，不可直接擦除。

Java 按阅读方向旋正原图，在原始分辨率标准化图上裁取该窗口，并将边缘条带朝正文方向扩展、沿页码基线方向覆盖全页。`locate` 只接收该 ROI，不接收整图，因此可在局部高清图上输出更精确的 ROI 相对坐标和正文边界。

Java 使用 `RoiTransform` 将 `locate` 返回的 ROI 坐标无损映射回标准化整页坐标。映射时统一补极小像素保护边以覆盖坐标量化与抗锯齿残边；随后所有安全门禁重新执行，保护边不构成放宽正文规则。

### 3. 安全门禁与擦除

模型坐标只是候选。`RegionValidator` 必须证明：

- 候选位于页面 20% 边缘带；
- 页码与正文之间存在至少 8px 无墨安全带；
- 候选框不含不明内容、长线、表格或疑似正文；
- 任何扩框后仍满足上述条件。

`InkMaskEraser` 只修改批准框内的页码墨迹，不允许矩形整块涂白。`PixelDiffGate` 必须证明掩码外像素完全未变。背景估计失败时，只能在已批准掩码内降级为白色。

每张实际擦除图都必须经过 `audit`：`body_unchanged=true` 且 `target_removed=true` 才能交付。背景色不佳可标记 `color_warning`，但不得回滚已证明安全的页码擦除。

### 4. 异常兜底

若整页/ROI locate 语义识别页码正确、但候选框内无目标墨迹，则调用高清边缘 ROI 的 `verify` 坐标精定位。它必须明确返回 ROI 相对精确框与正文边界；否则 `manual_review`。Java 不得在边缘带内自行猜测或盲目寻找文字替代模型语义判断。

任何网络错误、JSON 协议错误、页 ID 错配、坐标非法、正文安全带不足、像素差分异常或审计失败，都失败关闭：保留原图并标记 `manual_review`。

## 模型与提示词

- 默认提供方为 Qwen（`active=dashscope`），请求必须设置 `enable_thinking:false`，避免深度思考耗尽时间与输出预算。
- Ark 是可切换提供方，接入点和密钥从 `~/.zshrc` 的 `MST_XB_AI_ARK_MODEL_ENDPOINT`、`MST_XB_AI_ARK_API_KEY` 读取；Qwen 使用 `MST_QWEN_API_KEY`。
- 四角色提示词在 `references/`：整卷共性、单页定位、局部复核、结果审计。新增提示词版本必须新增文件，禁止覆盖历史版本。
- 图片 data URL 必须作为 OpenAI 兼容消息的 `image_url` 内容项发送，不能作为普通文本。

## 输出与可观测性

每次运行生成 `xb-to-sgc-by-paper-erase-output/exam-page-only/runs/<model>@<time>/`。必须包含：

- 原图、正式擦除图、人工审核预览、模型坐标预览；
- 每页 `regions.json`、整卷共识 JSON、`_progress.ndjson`、`_audit.ndjson`；
- 原图 Word 与擦除后 Word；
- 测试报告。

运行中优先查看 `_progress.ndjson`；任何 `manual_review` 都必须检查对应 regions 证据和坐标预览，区分模型识别、坐标、Java 门禁还是擦除审计问题，禁止无证据反复修改阈值。

## 开发与验证习惯

- 修改文件使用 `apply_patch`；保留用户已有改动，避免无关重构。
- 核心轻量验证优先：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home \
mvn -q -f java/pom.xml -Dtest=ResponseParserTest,PageBatcherTest,RegionValidatorTest,ExamPipelineTest test
```

- 先以固定失败代表页验证根因修复，再运行用户提供的 bad 集 gate；不要为提高通过数移除正文门禁。
- bad 根目录是首要验收集：`/Users/longxuebin/Desktop/校本融合/多学科测试数据集/所有学科100份试卷_bad图/`；只要肉眼可安全擦除的页码仍为 `manual_review`，就未验收通过。

## 额度与交接

模型调用与上下文额度有限。避免无意义全量测试、重复网络调用和超长单元测试；优先用核心测试与代表页证据定位问题。

如果额度不足、网络受限、重复阻塞或无法在本轮完成：立即更新桌面交接文档
`/Users/longxuebin/Desktop/xb-to-sgc-by-paper-erase-方案与过程/当前断网交接状态.md`，写清楚当前分支/提交、已验证结果、失败根因、关键路径、未完成项、下一步精确命令和产物位置。不得默默停止或在无证据情况下宣称通过。
