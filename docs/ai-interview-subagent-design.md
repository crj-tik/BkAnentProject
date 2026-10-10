# AI 深访 subAgent 设计方案（interview-service）

> 来源：S²访谈台（贝壳广州 AI 深访工作台 v12.0）功能提炼。
> 落位：按本仓库「common-skill 技能基建 + A2A 子 Agent」架构设计，接入姿势对齐 `contract-service` 样板。
> 实施追踪：openspec change [create-interview-subagent](../openspec/changes/archive/2026-10-10-create-interview-subagent/proposal.md)（P1 已实施）。
> 范围：只提炼访谈业务功能。CAS 单点登录、签名会话、邀请码、IP 限速、花名册权限树等鉴权能力**不在本设计内**（由平台统一承担，身份经 A2A 可信传递）。

---

## 1. 定位

把「人工访谈 + AI 深访」能力提炼为平台的一个子 Agent 服务：

```
业务侧发起访谈 → 访前出题（题目确认制）→ AI 深访员（小智/小凤）主导或辅助访谈
→ 逐字稿自动归集 + L0–L3 确定性评级 → 单案例「策略打法卡」 / 跨案例「共性沉淀」
```

- **agentId**：`interview-agent`，**domain**：`interview`（新领域，全仓库当前无访谈代码，属全新 change）。
- 服务名 `interview-service`，独立 Spring Boot 模块，对外暴露 A2A（Agent Card + `/.well-known/agent.json`），由 agent-service Supervisor 按技能目录路由。
- 两条对话通道分开：
  - **发起人/Supervisor 通道**：标准 ReAct 循环（ReactAgent + 技能），负责开台、出题确认、报告与三库查询。
  - **受访者话轮通道**：不走 ReAct——由服务端**确定性决策引擎**编排状态机，每次话轮只做一次「造句」模型调用（`代码定决策、模型只造句`），经 interview-service 自有 REST/SSE 接口进出，不绕行 Supervisor。

## 2. 四条贯穿性设计原则（从原产品提炼，全量保留）

1. **访中只还原故事，方法论全部留访后**：访谈中禁止策略提炼/总结类问题（正则拦截），打法卡、画像、可复制性判断全部在访后任务里做。
2. **代码定决策、模型只造句**：追问动作、深度、换角度、转题、收尾全部由纯函数决定；模型只负责按既定动作生成一句话。
3. **所有 AI 产出过服务端证据核验**：原声逐字命中（命不中自动降级为「转述」）、L 等级上限注入、置信度按样本数固定。
4. **一切远端操作幂等可恢复**：幂等键 + 租约 + 状态机（报告任务复用 `agent_async_task` 租约模式；SSE 断线续取对齐 supervisor_stream_events 的 x-req-id 方案）。

## 3. 总体架构

```
agent-service (Supervisor)
   │  A2A / Agent Card
   ▼
interview-service
 ├─ InterviewOfficialA2aAgent        ReactAgent = 人设 prompt + DeepSeek
 │    ├─ 工具：InterviewTools（@Tool，见 §6）+ SkillTool 伪工具
 │    └─ 拦截器：A2aSupervisorContextInterceptor + SkillRoutingModelInterceptor
 ├─ skills/interview/*.md            技能 = YAML frontmatter + systemPrompt（common-skill 加载、热更新）
 ├─ 确定性决策层（纯 Java，不调模型）
 │    OutlineRouter(T1–T8) / ProbeDecisionEngine / DepthPolicy / AngleLadder
 │    ClosingDetector / RepetitionGuard / ProhibitedQuestionFilter / AnswerStatusJudge
 │    ArchiveGrader(L0–L3) / EvidenceVerifier / OriginalQuoteRegistry(原声编号表)
 ├─ 领域服务
 │    InterviewRuntimeService（话轮状态机 + 写队列落库）
 │    ReportTaskService（durable 任务，复用租约）/ TranscriptLibraryService / PlaybookService / CommonalityService
 └─ 存储：MySQL（新表见 §8）+ Milvus（行业大脑/知识库检索）+ 对象存储（提纲/报告/打法卡 HTML）
```

与原产品的组件替换关系：

