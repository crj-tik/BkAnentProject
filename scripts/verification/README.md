# 私有 RAG / A2A 运行探针

独立 Java 17 `main`，不属于 Maven 模块，不导出服务/API，不使用 stub。runner 仅在**既有项目私有 Docker 网络**中用已 package 的 **agent-service 可执行 jar** 内 classes/libs 编译运行；不会执行 Maven、Compose、下载镜像或读取 `.env`。应由统一构建完成后的操作者执行。

## 公共前置

全部参数通过当前进程环境传入，不接受命令行参数。不要把令牌/密钥写在命令历史或开启 shell tracing；缺少 secret 时，有交互终端的 Java Console 可隐藏输入。runner 仅传显式 allowlist 中的变量，无 Docker socket/端口映射，挂载只读，编译产物在容器 tmpfs，退出即删除。

- `RAG_PROBE_ENABLED=true`
- `RAG_PROBE_DOCKER_NETWORK`：既有私有项目网络；`RAG_PROBE_JDK_IMAGE`：本机已有的 Java 17 JDK 镜像。
- `RAG_PROBE_RUNTIME_JAR`：当前新 package 的 agent-service Boot jar 绝对路径，必须包含唯一的 `a2a-java-sdk-client-0.2.5.Beta2-bk-transport1.jar`。
- `RAG_PROBE_ACCESS_TOKEN`：真实当前 access token；不接受 userId 代替认证。
- `RAG_PROBE_AUTH_DUBBO_URL`：明确的 `dubbo://auth-service:20880` 等真实私网直连地址；端口以实际导出为准。URL 禁止凭据、查询和 fragment。
- `AUTH_RAG_RPC_RESPONSE_KEY_ID` / `AUTH_RAG_RPC_RESPONSE_SECRET`：当前 auth 响应验签配置。
- `RAG_PROBE_DEADLINE_SECONDS`：10–300，默认 240；`RAG_PROBE_RPC_TIMEOUT_MILLIS`：1000–60000，默认 15000。全进程 deadline 强制退出，不宣称远端副作用已取消。

执行 `bash scripts/verification/run-rag-runtime-probe.sh`。第三方 stdout/stderr 在 Java 内关闭；只打印白名单操作、空间、状态、计数、身份 hash 和结构化错误码，绝不打印响应正文、异常消息/stack、token、信标、凭据摘要或配置。编译错误可见但不含运行凭据。Docker 管理员仍能查看容器进程环境；本工具不将进程 env 当作防宿主管理员的 secret manager。

## DOMAIN

`RAG_PROBE_MODE=DOMAIN`，`RAG_PROBE_OPERATION=INSPECT|PROVISION|GET_STATUS|REPLAY`。

- `RAG_PROBE_RAG_DUBBO_URL`：真实 RAG 服务直连 Dubbo URL。
- RAG 接口按服务端契约使用 `fastjson2ctor` 序列化；Auth 直连仍使用其既有 `fastjson2` 配置。
- `RAG_PROBE_SPACE_ID`：`listing.knowledge` / `memory.preference` / `memory.constraint`，或已登记的 Memory v2 逻辑空间。Memory v2 仅接受 `session`、`summary`、`preference-history`、`experience`、`collaboration` 五类和 `marketing/marketing-agent`、`interview/interview-agent`、`listing/listing-agent`、`trade/trade-agent`、`contract/contract-agent`、`compare/compare-agent`、`settlement/settlement-agent` 七组精确映射；它们全部由 `memory-service` 管理，不能传入物理索引或集合名。
- `RAG_PROBE_DOMAIN_KEY_ID`：当前运行配置已信任的 owner keyId；listing 使用 `LISTING_RAG_RPC_SECRET`，memory 使用 `MEMORY_RAG_RPC_SECRET`，不新增 key、不扩大 allowedClients/operation。
- 全部操作要求真实当前 **ADMIN**（执行前与返回前验签/复核）。INSPECT/PROVISION/REPLAY 使用 USER envelope + 专用 token 参数。现有 GET_STATUS 契约无 credential 参数，严格按 owner-domain SERVICE envelope 调用，但探针另外先后验证真实 ADMIN，不新增 SERVICE READ/管理 fallback。
- PROVISION 必须设置 `RAG_PROBE_ALLOW_MUTATIONS=true`、`RAG_PROBE_SCHEMA_VERSION`、`RAG_PROBE_DIMENSION`，仅调用已配置逻辑空间 provision，不提供物理集合/index 定义。Memory v2 当前模板的 schema version 为 `memory-v2.1`、dimension 为 `1024`；运行前仍须与 RAG readiness/实际配置及 embedding 模型响应核对，受管注册表不匹配时拒绝 provision。
- GET_STATUS/REPLAY 必须设置恰好一个 `RAG_PROBE_EVENT_ID` 或 `RAG_PROBE_DOCUMENT_ID`；REPLAY 还须 `RAG_PROBE_ALLOW_MUTATIONS=true`。**个人偏好只接受本人 canonical documentId，不接受 eventId**，避免 service-only status 暴露他人偏好控制事实。
- INSPECT/PROVISION `ready=false` 非零退出。GET_STATUS/REPLAY 返回水位状态不是证明双路 APPLIED、回填/切流完成。
- Memory v2 空间按逐项验收清单显式 INSPECT/PROVISION，不批量启用全部空间。Provision 只创建已注册逻辑空间所绑定的 ES/Milvus schema，不开启 memory ingress、投影或索引 worker。

