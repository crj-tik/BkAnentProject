# Tasks

## 0. 独立会话与契约基线

- [ ] 0.1 按并行总览记录独立worktree/分支、共同base SHA、.m2repo和测试资源，用git/构建记录验证未导入另外三个实现分支。
- [ ] 0.2 固定拆分边界：只改business-service复盘领域/API/存储/技能、bk_business迁移、common/business/review最小业务DTO、本域fixture和一条a2a:trade-agent受管映射；源码引用扫描验证未依赖房源/知识change新增Java类型，且未修改common/authz、auth-service、其它业务服务、Studio或Agent通用框架。
- [ ] 0.3 按domain-parallel-v1固定房源/知识HTTP样例和成功/空匹配/拒绝/过时/不可用反例，启动本会话本地fixture验证所需输入全部可独立提供。

## 1. 数据与度量边界

- [ ] 1.1 定义复盘输入、来源引用、dataset 和数据模式契约及固定模拟样例，用契约测试验证事件/观察时间、稳定版本和模拟/真实隔离。
- [ ] 1.2 实现带看、跟进、议价和维护的 fixture 数据端口，用同输入同输出及非法来源测试验证无随机推造经营事实。
- [ ] 1.3 定义指标版本、窗口/时区、去重机会单位和有效下一步事件，使用零分母、缺反馈、迟到事件及跨窗口测试验证 UNKNOWN 和分子/分母。
- [ ] 1.4 定义TrustedStoreScopePort并用测试替身注入固定的已验证actor/store范围；验证请求storeId/employeeId不能授予范围、跨店/缺失/过期范围拒绝。当前Auth签名身份只允许BROKER，生产适配在Auth提供签名STORE身份前必须失败关闭；测试替身只验证Business边界，不冒充生产Auth验收，也不阻塞本change独立实现。

## 2. 复盘事项与依赖

- [ ] 2.1 增加事项、证据快照、诊断/计划修订、动作和回执表及增量清单，用迁移重复执行和 request 唯一键测试验证可靠记录。
- [ ] 2.2 实现房源与已发布知识的真实HTTP consumer adapter、消费方本地wire DTO/mapper和显式fixture/http模式；用有界HTTP夹具验证冻结v1的版本、空匹配、拒绝、不可用和禁止静默回退，不能留下空实现。
- [ ] 2.3 实现带看后停滞和房源维护缺口的事实整理及有界模型解释，用无反馈、无价格依据和未核验知识用例验证不虚构原因或经营事实。
- [ ] 2.4 在计划确认和结果使用前复核房源/知识/范围版本，用调价、知识撤回和授权变化测试验证相关计划待复核及旧正文停止使用。

## 3. 推进与观察业务

- [ ] 3.1 实现计划版本、负责人、期限和参数 hash 的确认动作，用并发确认、丢回执重试、同号异内容和旧批准测试验证动作不重复。
- [ ] 3.2 实现动作状态与人工/来源反馈，用 USER_REPORTED、SOURCE_CONFIRMED 及非法状态流转测试验证 DONE 不自动改变上游房源或成交事实。
- [ ] 3.3 实现效率与经营阶段观察查询，用模拟、混合、未知及真实来源样例验证模式继承和不将前后变化解释为因果增长。
- [ ] 3.4 约束反馈/结果事件出口，用模拟数据、模型自评和工具受理反例验证不向生产 Memory 发布有效经营结果证据。

## 4. API 与 Agent 接入

- [ ] 4.1 按design中的HTTP方法/路径/DTO矩阵提供复盘、计划、确认、动作及观察API；沿用ApiResponse和有界cursor，storeId仅作已签范围内的选择器。用controller/服务契约测试逐路由验证精确business.review.*.store操作、真实事项store owner、跨店拒绝、expectedDiagnosisRevision/expectedPlanVersion/expectedActionVersion，并验证确认与反馈的同requestId同hash重放、同号异hash冲突。
- [ ] 4.2 保留trade/trade-agent、trade Memory及现有拦截器；只在受管Agent配置增加CapabilityId.a2a("trade-agent")→agent.workflow.invoke.trade映射，并交付该调用码及全部business.review.*.store操作的Auth登记清单。按D9请求级proof验证A2A实际body绑定、本地验签和精确领域操作；trade-agent A2A 与 TradeTools 只读并形成未落库草稿；确认不能藏在同一次 A2A 调用或通用 TradeMcpTools 中。唯一 Supervisor 写入口为独立 `confirmBusinessReviewPlan` capability，经用户审批后携带 actor/store/plan 版本/hash 调用并由服务端再次授权；执行反馈仍由用户显式调用领域 API。
- [ ] 4.3 输出安全的领域 artifact 和引用契约，用结果投影测试验证 owner/来源/dataMode，保留原 Studio work/command 语义且不勾选其未完成任务。
- [ ] 4.4 新增门店复盘专属 Supervisor 技能和动态目录映射，将计划准备与确认拆成 `prepareBusinessReviewPlan`、`confirmBusinessReviewPlan` 两个 capability；验证确认不藏在 trade-agent A2A 调用内。
- [ ] 4.5 在隔离 Supervisor profile 将确认 capability 的稳定 ID 加入 `approval-capabilities` 配置差异，交付共享配置并集说明；覆盖未批准、拒绝、计划过时和当前门店范围失效均不创建动作。

## 5. 独立验收与交付

- [ ] 5.1 仅用本会话本地 v1 HTTP fixture、Auth/Memory 精确测试登记、TrustedStoreScopePort 测试替身及真实本域持久处理器，经 Supervisor 实际技能、目录和审批路径完成模拟带看停滞和维护闭环；验证未批准不创建动作、计划过时拒绝、回执恢复/撤回/重复输入。无需其它 change 新实现即可验收本域，但单独记录生产 STORE 范围和权限登记尚未启用。
- [ ] 5.2 在共同基线及本change独立运行business及必要消费契约测试/编译，记录自身Maven仓库、功能与模式证据；跨服务联机/真实增长另列未验证，不作为本期等待任务。
- [ ] 5.3 更新独立部署回退、口径及本域LR引用，严格校验和diff检查后中文commit/push本会话分支；交付head SHA、迁移/最小Agent映射差异及Auth登记交接（agent.workflow.invoke.trade、六个business.review.*.store操作和签名STORE身份范围），不修改Auth代码/全局目录、不推dev-fix、不关闭KI-114/128/166，合并后生产权限登记与联调交协调/Auth会话。

## 6. 既有分析能力兼容验收

- [ ] 6.1 在实现前盘点并固定月度 KPI、每日工作量、KPI 评估、排行榜/重建、房源流转、门店看板/大屏的 REST 路由、TradeTools、TradeMcpTools 和代表性结果基线。
- [ ] 6.2 为全部既有 API 和工具增加或更新回归验证，覆盖参数、权限范围和原指标口径；证明复盘上线后每个原入口仍可达且结果未被新复盘数据改写。
- [ ] 6.3 验证既有分析与复盘可在同一门店工作流中并行使用；任何未映射、被隐藏或被替换的既有分析能力都阻止 change 验收完成。
