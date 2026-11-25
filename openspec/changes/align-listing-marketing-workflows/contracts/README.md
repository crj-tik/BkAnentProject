# 一期 A2A 契约

三个文件描述可验证的设计。所有领域使用 [共用信封](a2a-workflow.schema.json)。[房源动作](listing-actions.schema.json)和[营销动作](marketing-actions.schema.json)定义各自的请求、正常结果和待办数据。

## 消息与校验

| 信封或状态 | 数据从哪里来 | 谁校验 |
| --- | --- | --- |
| `REQUEST` | Supervisor 发给领域。关联工作、动作为何及本次请求幂等标识 | Supervisor 和领域按 `targetAgent + action + contractVersion` 选择 `x-actions.request` |
| `RESULT / SUCCEEDED` | 领域返回的结构化数据，`inReplyTo` 引用原请求或续接 | Supervisor 按已绑定动作验证 `x-actions.result`，再按业务状态更新任务 |
| `RESULT / WAITING_DOMAIN` | 领域声明本次暂停所需的动作、请求体及依赖 ID | Supervisor 检查依赖登记、动作权限、目标身份和该动作的请求 Schema，再经 A2A 发送 |
| `RESUME_DOMAIN` | Supervisor 回传已关联依赖的最终结果 | 原领域校验原 Task、依赖 ID、消息引用及该依赖的成功输出或失败结构 |
| `RESULT / WAITING_HUMAN` | 领域要求人处理，绑定待办、业务对象、版本、允许选项和页面数据 | Supervisor 校验原动作允许的待办及 `x-humanActions.view`，限制可见人群，再持久化 |
| `RESUME_HUMAN` | Supervisor 校验登录人决策后回转原领域 | 双方检查业务版本、权限、待办、决策名和 `x-humanActions.answersByDecision`，重复提交复用既有结果 |
| `RESULT / FAILED` | 请求或执行未能产生可用的结构化结果 | 接收端按错误码与恢复方式处理。错误描述只给出允许被当前调用人查看的信息 |

`SUCCEEDED` 表示动作产出了契约要求的记录。房源动作仍需判断数据的 `AVAILABLE` / `UNAVAILABLE`。发布动作仍需判断回执的 `SUCCEEDED` / `FAILED` / `OUTCOME_UNKNOWN`。只有渠道明确成功才显示发布办结。结果未知显示待人工核对。等待领域或人工时不发送工作成功结束事件。服务端保存消息和原任务的关联后，才允许消费回执或恢复。

## 一期动作

| 动作 | 执行方 | 正常结果及适用条件 |
| --- | --- | --- |
| `listing.snapshot.read` | Listing Agent | 对当前有权限且在售的房源提供最小快照。记录不存在、对于当前身份不可读和非在售统一为 `UNAVAILABLE`，来源服务自身不可达走信封 `FAILED` |
| `marketing.draft.create` | 营销 Agent | 保存草稿后发起草稿待办。同任务可能经过改稿、调价选择和换房源。最终结果是已审核 revision |
| `marketing.publish.prepare` | 营销 Agent | 根据已审内容准备计划并等待人工确认，必要时处理登录。完成实际调用后返回尝试回执 |
| `marketing.publish.mark-not-published` | 营销 Agent | 原确认人或本部门发布管理员提交核对结论，返回人工纠正记录 |
| `marketing.publish.republish` | 营销 Agent | 由人工纠正记录进入重发确认，产生关联原尝试的独立回执 |

房源引用沿用内部来源 `legacy-local` 的 `sourceId`。营销域将 Listing 返回的 `listingVersion`、`observedAt`、`contentHash`、`askingPriceYuan` 映射到自己的审计快照，与自己的草稿版本分开。人民币金额在跨域数据中使用元和十进制字符串，实施时按十进制值比较价格。

## 最小消息示例

```json
{
  "schemaVersion": "1.0",
  "kind": "REQUEST",
  "messageId": "msg-listing-1",
  "workflowId": "run-marketing-1",
  "sourceAgent": "supervisor",
  "targetAgent": "listing-agent",
  "action": "listing.snapshot.read",
  "contractVersion": "1.0",
  "sentAt": "2026-11-25T10:00:00+08:00",
  "idempotencyKey": "read-listing-1",
  "causationId": "msg-marketing-dependency-1",
  "payload": {
    "listingRef": {"sourceSystem": "legacy-local", "sourceId": "21"},
    "purpose": "MARKETING_DRAFT_CONFIRM"
  }
}
```

会话凭据在服务间的信任机制处理。业务数据里的标识、引用、哈希、决定与号码均须与服务端保存的工作流记录对齐。浏览器的决定内容只描述它选择了什么，办理人从 Auth 签名身份取得。二维码在待办中以短期引用表示，使用受权入口查取。动作权限、审批匹配、租约及去重由运行逻辑校验。
