# xb-to-sgc-by-paper-erase 项目说明

## 项目目标

按**整份试卷**而不是单张图片处理页码：自动擦除可明确证明安全的页码，正文像素零误伤。页码擦除率目标为 99% 以上；正文保护是不可降级的硬门禁，背景色差仅记录告警。

本 skill 处理页码，以及经模型明确确认、与页码同处同一独立页眉/页脚行的非正文元数据；批准区域必须覆盖该完整目标行。不得裁切或擦除题干、选项、图表、公式、答题区、标题、密封线、表格、正文编号或不确定文字。

## 核心原理

### 1. 单页方向定位

`pattern` 已退出活跃链路。每页由 `locate` 在原图上同时判断阅读方向和页码语义；正向页复用首次结果，旋转页由 Java 旋正后再定位一次，未旋正坐标不得用于擦除。历史 pattern 模型、parser 和空 consensus 输出只为兼容旧 run 保留。

### 2. 粗窗口驱动的高清定位

Java 在原始分辨率标准化图上，按 locate 候选、最近正文边界和固定 margin 裁取局部 ROI。坐标精修只发送 3 倍放大的局部图；普通风险 verify 使用整页预览和原分辨率 ROI。

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

用户额外设定的硬限制：若本会话可用额度降至 **50%**、且 bad 测试集尚未达到可验收通过状态，必须立即停止继续模型验证与试错，先更新桌面交接文档
`/Users/longxuebin/Desktop/xb-to-sgc-by-paper-erase-方案与过程/当前断网交接状态.md`。交接必须包含当前分支、未提交改动、已验证产物、失败根因、精确复现命令与下一步最小修复；不得为了“再试一次”越过该限制。

如果额度不足、网络受限、重复阻塞或无法在本轮完成：立即更新桌面交接文档
`/Users/longxuebin/Desktop/xb-to-sgc-by-paper-erase-方案与过程/当前断网交接状态.md`，写清楚当前分支/提交、已验证结果、失败根因、关键路径、未完成项、下一步精确命令和产物位置。不得默默停止或在无证据情况下宣称通过。
