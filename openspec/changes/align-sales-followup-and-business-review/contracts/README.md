# 佣金事实契约草案

[Schema](commission-facts.schema.json)补充领域数据定义，继续使用[共同 A2A 信封](../../align-listing-marketing-workflows/contracts/a2a-workflow.schema.json)。当前没有可执行新接口或最终动作登记，不能把结构文件存在解释为财务能力已开放。

| 定义 | 用途与边界 |
| --- | --- |
| `EntryRegistrationInput` | 登记写入的输入片段，只接收业务字段。笔号、版本、登记人及时间由服务端生成；不得用根 `oneOf` 校验写入 |
| `CommissionTerms` | 服务端费用与归属快照，包含规则版本、成交金额、买卖侧费率、负责经纪人及门店 |
| `PendingEntry` | 已受理但未确认的服务端事实输出，不能包含确认人或作为生效金额 |
| `AdminConfirmedEntry` | 管理员确认后输出，归属人和确认人分开；载荷自称已确认不能代替当前授权与真实决定 |
| `ExternalFinanceConfirmationCandidate` | 预留财务来源适配，不在根可接受分支中；当前事实入口拒绝此载荷，未来启用需独立来源验证 |

共享引用为 `urn:bkanent:agent:supervised-workflow:1.0` 和 `urn:bkanent:a2a:customer-matching:1.0`。运行登记要解析并绑定输入、输出及共享依赖，前端、模型和服务端使用同一已解析契约。

`default=150` 是费率注解，不会自动补齐数据；应用配置和费用快照仍须明确写入两侧费率。150 基点为 1.5%，当前参考买卖两侧合计 3%。金额使用 CNY 元的两位十进制字符串，实际到账和退款须为正数；更正候选可为零，但必须关联原笔，不能据此直接累加收入。日期验证须启用 `date-time` 格式断言。

登记输入样例：

```json
{
  "dealId": "deal-1",
  "termsVersion": 1,
  "feeSide": "BUYER",
  "entryType": "RECEIPT",
  "amountYuan": "30000.00",
  "currency": "CNY",
  "unit": "YUAN",
  "occurredAt": "2026-11-27T11:00:00+08:00"
}
```

Schema 不能替代以下语义校验：当前操作/资源权限、共同人工待办决定及所审版本、归属人对应的收费侧别、原笔同成交同侧且不自引用、哈希和版本一致、来源签名及防重放、去重入账和更正投影。财务候选不得伪装管理员人工续接。

2026-11-27 已用 JSON Schema 2020-12 验证三份元 Schema、共享引用及 35 个正反例，开启日期格式断言。覆盖金额类型与精度、默认值不补必填字段、退款/更正原笔要求、同人双侧、待审越权字段、写入伪造服务端字段及财务预留载荷隔离。该验证只证明字段契约，不代表业务授权、并发或财务联机已验收。
