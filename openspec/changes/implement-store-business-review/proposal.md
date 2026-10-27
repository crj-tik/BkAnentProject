# 新增独立门店经营卡点复盘服务

## Why

现有 business-service 提供经营分析、排行榜和看板能力，并承载 trade/trade-agent。门店复盘需要新增事项、证据、计划、动作回执和观察状态；继续扩充现有服务会混合不同业务域，并与另一会话的服务拆分在共享模块及配置上发生冲突。本 change 以独立服务承接复盘，现有 business-service 的源码、API、数据库和 trade Memory 身份保持不变。

首期仍服务知识运营人员和店长，优先覆盖带看后停滞与房源维护缺口。模拟经营数据用于验收流程，不作为真实业绩或生产经验结论。

## What Changes

- 新增 business-review-service 微服务、独立数据库 bk_business_review、服务端口 9015、Nacos Data ID business-review-service.yaml 与 Gateway 路径 /business-review/**；复盘 API 使用 /business-review/reviews 前缀。旧 /business/** 路由和 business-service 代码不变。
- 在新服务内保存复盘事项、证据快照、卡点假设、计划修订、确认动作、执行回执与指标观察。房源和知识通过冻结的 domain-parallel-v1 HTTP 契约读取；业务 DTO 留在新服务，不共享或迁移旧 business-service 数据。
- 按当前稳定授权方案使用 CapabilityId.a2a(business-review-agent) 到 agent.workflow.invoke.business-review 的精确映射；领域 API 继续逐操作检查 business.review.*.store，并要求 Auth 签名的 STORE 身份及门店范围。Auth 全局登记由协调/Auth 会话接入。
- 新 Agent 使用独立 A2A 服务身份和必需 Memory Hook，caller/domain/agent 固定为 business-review-service / business-review / business-review-agent。Memory 历史与现有 business-service / trade / trade-agent 隔离；Memory 不是经营事实真源，模拟或人工回报不发布为真实经验结果。
- 同步新增服务涉及的 Maven、服务模板、Nacos、Compose、Gateway、.env.example、服务密钥生成表、数据库初始化、Nacos 发布和部署校验配置。实际 .env 若存在，只补齐所需非敏感变量并保留原值，不提交该忽略文件或任何密钥。
- 与并行拆分会话共享文件时只增加本服务专属条目；服务 ID、数据库、端口、路径、Nacos Data ID、Agent ID、密钥 ID 均使用独立名称。Memory 全局受信调用者、Agent/Auth 注册和密钥生成全局表交由协调会话合并。

## Capabilities

### New Capabilities

- store-business-review：在可信门店范围内创建和推进有来源的经营复盘事项，保留计划、执行回执和观察口径。

### Modified Capabilities

无。既有经营分析、交易 Agent、通用 Agent 编排和 Memory 契约保持原行为。

## Impact

**本 change 拥有**：新增 business-review-service 的 Java 模块与本地 API/DTO、领域处理器、存储和迁移；bk_business_review 数据库；本服务 Agent/技能及 fixture；服务专属 application 配置、business-review-service.yaml、Compose 服务块、Gateway 路由、环境模板变量和发布/启动清单条目。根 Maven 聚合仅追加本模块。现有 business-service 的 Java、配置、SQL、端口、API、trade Agent 和 trade Memory 身份均不修改。

**共享配置交接**：提交可合并的最小差异片段，用于 agent-service 的稳定 A2A capability 映射、gateway authorization audience、mysql-common 的独立数据库别名、memory-service 的精确 trusted caller、公钥注册和 ServiceIdentityKeygen 服务名单。Auth 负责 agent.workflow.invoke.business-review、六个 business.review.*.store 操作及签名 STORE 身份关系的全局登记。Memory/RAG 全局空间配置不新增 business-review 投影；协调会话合并共享注册并核对与其它拆分的配置并集。

**并行边界**：本 change 不移动、重命名或修改 business-service 中任何代码和资源，不直接读取或改写 bk_business，不复用 trade-agent 的权限或 Memory 历史。实现会话在独立 worktree、Maven 仓库和隔离数据库中工作；只触碰本服务专属块，协调会话负责共享聚合文件和全局登记的最终合并。

具体文件差异、Auth/Memory 交接和合并顺序见 design.md；实施任务保持未完成，需由 $openspec-apply-change implement-store-business-review 执行。
