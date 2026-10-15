# Studio V2：已实施接口与交付边界

日期：2026-10-15。变更 `implement-studio-v2`。本文件描述当前源码，完整目标仍见同名 OpenSpec 未完成任务及前端仓库的后端实施 Prompt。

## 身份与存储

浏览器只经过 Gateway；Studio 位于 `/agent/studio/v1`。入口与输出均重新校验真实 Bearer/principal，工作、成果、消息和回执只允许原 owner。ADMIN 的业务房源读范围不扩展到其他人的工作。所有 SQL 均先绑定 owner/work；模型或客户端提供的 owner、角色、URL 不会授予权限。

新增六张表位于 `bk_agent`：studio_work、studio_command、studio_run、studio_message、studio_artifact_series、studio_artifact_revision。创建/修订/命令按 work/requestId/请求哈希幂等，同号不同内容 HTTP409。人工草稿不可变修订和回执同事务，不反向修改 Memory 生成成果。参见 LR-53。

所有普通响应沿用 `ApiResponse`。新增 ID 为字符串、时间为 ISO8601。旧 listing.totalPrice 是万元；Studio 金额为 `{amount:"3200000",currency:"CNY"}`，对应320万元。单价为 `万元×10000/㎡` 元/㎡。参见 LR-54。

## 当前接口

| 方法 | 路径（相对 Studio 根路径） | 语义 |
| --- | --- | --- |
| GET | /capabilities | 受控业务目录；发现不代表健康；当前 directOperations=[] |
| POST | /works | 幂等保存工作草稿；不会自动启动 |
| GET | /works?keyword=&cursor=&limit=20 | 本人工作，更新时间/ID倒序，最多100条；status/needsDecision 当前返回400，不静默忽略 |
| GET | /works/{id} | 上下文、最新执行、待输入/审批、首100成果摘要及状态读取前的事件水位 |
| POST | /works/{id}/context | expectedContextRevision；只允许 refs/criteria，独立条件版本 |
| POST | /works/{id}/ui-state | expectedUiRevision；activeSeriesId须属于本工作，pinnedViews有界 |
| POST | /works/{id}/runs | mode=NEW/CONTINUE、message、requestId、expectedContextRevision、baseArtifactRefs |
| GET | /works/{id}/runs | cursor/limit，按创建时间/请求编号升序 |
| GET | /works/{id}/messages | cursor/limit，按创建时间/消息编号升序 |
| GET | /works/{id}/artifacts | cursor/limit，按系列创建时间/ID升序 |
| GET | /artifact-series/{seriesId}?version= | 当前或指定不可变修订，重新检查来源访问 |
| GET | /artifact-series/{seriesId}/revisions | 版本倒序；nextCursor为下一页的排他版本上界 |
| POST | /artifact-series/{seriesId}/revisions | 营销正文保存或恢复；expectedVersion，追加新版本 |
| POST | /works/{id}/commands | 受控只读比较/候选展开/计划快照；返回真实结果引用 |
| GET | /works/{id}/commands/{requestId} | 查询原回执；结果来源仍重新授权 |

NEW 使用现有 async 执行，事务提交前派发器看不到半关联请求。CONTINUE 只接受原执行 WAITING_USER_INPUT，冻结 context/skill/allowMcp，沿用原有效 contextRevision；已完成追问必须 NEW。为避免未定义写策略，本批 Studio 发起的模型调用使用现有 requireApproval=true 门。该门不代替旧领域接口自身授权。

baseArtifactRefs 由服务端检查 owner/work/series/version/revisionId并装配不可变内容快照，避免将 Studio ID 当成 Memory ID。AI 建议另建系列，保留 baseArtifactRefs；前端不覆盖 dirty 缓冲。

事件水位来自现有 audit，读取在状态快照之前，允许重放重叠。原持久 SSE 可回放，若依赖回退到进程内事件，游标不作全局成功证明；前端每十秒及重连后重新读取权威状态。UI偏好不推进工作条件版本。

## 命令样例

创建后先提交 run，或者为工作引用房源后直接展开候选：

```json
{
  "requestId": "request-001",
  "goal": "比较这两套房源，再由我选择主推",
  "context": {"refs":[{"type":"listing","id":"101"},{"type":"listing","id":"102"}],"criteria":{}}
}
```

```json
{
  "requestId":"request-002",
  "actionId":"load-context-listings",
  "expectedContextRevision":1,
  "artifactRefs":[],
  "input":{}
}
```

该命令按现有签名 READ 获取真实房源，受 LR-40 ADMIN/BROKER 范围约束。比较操作再绑定候选版本及每套来源指纹：

```json
{
  "requestId":"request-003",
  "actionId":"compare:<候选seriesId>",
  "expectedContextRevision":1,
  "artifactRefs":[{"seriesId":"<候选seriesId>","revisionId":"<revisionId>","version":1}],
  "input":{
    "listingIds":["101","102"],
    "sourceVersions":[{"id":"101","sourceRevision":"<来源哈希>"},{"id":"102","sourceRevision":"<来源哈希>"}]
  }
}
```

