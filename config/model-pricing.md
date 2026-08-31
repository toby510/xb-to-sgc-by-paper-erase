# `model-pricing.json` 配置说明

该文件仅用于把模型接口返回的真实 Token 用量换算为费用；不会参与页码定位、正文保护、擦除、审核或重试等业务决策。

## 顶层字段

| 字段 | 含义 | 当前取值 |
| --- | --- | --- |
| `currency` | 输出费用的币种。当前只支持 `CNY`（人民币）。 | `CNY` |
| `unit` | 单价的计量单位。`per_million_tokens` 表示“每 100 万 Token 的价格”。 | `per_million_tokens` |
| `models` | 按**模型实际返回名称**配置单价的映射表。模型不存在或单价不完整时，只统计 Token，不计算费用。 | 对象 |

## 单模型字段

`models.<模型名>` 下的所有价格单位均为“元 / 100 万 Token”。例如模型接口最终返回 `qwen3.8-max`，就读取 `models.qwen3.8-max`。

| 字段 | 含义 | 使用规则 |
| --- | --- | --- |
| `input_cny_per_million_tokens` | 非缓存输入 Token 单价。 | 必填；模型未命中缓存的输入 Token 按此价计算。 |
| `cached_input_cny_per_million_tokens` | 缓存命中输入 Token 单价。 | 必填；接口未返回缓存 Token 时按 0 计。 |
| `output_cny_per_million_tokens` | 输出 Token 单价，包含模型返回的可计费输出。 | 必填。 |

## 配置示例

以下数字仅展示格式，**不是推荐价格**，请替换为公司实际结算单价：

```json
"qwen3.8-max": {
  "input_cny_per_million_tokens": 2.00,
  "cached_input_cny_per_million_tokens": 0.50,
  "output_cny_per_million_tokens": 8.00
}
```

费用计算为：

```text
非缓存输入 Token × input 单价
+ 缓存输入 Token × cached_input 单价
+ 输出 Token × output 单价
```

## 当前配置来源

当前已按用户提供的模型广场/火山方舟截图，填写以下常规按量单价：

| 模型 | 非缓存输入 | 缓存命中输入 | 输出 |
| --- | ---: | ---: | ---: |
| `qwen3.7-plus` | 2.0 | 0.4 | 8.0 |
| `qwen3.8-max` | 12.0 | 1.5 | 36.0 |
| `qwen3-vl-235b-a22b-instruct` | 2.0 | 无缓存能力 | 8.0 |
| `qwen3-vl-plus` | 1.0 | 0.2 | 10.0 |
| `doubao-seed-2-1-pro-260628` | 6.0 | 1.2 | 30.0 |

单位均为元 / 100 万 Token。截图中的“限时折扣”属于活动价，未固化进配置；如公司实际结算使用活动价，可直接调整对应三项数值。

## `null` 的含义

当前值为 `null` 表示“尚未配置该模型的公司实际单价”。此时报告仍展示真实 Token、调用次数和耗时，但费用列显示“未配置模型单价”，不会猜测或使用公网参考价格。

如果一个模型的三个单价任意一个为 `null`，该模型本次费用不计算，避免汇总费用被不完整配置误导。
