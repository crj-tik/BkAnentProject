# 回归 / I/O基线（历史采样与本轮验证；2页）

## 页1：历史reports与集中重跑

HEAD `dbc13dbb7f18375f880d1d7fef3295357810a37c`。apply context和定向LR/KI已读，节点见implementation-inventory。**本子会未运行Maven**：已向主会先报告准确selector/命令，由主会串行仓库settings测试，避免并行重建common。只读既有Surefire XML统计，不读properties/environment正文，无秘密/业务DB/容器/外部模型访问。

证据为各模块 `target/surefire-reports/TEST-<全限定类名>.xml`。时点为本机文件mtime，XML无timestamp；不伪称执行开始/部署时间。tests取XML实计含参数化；历史报告被后续重跑覆盖前属于旧树，不能验证新未提交实现。

| 模块、准确class（tests） | 2026-10-23历史mtime / 合计 |
|---|---|
| interview-service：ExplicitSkillAgentWiringTest(1)、ArchiveGraderTest(8)、OutlineRouterTest(10)、ProbeDecisionEngineTest(10)、QualityGateTest(16)、SignalAndClosingTest(10)、DirectorCommandTest(5)、InterviewControllerTest(8)、InterviewSessionStateMachineTest(9)、InterviewReportServiceTest(5) | 17:13:38–39 / **82** |
| agent-service：SupervisorCapabilityCatalogTest(3)、SupervisorToolLoopGraphTest(30)、SupervisorMemoryIntegrationTest(31)、AgentPrincipalContextTest(4)、AgentRagCredentialLeaseStoreTest(6)、SupervisorSessionOwnershipServiceTest(3)、SupervisorAsyncTaskLeaseTest(2)、AsyncRuntimeLeaseHeartbeatTest(1) | 15:51:41–57 / **80** |
| memory-service：MemoryV2RequestSecurityTest(7)、MemoryV2ReplayGuardTest(3)、MemoryRagSecurityTest(10)、MemoryArchiveProviderTest(2)、MemoryArchiveServiceTest(33)、MemorySharingAccessServiceTest(16)、MemorySharingWriteServiceTest(5)、MemoryWorkingSetRuntimeTest(20) | 15:52:07–15 / **96** |
| common-a2a：A2aAuthenticatedRequestGuardTest(8)、A2aCredentialContextTest(4)、BeaconA2aHttpClientTest(2)、A2aMemoryAgentHookTest(9)；common-memory：MemoryWorkingSetCacheTest(2) | 15:51:08–09；15:50:54 / **23+2** |

历史选集**283**（82+80+96+23+2），failure/error/skipped均0；非全仓、非本轮重跑或实际身份/部署验收。82不覆盖新增访谈用户关系/参与票/发布/worker围栏。

已报告主会的建议命令（根目录执行，**本子会未执行**）

