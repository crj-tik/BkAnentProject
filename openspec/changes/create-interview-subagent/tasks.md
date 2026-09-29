# Tasks: create-interview-subagent

## 1. 数据库与模块脚手架

- [ ] 1.1 新建 `interview-service` Maven 模块（pom 对齐 contract-service：web、mybatis-plus、mysql、nacos discovery/config、deepseek、spring-ai mcp server webmvc、a2a server、common、common-skill、common-a2a；注册进根 pom `<modules>`）
- [ ] 1.2 编写 `sql/migrations/20260929_interview_subagent.sql`：interview_case / interview_prep_card / interview_question / interview_session / interview_turn / interview_fact_summary / interview_asset / interview_report / interview_report_task / playbook_card / commonality_report（含状态机、乐观锁 version、租约列、幂等唯一键）
- [ ] 1.3 实体与 Mapper（MyBatis Plus，`entity/` + `mapper/`，命名对齐仓库规范 *Entity/*Mapper）
- [ ] 1.4 `nacos/interview-service.yaml`（服务、数据源、deepseek、mcp server STREAMABLE `/mcp`、a2a server、agent.system-prompt、agent.skills 外部目录）与 `application.yml` 骨架（local/distributed profile 对齐既有服务）

## 2. 纯函数决策引擎（engine/，零 Spring 依赖）

- [ ] 2.1 `OutlineRouter`：开台要素 → T1–T8 提纲路由 + 未成交/流失案例的禁成功预设标记
- [ ] 2.2 `ProbeDecisionEngine` 五动作决策（CLOSE/ACK_AND_SWITCH/ANGLE/ADVANCE/OPEN_DRILL）+ `DepthPolicy` 深度分级 + `AngleLadder` 四级换角度阶梯
- [ ] 2.3 `ClosingDetector` 硬/软道别词表双通道判定 + 收尾持久锁语义
- [ ] 2.4 `RepetitionGuard`（bigram 相似度）+ `ProhibitedQuestionFilter`（违禁正则族 + 笑声词/夸奖前缀剥离 + 160 字截断）+ `Sanitizer`（姓名/手机/价格/地址脱敏，映射表仅存服务端）
- [ ] 2.5 `SignalDetector`（停止/纠错/重复抗议/困惑/悬尾 约 50 词表）+ `AnswerStatusJudge`（规则秒判 + ≥3 轮保底）
- [ ] 2.6 `ArchiveGrader` L0–L3 六项确定性检查 + `EvidenceVerifier` 原声逐字命中核验（脱敏域内比对）与缺失三分类
- [ ] 2.7 engine 层单元测试（每个决策组件穷举场景，含 spec 中全部 Scenario 对应用例）

## 3. 运行面（runtime/）

- [ ] 3.1 `InterviewSessionStateMachine`（DRAFT→…→ARCHIVED 状态推进单一入口 + 推进权限表 + 乐观锁；凭据 ticket 签发与校验）
- [ ] 3.2 `InterviewRuntimeService` 话轮管线：入模脱敏闸 → 信号识别 → 决策 → 三段记忆装配（>12 轮拆段）→ 单次流式造句（system 基底取 live-probe 技能正文）→ 出模质量门 → 空返回两级兜底 → SSE 下发 + 写队列落库（幂等键去重、失败退避）
- [ ] 3.3 `InterviewController` REST/SSE（`/interviews/**`：POST turn、GET monitor、GET stream；x-req-id 断线续取对齐 supervisor_stream_events 模式）
- [ ] 3.4 归集补偿扫描器（COLLECT_PENDING 定时扫描、指数退避、冻结快照哈希）+ 资产落库与评级触发

## 4. 治理面（a2a/ + tool/ + 技能）

- [ ] 4.1 `InterviewAgentProperties` + `InterviewOfficialA2aAgent`（ReactAgent + InterviewTools + SkillTool + A2aSupervisorContextInterceptor 副本 + SkillRoutingModelInterceptor，A2aOutputPolicy.structured("interview")）+ `OfficialA2aAgentExecutor` Bean
- [ ] 4.2 `InterviewTools` @Tool：openInterviewCase / generateQuestionSet / confirmQuestions / startInterviewSession（签发凭据+返回入口工件）/ getInterviewMonitor / sendDirectorCommand / finalizeInterview / generateCaseReport / searchAssets / regradeAsset
- [ ] 4.3 技能 md 六份：interview-prep-question-design / interview-live-probe / interview-report-case-card / interview-orchestration（supervisor 域知识技能，放 agent-service）+ 预留 P2 两份占位（不落盘，记录在 tasks）
- [ ] 4.4 agent-service 注册：DistributedAgentProperties 默认项或 nacos 说明 + `nacos/gateway.yaml` 新增 `/interviews/**` 路由

## 5. 能力面（mcp/）

- [ ] 5.1 `InterviewMcpTools implements McpTool`：search_transcripts（L2/L3 过滤）/ get_case_report / submit_transcript（归档+评级）/ grade_transcript / generate_case_report（异步任务 ID + 轮询），共享 service/ 实现，零管理动作
- [ ] 5.2 MCP server 配置（nacos yaml `spring.ai.mcp.server`）+ agent-service 静态 MCP client 连接 `interview-mcp-server`（nacos/agent-service.yaml）

## 6. skill_hint 链路（common-skill + agent-service）

- [ ] 6.1 `SkillRoutingModelInterceptor` 支持 hint 预激活：首轮从请求上下文读取 supervisor.skill_hint，命中本域 Registry 即激活态开局；缺席/未命中回退目录注入（分支不变，向后兼容）
- [ ] 6.2 `OfficialA2aMetadataMapper` 附加 skill_hint（来自 Supervisor 技能匹配结果，建议性可缺席）
- [ ] 6.3 common-skill 既有行为回归：无 hint 路径单测（4 个既有接入域编译 + 拦截器单测）

## 7. 报告任务（service/）

- [ ] 7.1 `interview_report_task` 租约运行器（10 分钟租约、1/3 周期续约、lease_epoch、retryable/blocked 分级、maxRetries 3；进程重启续跑；输入快照哈希幂等，已成功直接返回）
- [ ] 7.2 案例卡生成：核心发现/时间线(≤6 转折)/核心策略/画像四宫格/可复制性三轴（缺失 1 项封顶 79 分，成功案例不得直接判 validated）+ EvidenceVerifier 核验降级 + 缺失三分类
- [ ] 7.3 报告任务单测（幂等复用、租约续跑、核验降级）

## 8. 验证与收尾

- [ ] 8.1 全仓编译 `mvn -gs .mvn-settings.xml -s .mvn-settings.xml compile` 通过；interview-service 与 common-skill 单测 `mvn -pl interview-service -am test` / `-pl common-skill test` 通过
- [ ] 8.2 更新 `docs/ai-interview-subagent-design.md` 顶部链接 change；tasks.md 勾选
- [ ] 8.3 提交推送（中文 commit，按 AGENTS.md 规则）
