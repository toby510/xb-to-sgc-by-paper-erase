# VLM Contract v1：试卷页码擦除

本协议是 VLM 提示词、响应解析和业务流水线之间唯一的稳定边界。提示词只能描述图像语义和本协议；调用方只消费本协议字段，不能依赖提示词中的实现细节。

## 通用约定

- 响应必须是一个 JSON 对象；禁止 Markdown、前后说明和未定义字段。
- 坐标均属于**当前输入图**的归一化坐标系：左上角为 `(0,0)`，右下角为 `(1,1)`；矩形以 `(x1,y1)` 左上角、`(x2,y2)` 右下角表示，必须满足 `0 <= x1 < x2 <= 1`、`0 <= y1 < y2 <= 1`。
- ROI 请求中的坐标只相对于该 ROI，不得当作整页坐标；页码、方向和正文边界只依据当前可见图像。
- 所有 `page_id`、`region_id` 必须逐字回显请求值；`null` 必须显式输出，不能用省略代替。
- 页码擦除候选不等于最终可写授权；无法区分非正文页码与正文时必须返回人工审核。

## Locate 响应

根对象固定为：`page_id`、`reading_rotation`、`direction_confidence`、`status`、`regions`、`evidence`。

- `reading_rotation`：将当前输入顺时针旋转后可正常阅读正文的角度，仅可为 `0|90|180|270`。
- `direction_confidence`：方向判断置信度，`0..1`。
- `status`：`safe_to_erase` 表示存在可测量的候选页码行；`no_pagenum` 表示当前页没有独立非正文页码行；`manual_review` 表示无法安全判断。
- `safe_to_erase` 的 `regions` 非空；其他状态的 `regions` 必须为空。

每个 `regions[]` 对象固定为：`region_id`、`x1`、`y1`、`x2`、`y2`、`page_number_text`、`same_line_metadata`、`on_line`、`confidence`、`safety_margin`、`nearest_body_boundary`。

- `page_number_text`：当前图中真实可见的**页码字面量**，是主锚点；不能填同一行的试卷名或其他元数据。
- `same_line_metadata`：与页码同基线、明确非正文的辅助文本；不是识别页码的主锚点。
- `on_line`：该候选是否与正文、答题线或表格处于同一视觉行带，布尔值。
- `confidence`：候选语义和框坐标同时正确的置信度，`0..1`。
- `nearest_body_boundary`：只属于本 `region` 的最近清晰正文边界，固定对象 `{"x":number|null,"y":number|null,"basis":string}`。顶部/底部目标使用 `y`、左右目标使用 `x`；未观察到可靠正文时两个值均为 `null`，但字段和 `basis` 不得省略。

## Verify 响应

Verify 只接收一个 ROI，根对象固定为：`page_id`、`region_id`、`decision`、`allowed_scope`、`evidence`、`refined_region`。

- `decision`：`safe_to_erase|no_pagenum|manual_review`。
- `refined_region`：`safe_to_erase` 时必须为当前 ROI 的非空矩形对象 `{"x1":number,"y1":number,"x2":number,"y2":number}`；其他决策必须为 `null`。
- `allowed_scope` 只描述当前页码行及明确同行非正文元数据；不能扩大为页面其他文字。

## Audit 响应

根对象固定为：`page_id`、`decision`、`original_target_is_non_body`、`body_unchanged`、`target_removed`、`background_acceptable`、`evidence`。

- `original_target_is_non_body`、`body_unchanged`、`target_removed` 是独立布尔判断。
- `decision=pass` 当且仅当上述三个字段均为 `true`；`background_acceptable` 仅表示视觉质量告警。
- 审计必须比较整页正文，ROI 内原本存在的正文也属于正文保护范围。

## 版本演进

任何新增、删除、重命名字段，或改变字段类型、可空性、枚举含义、坐标系、业务语义，均必须新建 Contract 版本，并同步新增提示词版本与解析器测试；不得在同一 Contract 版本中隐式兼容。