| S²访谈台原实现 | 本方案落位 |
|---|---|
| rg 检索本地 Obsidian 知识库 | Milvus 向量检索（agent-service 已有 DashScope 向量 + rerank） |
| 自研持久化任务运行器（租约/lease_epoch） | 复用 `agent_async_task` 租约模式与 `AgentAsyncTaskEntity` |
| 自研 SSE 断线续取（x-req-id + 15 分钟终态暂存） | 复用 supervisor_stream_events 同款机制 |
| SQLite | MySQL（`sql/migrations/` 增量脚本） |
| 服务端 WebSocket 注入语音 Key | 二期；文字优先，Key 不下发浏览器的原则保留 |
| CAS/session/邀请码/花名册权限树 | **舍弃**，身份由 A2aInput.metadata 可信传递 |

## 4. 功能分解

### 4.1 访前（开台与出题）

| 功能点 | 设计 |
|---|---|
| 开台五要素 | 场景（门店管理 / 经纪人成交 / 二手客业 / 新房案场 / 社区专家 五选一）、访谈目标（一句话）、组织三级（事业部→大区→商圈，本期只透传不校验花名册）、受访者角色、任务类型（完整还原/关键转折/专项环节/事实补齐）+ 参考时长（15/30/45/60 分钟，只控制问题预算，不到点强停） |
| 案例状态机 | `won/active/lost/churned`；未成交/流失案例自动启用**禁成功预设**问法约束与必采清单（真实卡点、竞品胜出原因等） |
| 提纲路由 | `OutlineRouter` 纯函数：开台参数 → T1–T8 提纲路由（T1 经纪人复盘、T2.1/T2.2 业主、T3.1/T3.2 客户、T4 开发商营销总、T5 案场OP、T6 店东店总、T7 置业顾问、T8 社区专家） |
| 访前基础卡 | 6 个动态字段（客户来源、最大卡点、家庭决策人等，按场景显隐）→ 计算信息完整度 LOW/MEDIUM/HIGH → 分别采用探索型 / 定向补充型 / 时间线补洞型出题 |
| 背景材料 + 知识库拉取 | 可粘贴背景材料；`fetchKnowledge` 工具对 Milvus 检索，片段点选注入背景，AI 访谈前预读 |
| 题目确认制 | `generateQuestionSet` 产候选题（每题带 focus 标签、风险提示、技术参考）→ 发起人勾选（支持按时长自动预算）→ `confirmQuestions` 落库；**只有确认后的题进入访谈**，未选题不计「未回答」 |
| 人工路径 A | `generateManualOutline` 按 T1–T8 框架直接产出提纲文档（不创建 AI 会话）；质量门校验不过自动重试一次，AI 失败用固定框架兜底；不虚构价格日期（写「待访谈确认」） |
| 开场白 | 两句话制；消费者场景（二手业主/客户、新房开发商/客户）自动敬语「您」，其余平语「你」 |

### 4.2 访中（AI 深访员话轮运行时）——核心

每个受访者话轮的**确定性处理管线**（`InterviewRuntimeService`，全部步骤除第 7 步外不调模型）：

