# Tasks

## 0. 独立会话与契约基线

- [ ] 0.1 按并行总览确认本会话独立 worktree/分支与共同 base SHA、独立 .m2repo 和测试资源，记录路径/分支/基线并验证未导入其它三个实现分支。
- [ ] 0.2 固定本域修改范围、LR/KI编号段及共享文件最小差异，用文件清单验证不改 Auth/Memory 通用协议、Auth 角色默认授权、共享在线登记和其它 change 的任务。
- [ ] 0.3 对照 domain-parallel-v1 固化房源三条 HTTP接口及样例，用生产者契约测试验证包络、字段、CURRENT/STALE/拒绝/不可用语义，无需等待下游消费者。
- [ ] 0.4 建立逐键配置矩阵，覆盖本地 `.env`、`.env.example`、服务配置模板、Nacos YAML、`docker-compose.yml` 和 Nacos 发布清单；逐项标注来源、默认值、密钥属性、消费者、增删/迁移动作及退役门槛。
- [ ] 0.5 核对 `MEMORY_V2_AUTHORIZATION_CONTEXT_ENABLED` 在 `.env.example`、Nacos 和 Compose 的默认值/传递差异；计划统一为默认关闭并显式透传，记录只有 Auth 公钥和部署验收完成后才可开启。

## 1. 房源来源契约与适配

- [ ] 1.1 在 common/房源域定义稳定来源引用、版本、单位及数据模式 DTO，并用契约测试验证跨来源同 ID、legacy-local ID 映射、UNKNOWN 和禁止客户端转正。
- [ ] 1.2 实现 ListingSourcePort、只读 legacy-local 适配器及固定版本 fixture provider，用相同查询/详情样例验证来源身份、版本、模式标记、空匹配和无随机业务事实。
- [ ] 1.3 实现受管 HTTP 来源适配与有界错误映射，用本地 HTTP 夹具验证单位映射、超时、错误响应及不会回退 fixture；真实目标未提供时单独记录未联机状态。
- [ ] 1.4 消费 Auth 当前签名上下文/新鲜度并复核每套来源；用精确签名测试上下文覆盖 `listing.read`/`listing.read.all`、无权、撤权、停用、过期与旧快照，确认缺少接线时拒绝且不信任 brokerId；不修改 Auth 协议或等待其它会话。

## 2. 比较与报告迁入房源域

- [ ] 2.1 将现有比较计算、报告缓存、REST 模型/控制器、工具和 PDF 服务迁入房源 comparison 包，用迁移后的测试验证 2–6 套稳定引用、未知字段与万元到元单价。
- [ ] 2.2 增加稳定来源映射和报告文件迁移/初始化检查；盘点 `compare-report-data` 卷中的 JSON/PDF 与 owner/source hash 元数据，用旧 Long ID、外部字符串 ID、重复迁移和文件对账夹具验证映射及保留稳定。
- [ ] 2.3 实现授权快照报告和缓存/历史读取前的当前来源复核，用 `compare.report.create`、`compare.report.read.own` 与逐套 Listing 读取权限的允许/缺失/撤回组合，加上来源调价、删除和依赖失联，验证 STALE/拒绝/不可用语义；确认缓存指纹不代替当前核验且不自动授予角色权限。
- [ ] 2.4 迁移报告详情、分享和 PDF 读取，用仅持分享码、旧 `publicShare=true` 记录及历史无 owner 文件的反例验证不返回正文或下载字节，并保留文件供受控迁移核对。

## 3. 调用方与部署身份切换

- [ ] 3.1 在 listing-agent 发布 `listing-comparison` 技能和工具，迁移 compare 技能调用方；确认 `a2a:listing-agent` 使用精确稳定映射 `listing.read`，比较创建和报告读取仍分别要求 `compare.report.create` 与 `compare.report.read.own`，不映射通用 `agent.chat.use`。用签名上下文验证缺映射/缺操作失败关闭。
- [ ] 3.2 复用 `listing-master-service/listing/listing-agent` 的既有必需 Memory Hook 与 trusted-caller 登记，不新增 Listing 比较 Agent/身份；用装配测试验证读取、来源复核、成功归档，以及 Memory 不可用/未授权时模型、来源和报告工具均未执行。旧 Compare Memory 仅按原身份留作受控历史读取。
- [ ] 3.3 更新 common 稳定来源比较契约、Supervisor 工具/技能配置、Listing ingress Auth 配置和旧 HTTP 兼容入口；Studio 保留工作区工件与回执，改用稳定来源版本和房源域确定性比较逻辑。用契约测试验证当前授权、预期版本和拒绝语义。
- [ ] 3.4 按配置矩阵同步本地 `.env`、`.env.example`、服务配置模板、Nacos 与 `docker-compose.yml`：新增 Listing 来源 provider/HTTP 配置、`listing.authorization` 开关和公钥注入、Agent capability mapping 与 Memory 可选上下文显式默认；删除或迁移不再使用的 Compare endpoint、服务参数及专属 Memory 参数。保留本地秘密、不提交 `.env`；Compare Auth/Memory 身份仅在排空门禁后移除。

- [ ] 3.5 编制 REST/MCP/A2A/RPC 入口、调用方/Card/Memory 身份、Auth 权限映射、配置项、Compose 服务/卷的迁移清单；用报告卷及在途任务夹具验证文件、原 taskId、历史记忆和授权范围保留，真实运行清单由合并后门禁现场核对。
- [ ] 3.6 停止旧 compare 能力接收新委托并完成原身份对账；旧 `/compare/**` 仅保留当前授权的 legacy-local 兼容，匿名公开分享不再可读。仅在消费者和历史读取不再依赖时退役旧 Nacos、Compose、环境变量和 Auth/Memory 信任项；保留报告卷定义并迁挂到 Listing。共享 Nacos 发布及实际停旧交协调执行。
## 4. 验收与交付

- [ ] 4.1 在仅有共同基线及本 change 的worktree中运行房源/比较和必要调用方聚焦测试/编译，记录模块与独立Maven仓库，验证无需其它change的新类型或制品。
- [ ] 4.2 用模拟接口执行查询、对比、报告、PDF及来源失效闭环，验证每个输出保留模拟标记，重试不重复建报告，旧未知调用不被替代重发。
- [ ] 4.3 在自身隔离库/夹具验证 SQL/check、报告文件保留和回退；逐键核对环境变量、Nacos 占位符、Compose 传递、默认值和敏感项，检查 Compare 残留引用及历史信任/报告卷保留。交付迁移版本、配置矩阵与切流手册；不发布共享 Nacos、不执行容器操作或真实上游联机。
- [ ] 4.4 同步本域 LR 与独立验收证据；保持 KI-115 的 FIXED 状态并更新迁移后的代码/验证引用，除非新回归证据证明该缺陷复发。运行 OpenSpec 严格校验和 diff 检查后中文 commit 并 push 本会话工作分支，提交中不包含 `.env` 或秘密，交付 head SHA/共享文件差异/未联机项，不推 dev-fix 或代合并其它会话。