## A2A

`RAG_PROBE_MODE=A2A`，`RAG_PROBE_OPERATION=SEND|STREAM|GET|RESUBSCRIBE|CANCEL`。

- `AGENT_RAG_RPC_SECRET`：仅供探针通过独立 RAG principal RPC 复核当前用户，使用 Auth 已登记的 `agent-current` RAG key。A2A 信标本身用 agent-service 的 Ed25519 私钥签名，按 `BK-ANENT:A2A-AUTH:v1` 协议域签名。探针只读挂载 `.secrets/service-identities/agent-service/private-key.pem`，并从同目录公钥环境文件读取 Auth 公钥。真实 Spring `@DubboReference` 给生产 `A2aBeaconAuthentication` 接 auth，官方 per-interface P2P override 指定原 auth URL；复用生产 beacon decorator 和官方 patched SDK，JDK transport `Redirect.NEVER`。
- `RAG_PROBE_A2A_AUDIENCE`：九个已支持 service 之一；`RAG_PROBE_A2A_URL`：该 service 原 JSON-RPC endpoint。
- `RAG_PROBE_TASK_ID` / `RAG_PROBE_CONTEXT_ID`：显式隔离任务/上下文，不打印原值。发送和后续 get/resubscribe/cancel **必须保留同一个原 endpoint/audience/task/context/owner**，不得根据新注册地址猜测原任务所在。
- SEND/STREAM 还需 `RAG_PROBE_PROMPT`（最大8192字符）与 `RAG_PROBE_ALLOW_MUTATIONS=true`；prompt 可能触发真实模型/业务工具，必须使用授权的隔离资源。SEND 默认非 blocking；`RAG_PROBE_BLOCKING=true` 显式等待。
- CANCEL 需要 `RAG_PROBE_ALLOW_MUTATIONS=true`，仅取消已明确指定的原任务。GET/RESUBSCRIBE 不重发任务；原 task/context 关联由真实服务器进一步守卫。
- 当前 ADMIN/BROKER 均可走真实 auth 正向链；MANAGER/失效/撤销用户失败关闭。每个实际请求发新精确信标，专用原凭据租约最多300秒且不超过 token expiry；回调与最终 summary 前复核当前授权，凭据不进 business metadata/模型/checkpoint。
- 建议先对一个隔离 task 执行非blocking SEND，再用同原关联 GET/RESUBSCRIBE/CANCEL；另一个隔离 task 用 STREAM。resubscribe/cancel 对已终态任务的真实协议拒绝必须记录为拒绝，不能当作传输未覆盖或自动创建替代任务。

退出码 0 表示本次调用通过本工具检查；1 为结构化失败，124 为 deadline/远端结果未确认。有限样本不是完整角色撤销矩阵、真实模型质量、两索引水位或跨进程恢复验收证明。
