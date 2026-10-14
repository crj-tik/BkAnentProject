## Purpose

本能力确保独立RAG的运行配置、安全与发布边界可验证，凭据归属清晰、受管HTTP公开与内网分支有界，初始化版本化且保留种子历史，并以真实身份/双后端/重排/配置生效验收区分进程运行和检索能力完成。

## ADDED Requirements

### Requirement: 独立配置与凭据不泄漏

rag-service SHALL 独立发布并独占本次ES/Milvus/embedding/rerank执行配置；消费者保留纯消费及chat/其他业务配置。凭据 SHALL 来自环境/秘密管理，MUST NOT 硬编码、打印或进入语料、模型metadata/参数、日志、审计/checkpoint。公司内网embedding/rerank/LLM本次不新增语料脱敏项目，但不得放宽凭据保护、访谈既有脱敏或擅自改用第三方/公开服务。

#### Scenario: 配置归属与内网语料
- **WHEN** 检查有效配置或将业务语料发送给已授权公司内网模型
- **THEN** 本次后端执行只在RAG，消费者其他业务配置保留，凭据不进入模型或可见记录，不增加新的通用脱敏工程

### Requirement: HTTP双模式使用精确host及对应内网范围

HTTP策略 SHALL 仅使用mode/allowed-hosts/internal-networks：PUBLIC_ONLY默认仅获准host公开地址；MANAGED_INTERNAL显式额外允许精确host对应RFC1918/ULA CIDR map，不共享跨host全局网段授权。受管Docker单标签/host.docker.internal SHALL 仅在精确allowlist/map内准入。仅http/https，localhost/环回/链接本地/IPv4映射/组播/其他保留地址始终禁止；通配host、0/0、任意URL覆盖、userinfo/畸形authority、代理和自动重定向 MUST 被拒绝。缺配置或无匹配 SHALL 失败关闭，安全拒绝不可借降级豁免。

#### Scenario: 显式受管内网
- **WHEN** MANAGED_INTERNAL精确允许某Docker服务host及其实际对应RFC1918范围
- **THEN** 仅该host的全部合规地址可被连接，host.docker.internal容器/Windows解析按实际部署分别验证，不因模式开关允许全部私网

#### Scenario: 默认模式或host跨网
- **WHEN** PUBLIC_ONLY访问私网，或host A解析到仅host B map允许的网段，或配置通配/0/0
- **THEN** 请求发送前拒绝并报ENDPOINT_POLICY_DENIED，不用Nacos/local可信来源绕过

#### Scenario: 公司公开HTTPS地址
- **WHEN** 精确获准公司host解析为全部合规公开地址
- **THEN** 使用公开分支，不要求添加公网CIDR到私网map；仍逐连接核验，不因公司域名跳过守卫

### Requirement: 全部DNS答案和实际连接绑定

所有真实HTTP transport SHALL 每新连接核对全部原始A/AAAA并固定已校验地址，保留映射AAAA识别，原host TLS/SNI/证书验证不弱化。任一答案不合规整体拒绝；连接池重连、重试、配置刷新 SHALL 同样校验，不能仅启动检查或校验后再次不受控解析。自动重定向及代理禁止，当前SDK缺真实扩展时 SHALL 明确阻塞而非假报安全完成。

#### Scenario: DNS混答或换址
- **WHEN** 公开/受管host的答案混入环回、链接本地、映射或非对应私网，或重连后变为禁止地址
- **THEN** 整体拒绝新连接，不访问禁止目标，不将其降级为成功

### Requirement: 基础就绪与检索能力分离

基础readiness SHALL 区别关键词、向量、embedding、rerank及各空间能力。默认混合加重排仅所有schema/维度/profile/安全/授权/空间准备通过时可用；TCP/running不能证明完成。消费者 SHALL 不将RAG后端作为全局硬启动/readiness依赖，故障只拒绝相关检索。

#### Scenario: RAG停机或模型不匹配
- **WHEN** RAG下线或基础健康但维度/重排协议错误
- **THEN** 检索能力明确不可用，SQL详情/结构化记忆/无知识Supervisor仍可使用

### Requirement: 版本初始化和Nacos实际生效验证

发布 SHALL 使用与最新代码一致的版本化首启/现存升级，明确目标库、版本/校验及可重入，接入三份20261012 RAG迁移并限定补齐agent三新编排表/run_control必要列，保留种子历史，不全仓盲重放SQL。Nacos SHALL 核对实际namespace/group/dataID与应用读取；空namespace指public，config-init必须使用受管管理认证且不打印秘密。无持久mount时 SHALL 先受管备份，不盲重建或全部配置覆盖。

#### Scenario: 现存Nacos及数据库缺结构
- **WHEN** BK-AGENT16/public21的DEFAULT_GROUP未有rag配置，旧容器空namespace，MySQL已有种子/历史但缺RAG和当前agent结构
- **THEN** 只对确认目标发布并验证应用读取，升级/重复执行不丢种子历史，不以模板上传或数据库healthy证明最新结构就绪

### Requirement: 开发发布门禁和验证不伪造完成

无生产真实业务 SHALL 用一个有界开发窗口完成备份/停写核对/版本初始化/增量追平/新消费者和无消费者旧RPC清理，不建立虚构RPC兼容发布。后续apply可修改本地/本项目容器与必要配置；旧数据/卷不自动删除或公开。切流 SHALL 通过当前有效身份/ADMIN-BROKER/self-only/无效用户反例、HTTP双模式真实transport、双索引/默认重排/水位及RAG停机验证；缺项明确未执行/阻塞。strict工件验证或旧构建通过 MUST NOT 被当作新实现完成。

#### Scenario: 默认真实路径尚缺验收
- **WHEN** 规划strict通过但新身份/内网守卫/版本初始化/真实双后端重排或SDK尚未验证
- **THEN** 对应任务保持待验，不恢复写入口或宣称完整完成，允许后续apply直接实施授权增量

#### Scenario: 回滚和状态调用点
- **WHEN** 发布需回滚或新增真实状态转换调用点
- **THEN** 按留存config/版本/事件水位恢复并保留数据，新增调用点同步真实路径单测，不仅改路由或恢复旧执行fallback
