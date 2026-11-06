# Tasks

## 1. 现状核对与契约固化

- [ ] 1.1 核对对比报告存储与读取路径（报告 store 的 sources 结构、响应模型、过时/权限复验入口）、listing-agent 技能装配点、Supervisor 技能注册方式与前端契约文档位置；确认卡片投影的挂接点与存量报告兼容性前提。
- [ ] 1.2 固化卡片数据结构契约（cards/metrics/highlights/单位/unknownFields 字段定义）与两种传输方式（内嵌结构块 / reportId 句柄）的边界，作为实现与前端契约文档的共同基准。

## 2. 卡片投影（listing 域，零新对比逻辑）

- [ ] 2.1 实现报告读取响应的卡片投影：读取时从已存储 sources 与规则结果计算 cards（每套一张：listingRef/revision/contentHash/标题/显式单位事实/unknownFields）、跨卡 metrics、每卡 highlights（由确定性指标反查）；不引入独立计算、不修改 rows/metrics 语义。
- [ ] 2.2 测试：多套报告卡片完整性、卡片与 rows 同源逐字段一致、未知字段不补值、单价缺省（缺面积/价格为空且不参与单价指标）、徽标仅来自确定性指标、存量旧报告读取自动获得卡片、过时判定与权限复验行为不变。

## 3. 咨询-报告关联（customer 域）

- [ ] 3.1 新增 `customer_inquiry_comparison` 受管迁移（bk_customer：inquiry_id/report_id/created_by/created_at，UNIQUE(inquiry_id, report_id)、INDEX(inquiry_id, created_at)）+ manifest 追加与 schema check 断言（表/列/唯一键）。
- [ ] 3.2 实现 `POST /customers/inquiries/{inquiryId}/comparison-reports`：校验咨询归属（customer.inquiry.write.own/.all）+ 经签名上下文向房源域核验报告可读（owner=当前经纪人）；任一失败拒绝不落库；同 (咨询,报告) 幂等。
- [ ] 3.3 咨询列表/详情响应带出关联清单（reportId + 关联时间，按时间排序）；不在客户域缓存或转存报告内容。
- [ ] 3.4 测试：关联成功/报告不存在/非本人报告/越权咨询/重复关联幂等/多报告共存/清单读取不含报告正文。

## 4. Supervisor 技能与装配

- [ ] 4.1 编写 Supervisor 技能 `consultation-listing-comparison`：步骤（买方上下文→listing-agent 搜索版本化候选→选 2–6 套→真实 refs+expectedRevisions 对比→卡片结构原样透传+文字引导→有咨询时关联 reportId）与硬约束（禁编造房源/版本、版本冲突重搜一次仍冲突如实告知、权限/依赖失败明确上报、agent 观点只进文字）。
- [ ] 4.2 装配测试：技能可被动态目录发现并指向 listing-agent 现有 `listing-search`/`listing-comparison` 能力；listing-agent 无新增技能；失败关闭（能力缺失/权限不足不回退）。

## 5. 前端契约文档

- [ ] 5.1 编写卡片结构字段说明（含单位/unknownFields/highlights 语义、版本标识用途）与两种传输方式的使用场景（即时对话内嵌块 / 历史回看按 reportId 拉取），明确数据不含 UI 语义、前端自行设计呈现。

## 6. 验收

- [ ] 6.1 端到端验收：买方咨询 → agent 匹配候选 → 对比 → 回答携带卡片结构 → 关联咨询 → 咨询历史回看按 reportId 拉取卡片；负例：版本冲突（搜索与对比之间房源变化→重搜一次→仍冲突告知）、越权关联、报告不可读。
- [ ] 6.2 回归：listing 与 customer 模块全量测试通过；受管 manifest 校验与 dry-run；按项目规范登记 LR（卡片投影同源一致性、关联单向引用）与新增 KI（如有）。