1. **输入预处理**：ASR 去重清洗（折叠累加乱码，文字模式跳过）；悬尾检测（≤36 字悬尾词 → 静默等待不回应）。
2. **信号识别**（约 50 词表）：停止→收尾；纠错→承认并按纠正后事实继续；重复抗议→道歉换向；困惑→换大白话重说。
3. **追问决策**：`ProbeDecisionEngine.decideProbeMove` 纯函数，五动作 `CLOSE / ACK_AND_SWITCH / ANGLE / ADVANCE / OPEN_DRILL`，优先级链：结束信号 → 重复抗议 → 最后一题收束 → 全部答完 → 追问超硬上限 → 超软上限换角度 → 自由深挖。
4. **深度分级**：`DepthPolicy`——核心题（前两题）追 4–5 层、一般题 2–3 层，按场景与题序差异化。
5. **换角度阶梯**：`AngleLadder` 四级——补时间线前后节点 → 确认动作归属 → 补可观察结果变化 → 核对判断依据；4 级用满即转题，不换皮重问。
6. **收尾双通道**：硬道别（约 30 词表）无条件收尾；软完结（「我说完了/就这些」）仅在全部题问完才收尾；**收尾持久锁**——首次收尾后本场强制极短对等道别。
7. **模型造句**：从 SkillRegistry 取 `live-probe` 技能正文作 system prompt，组装三段对话记忆（已问问题台账近 80 条 + 受访者已讲事实摘要 + 近期完整对话；>12 轮拆三段防绕回），单次流式调用。
8. **播出前质量门**：违禁问句正则族拦截（反事实假设 / 策略提炼 / 逐字复述压力 / 微表情 / 诱导性预设 / 角色越界 / 换人复制类约 15 条）；`RepetitionGuard` bigram 相似度 ≥0.72 判换皮重问即拦截；风险主题跨轮计数（同类题全场 ≤2 道）；笑声词剥离、通用夸奖前缀剥离、4 句 160 字硬截断。
9. **空返回两级兜底**：流式空 → 非流式重试；仍空 → 直接用清单下一题接住，绝不空追问。
10. **answer_status 判定**：代码规则秒判（ADVANCE/CLOSE 即标已答）+ 后台元数据 LLM fire-and-forget 补充，保底「该题已答 ≥3 轮升级为已答」。
11. **可靠落库**：按会话隔离的写队列 + 失败退避重试；结束访谈前等待全部落库。

模式与变体：

| 模式 | 说明 |
|---|---|
| 辅助模式 | 访谈者主导，AI 只给建议追问，不自动播出 |
| AI 主导 | 自动发问 + 追问（上述全管线） |
| 受访人链接 | 极简功能面（只有消息流），权限只放行本场 turn/summary——本期按「会话凭据」简化实现，列二期 |
| **小凤引擎**（经纪人成交专属） | 服务端强制指定：prepare 不走 AI（七阶段提纲即配置），按经纪人分工切三套（客户成交人/房源维护人/双边），收尾前必补「底牌三件」（真预算/真死线/真拍板人）；**双脑**——快脑流式台词（4 句/160 字硬截断）+ 慢脑后台自节奏跑账本/题卡/灯号/阶段建议（含账本冻结与冷启动预填） |
| 导演台 | 人工监播：SSE 字幕流；快捷指令（收束/下一问/收束→收尾）；加问置顶（可「原样问」逐字播出）；提纲树选问；监播面板（慢脑题卡队列、阶段进度、客户账本、MOT 事件及五项缺口、停靠线索、操作留痕）；慢脑连挂 2 轮红条告警 |
| 社区专家 26 题 | 固定题库不走 AI 出题；转题纯代码判定（明确「不知道」= 有效知识边界直接转题；过短回答只允许一次澄清） |

行业大脑：Milvus 集合 `interview_industry_brain`，五套场景包（门店/经纪人/新房/消费者/社区）+ 认知包 5 步判断顺序；**使用纪律写进技能 prompt**：只帮 AI「听懂」，不当事实来源、不当题库、不制造风险转折、不把访谈改成述职。

### 4.3 访后（归集、评级、报告、资产三库）

**逐字稿归集与 L0–L3 评级**

- AI 访谈结束自动归集：`conversation_state` 状态机 + 后台补偿扫描（每 60 秒扫 `collection_pending`，指数退避最多 8 次；冻结快照哈希校验防覆盖）。
- 人工上传：TXT/MD/doc/docx（编码自动探测 UTF-8/UTF-16/GB18030，docx 用 mammoth、doc 用 word-extractor），统一转 Markdown 归档。
- **L0–L3 归档评级 = 确定性规则，不用模型**：六项检查（正文 ≥300 字 / 对象明确 / 主题聚焦 ≥700 字 / 模块闭环 / 证据充分 / 话轮 ≥4）。L0 拒收、L1 待补充、L2 正式归档、L3 标杆归档（≥1500 字 + 话轮 ≥10 + 模范线）；元数据缺失强制降级；补齐元数据可重评级（`regradeAsset`）。

**报告生成（durable 任务）**