只接受候选中的2–6套不同房源；来源变化返回 SOURCE_VERSION_CONFLICT。成功回执示意：

```json
{"success":true,"code":"0","message":"OK","data":{"requestId":"request-003","workId":"<workId>","status":"COMPLETED","artifactRefs":[{"seriesId":"<新seriesId>","revisionId":"<新revisionId>","version":1}]}}
```

比较成果含 columns/rows/snapshots/snapshotHash/analysisMode/generatedAt，来源重新签名读取且不使用旧无凭据比较 RPC。共享 `ListingComparisonFacts` 负责单位及数据指纹；旧比较缓存也在来源加载后用 compare-yuan-v2 指纹隔离。旧 `/compare` 正向读取与分享授权尚待迁移，不标为可用。

`publish-plan:<草稿seriesId>` 的 input 为 seriesId/revisionId/version；当前仅保存正文快照及 contentHash，生成 `publish.plan`。planVersion=1、status=INCOMPLETE、providerMode=NOT_CONFIGURED、targets/materialRefs为空，无执行 action。COMPLETED 只表示准备命令保存完成，绝非发布成功。

正文保存 input 为 requestId/expectedVersion/content.body/可选content.headline/note；恢复带 derivedFromVersion，从服务端历史读取正文并追加，忽略客户端伪造历史内容。依赖的条件、房源或基准成果变化标记 STALE；来源不全标记 UNKNOWN。发布计划不能直接编辑。

## 成果投影与限制

- copy_draft/copy_draft_body/marketing_result/marketing 在实际含 draftText 时投影 marketing.draft。
- listing_candidates/listing_result/listing_search/listing 仅在有 listings 集合时重新授权读取，投影真实候选；只有 summary 时保持 text.result，不从正文猜ID。
- contract_summary/contract_review_detail 为 contract.status-check；不把状态/seal文本转换成条款证据。
- 不支持或未知格式保留安全文本及脱敏数据，不能声称已接入该域的结构化结果。
- 原始 artifacts 不重写；sourceArtifactId 用于溯源，seriesId/revisionId 是另一种身份。

## 错误

401 AUTH_401/AUTHENTICATION_FAILED/AUTHENTICATION_EXPIRED；403 ACTION_NOT_ALLOWED/FORBIDDEN；404 WORK_NOT_FOUND统一表达不存在或无权访问；409 COMMAND_CONFLICT/CONTEXT_CONFLICT/UI_CONFLICT/VERSION_CONFLICT/SOURCE_VERSION_CONFLICT/RUN_ACTIVE；400 INVALID_ARGUMENT；503 CAPABILITY_UNAVAILABLE/STUDIO_STORAGE_UNAVAILABLE/STUDIO_DEPENDENCY_UNAVAILABLE及安全RAG错误码。没有向前端返回SQL、provider异常或凭据。

## 未完成对接

发布完整 prepare/read/confirm 与实际审批参数哈希绑定、选定结算预览/确认、保留直写接口等价校验、工作→访谈可信主体绑定及 ticket恢复、文件上传/登记/读取、媒体/经营等完整结果投影、领域列表分页和通知/报告授权均待继续。`/works/{id}/interview-entry`、`/files/{id}/content` 当前不存在；不得提供公开存储或 ticket URL 作为替代。

访谈 `/interviews/{sessionId}/replies/{reqId}` 已改为先查本场持久 INTERVIEWER 回答，再查会话命名空间内的15分钟缓存。它不会推进状态或调用模型。WAIT/处理中/失败的完整回执仍未持久化，保持 KI-12 未完成状态。前端找不到回执时保留原请求，禁止提示换新编号盲重发。

## 发布、验证与回退

新增 manifest 版本 `studio-workspace-20261015-v1`，采用原 `scripts/deploy/run-init-manifest.sh` 流程及规范 LF SHA-256/schema check。未修改任何已应用版本校验值，未应用运行数据库迁移。上线前先备份并检查目标库权限和历史版本，再执行新增版本。公用 RPC 声明 RagException 需消费者/提供者使用匹配 common 制品，保留 STRICT，不放宽包白名单。

本次执行前端类型检查/生产构建、OpenSpec严格校验、相关 Java 模块 compile（跳过测试）；未跑测试套件或真实环境请求，未执行外部发送、发布、付款或业务回放。源码通过不等于迁移、事务、权限矩阵或真实 provider 已验收。

回退代码时保留新增 Studio 表及已有用户草稿，恢复原前端/agent/gateway制品；不删除个人工作，不回放命令。新功能关闭后可使用原临时任务入口。KI-110/111已修的事实保持，当前环境状态另行核实。