```bash
mvn -gs .mvn-settings.xml -s .mvn-settings.xml -pl interview-service,agent-service,memory-service,common-a2a,common-memory -am "-Dtest=ExplicitSkillAgentWiringTest,ArchiveGraderTest,OutlineRouterTest,ProbeDecisionEngineTest,QualityGateTest,SignalAndClosingTest,DirectorCommandTest,InterviewControllerTest,InterviewSessionStateMachineTest,InterviewReportServiceTest,SupervisorCapabilityCatalogTest,SupervisorToolLoopGraphTest,SupervisorSessionOwnershipServiceTest,AgentPrincipalContextTest,AgentRagCredentialLeaseStoreTest,SupervisorAsyncTaskLeaseTest,AsyncRuntimeLeaseHeartbeatTest,SupervisorMemoryIntegrationTest,MemoryV2RequestSecurityTest,MemoryV2ReplayGuardTest,MemoryRagSecurityTest,MemoryArchiveProviderTest,MemoryArchiveServiceTest,MemorySharingAccessServiceTest,MemorySharingWriteServiceTest,MemoryWorkingSetRuntimeTest,A2aAuthenticatedRequestGuardTest,A2aCredentialContextTest,BeaconA2aHttpClientTest,A2aMemoryAgentHookTest,MemoryWorkingSetCacheTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

名称均查src/test确认，无MySql/Docker/LiveModel类。可扩展准确存在的MemoryRagSearchFacadeTest、SessionSharedMemoryServiceTest、MemoryControllerTest，另计不混283；Auth/common新实现测试顺序由主会决定。“上游无匹配不失败”不等于上游测试执行。

<div style="page-break-after: always;"></div>

## 页2：采样点、可读assert与缺项

下面是**已读HEAD消费链源码位置/条件，不是实跑次数**。交付前并行Agent新增session/hash/audit/governance守卫已由diff确认，行号可能移动，以类/方法稳定定位；入口/Graph旧重复Auth仍在，新树计数待主会。统计分Auth resolve/hasPermission、A2A issue/resolve、RAG principal、Redis撤销/限流/权限head、Memory operation/来源；不记录token/proof/ticket/正文/真实userID。

| 采样点（模块src/main/java） | 静态意义/保留边界 |
|---|---|
| Gateway AuthTokenFilter:66；Agent filter:25→LeaseStore:105/82；PrincipalContext:47 | 完整受保护正常路径静态Gateway1+filter.currentUserId内部2+principal1=**4次resolve**，非实测；health/Studio/异常/其他路由不能套总数。应同request mock四点。 |
| Graph:138-141/203-206/466-467/511-516、runner凭据检查；Catalog:54-93/AgentPermissionService | 节点/模型/worker/执行前后credentials→resolve；目录每能力/回调→hasPermission，次数随分支非固定N。保留schema/审批hash/lease/cancel/budget。 |
| SupervisorMemoryLifecycle:378-386/449-452；MemoryRpcClient:131-139/115-119 | USER每operation currentActor→verifiedIdentity/resolve；SERVICE deliver不取actor，仅精确permit。read/check/writer/permit/summary/分享分别计，资源源检查不删。 |
| A2aGuard:48/52、CredentialContext:23-25/36-42/75-78、tool interceptor:12-14、Executor流/终态 | 入口resolve与lease validator重复；required/openTask/currentActor/currentCredentials可触发。transport issue、新get/cancel/resubscribe单独计，每delta/工具内部增量待mock。Store/Queue前身份/在途预留/输出源约束保留。 |
| MemoryV2Security:56-84/109-127；Archive/Sharing/SourceAuthorizer | USER入口+输出hasPermission，治理额外许可；SERVICE受限。SQL owner/grant/source/fence另计、不等Auth。无独立MemorySourceAuthorizerTest，来源反例在Archive/Sharing/WorkingSet。 |
| RagAccessControl.principal、ListingRagIdentity.resolve、MemoryRagSecurity.authenticate、Customer/Marketing ReadClient | 独立signed principal重复；领域SQL后当前源/角色复验保留，新context替代成立前不删RAG HMAC/caller操作门。 |
| AuthTokenService.parse→RedisTokenRevocationStore:30/39；RedisSupervisorRateLimiter:21/26 | 实际Redis是revoke SET/hasKey、条件redis provider INCR/首次expire。当前无权限head/snapshot/PubSub消费链，未接线≠实测0；provider启用UNKNOWN，应mock template分项。 |

**现有Auth调用计数可读assert**（并非只有“mock可以看”）

- MemorySharingAccessServiceTest:63一次读 `assertEquals(2,f.auth.calls)`，:70第二次累计4；CountingStore:356-359另数payloadReads=1/2及拒绝0；AuthStub:368-372断言RPC不在SQL锁。仅此fixture，不推广所有Memory恒2。
- MemoryArchiveServiceTest:74-76，SERVICE补交付前后Auth calls不变，:745-746计数stub；MemoryV2RequestSecurityTest:79伪造proof拒绝Auth=0，:200-215有calls/lastPermission，nonce分计。
- SecurityTest:178-180及Auth A2aBeaconRpcServiceImpl:26/87的 `service.identity.signature.verified{protocol,algorithm}` 是成功验签计数，可能含输出复验，**不是RPC/资源许可/request次数**。
- Catalog/Graph/Principal/lease可控mock未找到Auth总次数verify(times)/完整采样assert；现模型或MCP次数不替Auth，fixture不替真实五操作/MCP/部署。

**本轮1.3基线补证**：主会串行仓库settings重跑所列回归及新增基础，11模块成功、413项失败/错误/跳过0。AuthAuthorizationRpcServiceImplTest真实调用新增provider并断言入口tokens.parse=1、snapshot=1；Memory原可读Auth次数/SQL锁边界assert保留。完整同请求Gateway→Supervisor、同版Model、N工具/M流、新域/MCP目标I/O尚未实现，留11.2验收，不声称内部Auth/Redis为0。具体失败修正、SQL/Redis隔离验证和协议阻塞见implementation-progress.md，源码基线与未来验收分开；本子会历史未运行Maven事实仍保留。