- 报告任务带输入快照与租约，进程重启不依赖 HTTP 继续执行；同访谈并发请求复用同一任务；已有报告直接返回（幂等完成凭据，不再调模型）。
- 三个模板：
  - **标准案例卡**：核心发现（一句话判断 + 五段摘要）、时间线（≤6 转折点）、核心策略（识别信号/动作/结果/适用边界）、画像候选四宫格、**可复制性三轴**（本场内容完整度 / 证据核验成熟度 / L0–L3 可复制性等级——L2 以上需外部证据与失效条件闭环，「成功案例不得直接判 validated」；缺失 1 项封顶 79 分）。
  - **社区知识版**：采集概览、已确认事实（亲历事实/专家判断/待核验三档来源状态）、社区知识萃取、内容选题（≤5 条须绑原声，标「发布前核验」）、待补内容、下一步行动。
  - **门店专属**：四维能力模型（业务/团队/文化/机制，每条必须挂实例）、访谈质量评级（初/中/高级 + 跳针检测）、决策过程轨迹、八大技术使用复盘。
- **服务端证据核验**：AI 只做抽取归纳；服务端核验原声逐字命中（命不中自动降级「转述」）；缺失项三分类 `interview_gap / external_verification / future_research`。
- 报告页支持锚点导航、复制 Markdown（含 YAML frontmatter）、下载逐字稿与打法卡。

**策略打法卡（原声编号表机制）**

- 恰好选 1 份 L2/L3 逐字稿 → 异步任务生成。
- **原声编号表**：服务端预提取受访者原声编 Q001 号 → 模型只返回编号 → 服务端还原原话，杜绝改写/拼接/虚构。
- 硬约束（两级自动修复 prompt，仍不过则 blocked）：恰好 4 核心动作（各含触发信号/≥3 步骤/失效条件）、恰好 3 关键原话、5 防漏提醒、5 执行者要求、时间线 ≥4 节点 ≥2 转折、关键数字禁「约/大概」。
- 支持 HTML 打法卡直传；下载独立 HTML；「来源 N 份逐字稿」可定位回逐字稿库。

**共性提炼库**

- 选 ≥2 份 L2/L3 逐字稿 → 两份产出：**共性客户画像**（客群构成/画像要素/典型画像/核心打动要素/决策路径/矛盾/适用边界）+ **共性项目卖点**（卖点排行/卖点×分型矩阵/卖点分类/客户原声/卖点图谱/反面卖点）。
- 规则：≥2 样本重复才算共性（单样本进 `individual_signals`）；L0 样本排除；整体置信度按样本数固定（2=倾向性 / 3–4=中 / 5+=高）；服务端归一化降级不合规则条目。

## 5. 技能清单（common-skill，domain=interview）

文件放 `interview-service/src/main/resources/skills/interview/*.md`，frontmatter 按 `docs/skill-template.md`：

| 技能 name | 用途 | tools | priority |
|---|---|---|---|
| `interview-prep-question-design` | 访前候选题生成与开台引导 | openInterviewCase, fetchKnowledge, generateQuestionSet, confirmQuestions | 9 |
| `interview-manual-outline` | 人工路径 A：T1–T8 提纲文档生成 | generateManualOutline | 7 |
| `interview-live-probe` | 访中造句（决策由引擎给出，本技能只按动作造句；也被 Runtime 直接取用 systemPrompt） | — | 9 |
| `interview-xiaofeng-script` | 小凤七阶段剧本引擎（经纪人成交专属） | startInterviewSession, getInterviewMonitor, sendDirectorCommand | 8 |
| `interview-community-26q` | 社区专家 26 题制式采集 | startInterviewSession, finalizeInterview | 8 |
| `interview-report-case-card` | 单案例策略打法卡报告 | generateCaseReport, searchAssets | 8 |
| `interview-report-community-knowledge` | 社区知识版报告 | generateCaseReport, searchAssets | 7 |
| `interview-playbook-extraction` | 打法卡（原声编号表） | generatePlaybook, searchAssets | 8 |
| `interview-commonality-mining` | 共性画像 + 共性卖点 | generateCommonality, searchAssets | 8 |

另在 agent-service 加一个 Supervisor 知识技能 `supervisor/interview-orchestration.md`（`supervisor_skill: true`），让 Supervisor 认识本域：何时路由到 interview-agent、四类意图（发起深访 / 生成提纲 / 查三库 / 出报告）。

## 6. 工具清单（InterviewTools @Tool）

| 工具方法 | 作用 |
|---|---|
| `openInterviewCase` | 开台：参数校验、OutlineRouter 路由 T1–T8、访前基础卡生成、禁成功预设开关 |
| `fetchKnowledge` | Milvus 检索行业大脑/背景片段，返回可点选片段 |
| `generateQuestionSet` | 候选题清单（focus/风险提示/技术参考 + 时长预算） |
| `confirmQuestions` | 题目确认制落库（只有确认题进入访谈） |
| `generateManualOutline` | 人工提纲文档生成（质量门 + 固定框架兜底） |
| `startInterviewSession` | 启动会话（辅助 / AI 主导 / 受访人链接 / 小凤剧本） |
| `submitRespondentTurn` | 受访者话轮入口（内部走 §4.2 管线） |
| `getInterviewMonitor` | 导演台监播：题卡队列/灯号/账本/MOT 缺口/停靠线索 |
| `sendDirectorCommand` | 干预指令：收束 / 下一问 / 加问置顶 / 原样问 |
| `finalizeInterview` | 收尾归集 → ArchiveGrader 评级 |
| `generateCaseReport` | 报告 durable 任务（三模板，幂等） |
| `generatePlaybook` | 打法卡任务（原声编号表 + 硬约束修复） |
| `generateCommonality` | 共性提炼任务 |
| `searchAssets` | 三库查询（逐字稿/打法卡/共性，多维筛选） |
| `regradeAsset` | 补齐元数据后重评级 |

## 7. 接入方式（对齐 contract-service 样板）

```java
@Component
public class InterviewOfficialA2aAgent {

    private static final String DOMAIN = "interview";

    private final ReactAgent reactAgent;

    public InterviewOfficialA2aAgent(ChatModel chatModel,
                                     InterviewAgentProperties properties,
                                     InterviewTools interviewTools,
                                     SkillRegistry skillRegistry) {
        List<ToolCallback> tools = new ArrayList<>(Arrays.asList(
                MethodToolCallbackProvider.builder().toolObjects(interviewTools).build().getToolCallbacks()));
        tools.add(new SkillTool(skillRegistry, DOMAIN));

        this.reactAgent = ReactAgent.builder()
                .name("interview-agent")
                .description("AI deep-interview subAgent: pre-interview question design, live interview probing, transcript archiving, case reports and commonality mining")
                .model(chatModel)
                .systemPrompt(properties.getSystemPrompt())
                .tools(tools)
                .interceptors(new A2aSupervisorContextInterceptor(),
                        new SkillRoutingModelInterceptor(skillRegistry, DOMAIN))
                .outputKey("output")
                .build();
    }

    @Bean
    public ReactAgent interviewReactAgent() { return reactAgent; }

    @Bean
    public AgentExecutor interviewA2aAgentExecutor(ObjectMapper objectMapper) {
        return new OfficialA2aAgentExecutor(reactAgent, objectMapper, A2aOutputPolicy.structured("interview"));
    }
}
```

- nacos `interview-service.yaml`：`agent.system-prompt`（深访员人设）+ DeepSeek 模型配置，对齐 `contract-service.yaml`。
- agent-service 侧 `DistributedAgentProperties.AgentRegistration` 增加一项：`agentId=interview-agent`、`supportedDomains=[interview]`、`supportedSkills` 对齐 §5、`supportsStreaming=true`、`supportsAsyncTask=true`、`inputModes/outputModes=[text/json]`。
- 受访者话轮通道不经过 Supervisor：interview-service 自有 REST（POST turn，带 x-req-id）+ SSE 流，复用同一 `InterviewRuntimeService`。
- 技能热加载（`SkillFileWatcher`）直接服务于访谈话术迭代——调 prompt 不用重启。

## 8. 数据模型（新表，SQL 放 `sql/migrations/`）

| 表 | 关键字段 |
|---|---|
| `interview_case` | 场景、受访者角色、组织三级、案例状态(won/active/lost/churned)、禁成功预设开关、创建人工号 |
| `interview_prep_card` | 6 动态字段 JSON、信息完整度(LOW/MEDIUM/HIGH)、出题策略 |
| `interview_question` | focus、风险提示、确认状态、预算序号、answer_status、追问轮数、深度上限 |
| `interview_session` | 模式、状态机、参考时长、收尾锁、风险主题计数、引擎类型(小智/小凤/26题) |
| `interview_turn` | role、content、probe_move、相似度标记、落库重试计数 |
| `interview_fact_summary` | 受访者已讲事实累积摘要（慢脑/后台任务产物，供记忆三段装配） |
| `interview_asset` | 来源(auto/upload)、正文、快照哈希、L0–L3 等级、评级依据 JSON、13 个业务元数据字段、S3 key |
| `interview_report` | 类型(案例卡/社区知识/门店)、输入快照、结论 JSON、证据核验结果、缺失三分类、得分、L 等级上限 |
| `playbook_card` | 来源 asset_id、硬约束校验状态(ok/blocked)、原声编号表快照、S3 key |
| `commonality_report` | 样本 asset 列表、置信度、画像/卖点两份文档 S3 key、individual_signals |
| 报告/提炼任务 | 复用 `agent_async_task`（租约 + retryable/blocked 分级 + maxRetries），不新建任务表 |

状态机：

- 会话：`DRAFT → QUESTIONS_CONFIRMED → IN_PROGRESS → CLOSING_LOCKED → COLLECT_PENDING → ARCHIVED`
- 逐字稿：`PENDING → L0 / L1 / L2 / L3`（可重评）
- 任务：`PENDING → LEASED → RUNNING → SUCCEEDED / RETRYABLE / BLOCKED`（租约 10 分钟、1/3 周期续约、lease_epoch 防旧执行复活）

## 9. 非功能与 SLO

- **SLO**：单轮追问 ≤6 秒（决策为纯函数不占预算，预算全给造句调用）；开台 ≤25 秒；上线自检纳入 `npm run check` 等价的启动冒烟。
- **模型预热**：启动即对三个用途各发一次热身请求，消除冷启动；出题/实时追问/报告可用同模型不同参数起步，预留三模型分工配置位。
- **断线续取**：POST 带 `x-req-id`，终态响应暂存 15 分钟，前端流断后轮询取回。
- **脱敏**（隐私合规，与鉴权无关，保留）：姓名/手机/价格/地址进模型前自动脱敏；对象存储密钥只在服务端注入。
- **可观测**：话轮管线各步骤打点（决策动作分布、拦截次数、兜底触发率），接入现有 Prometheus/Grafana 基线。

## 10. 非目标（本期明确不做）

| 舍弃项 | 处理 |
|---|---|
| CAS 单点登录、签名会话、整站邀请码、IP 限速、花名册权限树 | 舍弃——鉴权由平台承担，身份经 A2A metadata 可信传递；组织三级仅作字段透传 |
| 受访人公网邀请链接（Token 状态机） | 二期按「会话凭据」简化实现（仅放行本场 turn/summary 的边界设计保留） |
| 语音模式（VoiceLoop：实时 ASR/TTS、防抢话三层、断线重连、Wake Lock） | 二期；防抢话三层设计（VAD 3.5s + ASR 最终稿 + 1.2s 缓冲窗 + 话轮优先权）作为需求保留，DashScope 已具备 ASR/TTS 能力 |
| 周报 Excel / 上传总账 / 全量浏览权 | 不属于 subAgent 职责，不移植 |
| Obsidian `rg` 本地知识库 | 替换为 Milvus 检索 |

## 11. 分期落地建议

| 期 | 内容 |
|---|---|
| **P1（MVP）** | 文字 AI 主导 + 辅助模式：开台路由 → 出题确认 → §4.2 全管线（决策引擎 + 质量门 + 兜底）→ 自动归集 + L0–L3 评级 → 标准案例卡报告。落地 = 1 个新服务 + 10 张表 + 6 个核心技能 + Supervisor 注册 |
| **P2** | 小凤双脑 + 导演台、社区 26 题、人工提纲路径、打法卡 + 共性提炼 + 三库前端 |
| **P3** | 语音模式、受访人链接、门店专属报告模板 |

**下一步建议**：用 openspec 立一个 `create-interview-subagent` change（依赖已合并的 common-skill 技能基建），按本方案拆 proposal/design/tasks。
