# Supervisor Agent 鎵ц娴佺▼璇﹁В

## 涓€銆佹灦鏋勬€昏

Supervisor Agent 鏄暣涓 Agent 绯荤粺鐨?澶ц剳"锛岃礋璐ｏ細鎺ユ敹鐢ㄦ埛娑堟伅 鈫?鎰忓浘璇嗗埆 鈫?浠诲姟瑙勫垝 鈫?Agent 璺敱 鈫?璋冨害鎵ц 鈫?瀹℃壒绠＄悊 鈫?缁撴灉姹囨€汇€?
鏁翠釜鎵ц妗嗘灦鍒嗕负 **6 灞?*锛?
```
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹? 绗?1 灞傦細鍏ュ彛涓庣紪鎺?鈥?SupervisorWorkflowService              鈹?鈹? 鎺ユ敹璇锋眰锛岄┍鍔ㄥ叏娴佺▼锛岀鐞嗗苟琛?瀹℃壒鍒嗘敮                        鈹?鈹溾攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹? 绗?2 灞傦細瑙勫垝 DAG 鈥?OfficialPlanningGraph                    鈹?鈹? 6 鑺傜偣绾挎€х閬擄細鍔犺浇浼氳瘽 鈫?LLM鎰忓浘瑙勫垝 鈫?楠岃瘉 鈫?瑙ｆ瀽鎰忓浘       鈹?鈹? 鈫?浠诲姟瑙勫垝 鈫?閫夋嫨Agent銆傝緭鍑?SupervisorGraphState             鈹?鈹溾攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹? 绗?3 灞傦細鎵ц瀛愬浘 鈥?SingleAgent / Parallel / Handoff         鈹?鈹? / Approval / Completion / Resume / RouteDecision             鈹?鈹? 灏嗚鍒掕浆鍖栦负瀹為檯鍔ㄤ綔                                           鈹?鈹溾攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹? 绗?4 灞傦細A2A 閫氫俊 鈥?DelegatingA2aAgentClient                 鈹?鈹? 绠＄悊 Supervisor 涓庡瓙 Agent 涔嬮棿鐨勯€氫俊鍗忚                      鈹?鈹溾攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹? 绗?5 灞傦細鐘舵€佺鐞?鈥?OverAllState + DbGraphCheckpointStore     鈹?鈹? 29 涓姸鎬侀敭锛孯eplace/Append 绛栫暐锛屽熀浜?DB 鐨勫鐗堟湰妫€鏌ョ偣         鈹?鈹溾攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹? 绗?6 灞傦細宸ュ叿璋冪敤 鈥?AgentChatService + HttpAgentMcpClient     鈹?鈹? 鏈湴宸ュ叿 + 7 涓?MCP Server 鐨勫伐鍏凤紝鎸夐渶鍔犺浇                     鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?```

---

## 浜屻€佽鍒?DAG锛氫粠鐢ㄦ埛娑堟伅鍒拌鍔ㄦ寚浠?
### 2.1 鑺傜偣娴佺▼

```
START 鈫?LOAD_SESSION 鈫?LLM_INTENT_PLAN 鈫?PLAN_VALIDATION
      鈫?PARSE_INTENT 鈫?PLAN_TASK 鈫?SELECT_AGENT 鈫?END
```

姣忎釜鑺傜偣閮芥槸 `SupervisorGraphNode` 鎺ュ彛鐨勫疄鐜帮紝绛惧悕涓?`SupervisorGraphState apply(SupervisorGraphState state)`銆傜姸鎬佸湪鑺傜偣闂翠笉鍙彉浼犻€掞紝姣忎釜鑺傜偣杩斿洖涓€涓柊鐘舵€併€?
### 2.2 鑺傜偣鑱岃矗璇﹁В

#### 鑺傜偣 1锛歀oadSessionNode 鈥?鍔犺浇涓婁笅鏂?
**鏂囦欢**锛歚agent-service/.../graph/node/LoadSessionNode.java`

| 鍔ㄤ綔 | 璇存槑 |
|------|------|
| 鍔犺浇浼氳瘽璁板繂 | 浠?`MemoryStoreClient` 璇诲彇 key/value 褰㈠紡鐨勪細璇濆巻鍙?|
| 鍔犺浇鐢ㄦ埛鍋忓ソ | 浠?`UserPreferenceRetriever` 鑾峰彇鐢ㄦ埛鍋忓ソ閰嶇疆 |
| 鍔犺浇绯荤粺绾︽潫 | 鏍规嵁鐢ㄦ埛娑堟伅涓殑鍏抽敭璇嶏紙contract/settlement/notification/marketing/trade/listing锛夊尮閰嶅苟鍔犺浇瀵瑰簲鐨勭郴缁熺骇绾︽潫瑙勫垯 |
| 杈撳嚭 | 灏嗘墍鏈夊唴瀹瑰悎骞跺埌 `sharedContext` |

**鐘舵€佸彉鏇?*锛氭棤瀛楁鍙樻洿锛屼粎涓板瘜 `sharedContext`銆?
#### 鑺傜偣 2锛歀lmIntentPlanNode 鈥?LLM 鎰忓浘瑙勫垝锛堝彲閫夛級

**鏂囦欢**锛歚agent-service/.../graph/node/LlmIntentPlanNode.java`

| 鍔ㄤ綔 | 璇存槑 |
|------|------|
| 璋冪敤 LLM | `SupervisorIntentPlanningService.tryPlan(userMessage, sharedContext)` |
| 鏉′欢鍒ゆ柇 | 濡傛灉 `agent.distributed.planning.llm-enabled=false` 鎴栫瓥鐣ヤ负 `rule-first`锛岃烦杩囨鑺傜偣 |
| LLM 杈撳嚭 | JSON 鏍煎紡鐨勫伐浣滄祦璁″垝锛歚{domain, intent, workflowType, requireApproval, parallelDomains, selectedAgentId, steps}` |
| 娉ㄥ叆涓婁笅鏂?| 灏?`WorkflowPlan` 瀛樺叆 `sharedContext.llmWorkflowPlan` |
| 瀹归敊 | LLM 璋冪敤澶辫触鏃堕潤榛樿烦杩囷紝涓嶅奖鍝嶅悗缁祦绋?|

**鐘舵€佸彉鏇?*锛歚sharedContext` 澧炲姞 `llmWorkflowPlan`銆乣llmSelectedAgentId`銆?
#### 鑺傜偣 3锛歅lanValidationNode 鈥?璁″垝楠岃瘉

**鏂囦欢**锛歚agent-service/.../graph/node/PlanValidationNode.java`

| 鍔ㄤ綔 | 璇存槑 |
|------|------|
| 璇诲彇璁″垝 | 浠?`sharedContext` 涓彁鍙?`WorkflowPlan` |
| 楠岃瘉瑙勫垯 | domain 蹇呴』鍦ㄥ厑璁稿垪琛ㄤ腑锛泈orkflowType 蹇呴』鏈夋晥锛泂electedAgentId 蹇呴』宸叉敞鍐岋紱parallelDomains 蹇呴』鍚堟硶 |
| 瀹归敊 | 璁″垝涓嶅瓨鍦ㄦ垨楠岃瘉澶辫触鏃讹紝涓嶅仛浠讳綍鍙樻洿锛屼氦缁欎笅娓哥殑瑙勫垯寮曟搸澶勭悊 |

**鐘舵€佸彉鏇?*锛歚sharedContext` 涓殑 `WorkflowPlan` 鍙兘琚慨姝ｃ€?
#### 鑺傜偣 4锛歅arseIntentNode 鈥?鎰忓浘瑙ｆ瀽锛圥lanning 鏈€鍏抽敭鐨勮妭鐐癸級

**鏂囦欢**锛歚agent-service/.../graph/node/ParseIntentNode.java`

**鍙岃矾寰勫喅绛?*锛?
```
if (sharedContext 涓湁 LLM 鐢熸垚鐨?WorkflowPlan)
    鈫?鐩存帴浠?WorkflowPlan 鎻愬彇 intent / domain / workflowType
else
    鈫?鍩轰簬瑙勫垯鐨勬剰鍥捐В鏋愶紙resolveDomain 鈫?resolveIntent 鈫?resolveWorkflowType锛?```

**瑙勫垯瑙ｆ瀽閫昏緫**锛坄resolveDomain`锛夛細

| 浼樺厛绾?| 鍒ゆ柇鏉′欢 | 鍒ゅ畾 domain |
|--------|---------|------------|
| 1 | `sharedContext.domain` 宸叉湁鍊?| 浣跨敤宸叉湁鍊?|
| 2 | 娑堟伅鍚?contract/鍚堝悓/绛剧害/褰掓。/ocr | contract |
| 3 | 娑堟伅鍚?notification/閫氱煡/鎻愰啋/娑堟伅 | notification |
| 4 | 娑堟伅鍚?settlement/缁撶畻/浣ｉ噾/鍑烘/鎵撴 | settlement |
| 5 | 娑堟伅鍚?marketing/鏂囨/钀ラ攢/骞垮憡/鎺ㄥ箍/灏忕孩涔?鎶栭煶 | marketing |
| 6 | 娑堟伅鍚?trade/浜ゆ槗/鎴愪氦/椋庨櫓/鍙鎬?| trade |
| 7 | 榛樿 | listing |

**鎰忓浘鏄犲皠**锛坄resolveIntent`锛夛細

| domain | 鍥哄畾 intent |
|--------|------------|
| listing | listing.search |
| marketing | marketing.generate_copy |
| media | media.generate_video_task |
| trade | trade.feasibility_analysis |
| contract | contract.risk_review |
| notification | notification.send |
| settlement | settlement.prepare |

**鐘舵€佸彉鏇?*锛氳缃?`intent`銆乣domain`銆乣workflowType`銆?
#### 鑺傜偣 5锛歅lanTaskNode 鈥?浠诲姟瑙勫垝

**鏂囦欢**锛歚agent-service/.../graph/node/PlanTaskNode.java`

| 鍔ㄤ綔 | 璇存槑 |
|------|------|
| 鍒ゆ柇骞惰 | 濡傛灉 LLM 璁″垝鐨?`parallelDomains` 鏁伴噺 > 1锛岃缃?`requireParallel=true` |
| 鍒ゆ柇瀹℃壒 | 濡傛灉 LLM 璁″垝瑕佹眰瀹℃壒锛岃缃?`requireApproval=true` |
| 榛樿骞惰 | 濡傛灉鐢ㄦ埛娑堟伅鍚屾椂鍖呭惈 listing 鍜?trade 鍏抽敭璇嶏紝榛樿骞惰鍩熶负 `["listing", "trade"]` |
| 宸ヤ綔娴佺被鍨?| 钀ラ攢娑堟伅 鈫?`marketing_pipeline`锛涢渶瑕佸鎵?鈫?`{domain}_with_approval`锛涢粯璁?鈫?`single_agent` |

**鐘舵€佸彉鏇?*锛氳缃?`requireParallel`銆乣requireApproval`銆乣parallelDomains`銆?
#### 鑺傜偣 6锛歋electAgentNode 鈥?Agent 閫夋嫨锛堢粓绔妭鐐癸級

**鏂囦欢**锛歚agent-service/.../graph/node/SelectAgentNode.java`

| 鍦烘櫙 | selectedAgentId 缁撴灉 |
|------|---------------------|
| `requireParallel=true` 涓?`parallelDomains` > 1 | `"parallel:listing,trade"`锛堟嫾鎺ユ牸寮忥級 |
| LLM 璁″垝鐨?`selectedAgentId` 鏈夋晥 | 浣跨敤 LLM 鐨勯€夋嫨 |
| 浠ヤ笂閮戒笉婊¤冻 | 璋冪敤 `SupervisorAgentRoutingService.selectAgent(domain)` 鏌ユ壘 |

**SupervisorAgentRoutingService 鐨勬煡鎵鹃€昏緫**锛?1. 浼樺厛鍦?`DynamicAgentRegistry` 涓寜 domain 鍖归厤宸叉敞鍐岀殑 Agent
2. 妫€鏌ョ伆搴﹀彂甯冮厤缃?`GrayReleaseProperties.preferredAgentIds` 鏄惁鏈夎鐩?3. 閫氳繃 Nacos Agent Registry 鍙戠幇绗﹀悎鏉′欢鐨?Agent 瀹炰緥

**鐘舵€佸彉鏇?*锛氳缃?`selectedAgentId`銆傝鍒掗樁娈电粨鏉熴€?
### 2.3 瑙勫垝杈撳嚭锛歋upervisorGraphState

```
SupervisorGraphState {
    sessionId, taskId, traceId, userId, userMessage  // 鍘熷杈撳叆
    workflowStatus: "RUNNING"                        // 宸ヤ綔娴佺姸鎬?    sharedContext: {                                 // 鍦ㄥ悇鑺傜偣闂寸疮绉殑涓婁笅鏂?        sessionMemory, userPreferences, systemConstraints,
        llmWorkflowPlan, skillKnowledge, ...
    }
    intent: "trade.feasibility_analysis"             // 瑙ｆ瀽鍚庣殑鎰忓浘
    domain: "trade"                                  // 棰嗗煙
    workflowType: "single_agent"                     // 宸ヤ綔娴佺被鍨?    requireParallel: false                           // 鏄惁闇€瑕佸苟琛?    requireApproval: false                           // 鏄惁闇€瑕佸鎵?    selectedAgentId: "trade-agent"                   // 鐩爣 Agent ID
    parallelDomains: []                              // 骞惰鍩熷垪琛?}
```

---

## 涓夈€佹墽琛屽眰锛氳鍒掑浣曞彉鎴愬姩浣?
### 3.1 涓绘祦绋嬪垎鏀紙SupervisorWorkflowService.startWorkflow锛?
```
                    鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                    鈹? SupervisorTaskRequest   鈹?                    鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                鈹?                    鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                    鈹? Planning DAG 鎵ц       鈹?                    鈹? 杈撳嚭 SupervisorGraphState鈹?                    鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                鈹?              鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?              鈹? parallelDomains.size() > 1 ?      鈹?              鈹斺攢鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹?                   鈹?YES                    鈹?NO
         鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?  鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?         鈹?骞惰鎵ц璺緞         鈹?  鈹?鍗?Agent 鎵ц璺緞    鈹?         鈹?ParallelAgentSubgraph鈹?  鈹?SingleAgentSubgraph 鈹?         鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?  鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                   鈹?                       鈹?         鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?  鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?         鈹?鏄惁闇€瑕佸鎵癸紵       鈹?  鈹?鏄惁闇€瑕佸鎵癸紵       鈹?         鈹斺攢鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹?  鈹斺攢鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹?              鈹俌ES       鈹侼O          鈹俌ES       鈹侼O
     鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹? 鈹屸攢鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹? 鈹屸攢鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹? 鈹屸攢鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹?     鈹傝繘鍏ョ瓑寰呭鎵?鈹? 鈹傚畬鎴?   鈹? 鈹傝繘鍏ョ瓑寰?鈹? 鈹傚畬鎴?   鈹?     鈹俉AITING     鈹? 鈹侰ompletion鈹?鈹俉AITING  鈹? 鈹侰ompletion鈹?     鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹? 鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹? 鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹? 鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
骞惰璺緞棰濆鍒ゆ柇锛?    骞惰缁撴灉闇€瑕佽矾鐢卞喅绛栵紵鈫?RouteDecisionSubgraph 鈫?Contract 瀹℃煡 鎴?瀹屾垚
```

### 3.2 鍗?Agent 鎵ц瀛愬浘锛圫ingleAgentSubgraph锛?
**鍐呴儴 DAG**锛?
```
START 鈫?build_invoke_request 鈫?invoke_agent 鈫?persist_artifacts 鈫?merge_result 鈫?END
```

#### 鑺傜偣 1锛歜uild_invoke_request 鈥?鏋勫缓璋冪敤璇锋眰

**瀹炵幇**锛歚BuildInvokeRequestNode`

鏋勫缓鍙戦€佺粰瀛?Agent 鐨?`AgentTaskInvokeRequest`锛?
```
AgentTaskInvokeRequest {
    sessionId, taskId, traceId
    instruction:      鐢ㄦ埛娑堟伅鍘熸枃
    domain:           "trade"
    intent:           "trade.feasibility_analysis"
    structuredContext: {
        userId, keyword, topK, domain,
        retryCount, requestStream, forceAsyncA2a,
        approvalFeedback锛堝鏈夛級
    }
    constraints:      [绯荤粺绾︽潫鍒楄〃]
    expectedOutput:   "Return a trade feasibility assessment..."
    idempotencyKey:   "taskId + targetAgentId + intent + retryCount"
}
```

#### 鑺傜偣 2锛歩nvoke_agent 鈥?璋冪敤瀛?Agent

**瀹炵幇**锛歚InvokeAgentNode` 鈫?`A2aExecutionService.execute()`

璇︾粏娴佺▼瑙?[绗洓绔狅細A2A 閫氫俊](#鍥沘2a-閫氫俊涓庡瓙-agent-浜や簰)銆?
#### 鑺傜偣 3锛歱ersist_artifacts 鈥?鎸佷箙鍖栦骇鐗?
**瀹炵幇**锛歚PersistArtifactsNode.persistSingle()`

灏嗗瓙 Agent 杩斿洖鐨勭粨鏋滄寔涔呭寲涓哄伐浠讹紙Artifact锛夛紝鎻愬彇涓昏宸ヤ欢绫诲瀷锛?
| 宸ヤ欢绫诲瀷 | 鏉ユ簮 Agent | 璇存槑 |
|---------|-----------|------|
| copy_draft | marketing | 钀ラ攢鏂囨鑽夌 |
| publish_payload | marketing | 鍙戝竷璐熻浇 |
| video_task | media | 瑙嗛浠诲姟 |
| contract_summary | contract | 鍚堝悓鎽樿 |
| settlement_summary | settlement | 缁撶畻鎽樿 |

**鐘舵€佸彉鏇?*锛歚artifactIds` 鍒楄〃杩藉姞鏂扮殑宸ヤ欢 ID銆?
#### 鑺傜偣 4锛歮erge_result 鈥?鍚堝苟缁撴灉

**瀹炵幇**锛歚MergeAgentResultNode.mergeSingle()`

灏嗘墽琛岀粨鏋滃悎骞跺埌 `SupervisorWorkflowState`锛岃缃?`finalAnswer`銆乣latestAgentResponse`銆佹洿鏂?`sharedContext`锛堝寘鍚?`latestArtifactIds`銆乣artifactRefs`锛夈€?
### 3.3 骞惰 Agent 鎵ц瀛愬浘锛圥arallelAgentSubgraph锛?
**鍐呴儴 DAG**锛?
```
START 鈫?parallel_invoke 鈫?persist_parallel_artifacts 鈫?merge_parallel_result 鈫?END
```

#### 骞惰璋冪敤鏈哄埗锛圥arallelInvokeNode锛?
```
parallelDomains = ["listing", "trade"]

瀵规瘡涓?domain锛岀敤 CompletableFuture.supplyAsync() 骞惰鎵ц锛?  鈹溾攢 domain="listing"
  鈹?  鈹溾攢 閫夋嫨 Agent: listing-master-service
  鈹?  鈹溾攢 鏋勫缓 AgentTaskInvokeRequest
  鈹?  鈹溾攢 A2aExecutionService.execute()
  鈹?  鈹斺攢 杩斿洖 AgentTaskInvokeResponse
  鈹?  鈹斺攢 domain="trade"
      鈹溾攢 閫夋嫨 Agent: trade-agent
      鈹溾攢 鏋勫缓 AgentTaskInvokeRequest
      鈹溾攢 A2aExecutionService.execute()
      鈹斺攢 杩斿洖 AgentTaskInvokeResponse

绛夊緟鎵€鏈?Future 瀹屾垚 鈫?mergeParallelResponses():
  鈹溾攢 涓烘瘡涓?domain 鐨勮緭鍑烘坊鍔犲懡鍚嶇┖闂? sharedContext.listingOutput, sharedContext.tradeOutput
  鈹溾攢 鏀堕泦鎵€鏈?artifactIds
  鈹溾攢 鐢熸垚 mergeSummary
  鈹斺攢 璁剧疆 contentType: "parallel_result"
```

### 3.4 瀹℃壒娴佺▼

```
                      鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                      鈹? 鎵ц瀹屾垚锛岄渶瀹℃壒       鈹?                      鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                 鈹?                      鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                      鈹?ApprovalSubgraph      鈹?                      鈹?enterWaitingState()   鈹?                      鈹?workflowStatus =      鈹?                      鈹?WAITING_USER_APPROVAL 鈹?                      鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                 鈹?                      鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                      鈹?鎸佷箙鍖栧埌 DB 妫€鏌ョ偣     鈹?                      鈹?杩斿洖 WAITING 鐘舵€佺粰鐢ㄦ埛 鈹?                      鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                 鈹?                        鐢ㄦ埛鎵瑰噯/鎷掔粷/缁堟
                                 鈹?                      鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                      鈹?handleCallback()      鈹?                      鈹?1. 浠?DB 鍔犺浇妫€鏌ョ偣    鈹?                      鈹?2. receiveDecision()  鈹?                      鈹?3. applyDecision()    鈹?                      鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                 鈹?              鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹尖攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?              鈹?                 鈹?                 鈹?    鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹€鈹€鈹? 鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹€鈹€鈹? 鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹€鈹€鈹?    鈹?APPROVED       鈹? 鈹?REJECTED     鈹? 鈹?TERMINATED   鈹?    鈹?resumeAction:  鈹? 鈹?resumeAction:鈹? 鈹?缁堟宸ヤ綔娴?    鈹?    鈹?"complete"     鈹? 鈹?"regenerate" 鈹? 鈹?鍒犻櫎妫€鏌ョ偣     鈹?    鈹?鎴?            鈹? 鈹?甯﹀弽棣堥噸鏂扮敓鎴?鈹? 鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?    鈹?"invoke-next"  鈹? 鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹?    鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?        鈹?            鈹?                 鈹?    鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?    鈹?        ResumeSubgraph               鈹?    鈹?        WorkflowResumeSupport.resume()鈹?    鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?```

**resumeAction 鐨勫彲鑳藉€?*锛?
| resumeAction | 瑙﹀彂鏉′欢 | 琛屼负 |
|-------------|---------|------|
| complete | 鎵瑰噯閫氳繃锛屾棤闇€涓嬩竴姝?| 鏍囪 COMPLETED锛屽垹闄ゆ鏌ョ偣 |
| invoke-next-agent | 鎵瑰噯閫氳繃锛岄渶缁х画涓嬩竴姝?| 鎵ц HandoffSubgraph锛岃皟鐢ㄤ笅涓€涓?Agent |
| regenerate | 鎷掔粷锛岄渶瑕佷慨鏀?| 閲嶆柊璋冪敤褰撳墠 Agent锛屽甫涓婂弽棣堟剰瑙?|
| route-after-parallel | 骞惰鎵ц鍚庨渶璺敱鍒ゆ柇 | 璇勪及鏄惁闇€瑕佷氦鎺ョ粰 contract |

### 3.5 浜ゆ帴娴佺▼锛圚andoffSubgraph锛?
**鍐呴儴 DAG**锛?
```
START 鈫?build_next_agent_context 鈫?handoff_invoke 鈫?END
```

#### build_next_agent_context 鈥?鏋勫缓涓嬫父涓婁笅鏂?
**瀹炵幇**锛歚BuildNextAgentContextNode`

灏嗕笂娓?Agent 鐨勮緭鍑轰腑涓庝笅娓哥浉鍏崇殑閮ㄥ垎鎻愬彇鍑烘潵锛?
```
浠庝笂涓€涓?Agent 鐨勫搷搴斾腑鎻愬彇锛?鈹溾攢 workflowHistory:     涓婃父 Agent 鐨勫畬鏁存墽琛屽巻鍙?鈹溾攢 upstreamArtifactSummaries:  涓婃父浜у嚭鐨勬憳瑕?鈹溾攢 latestArtifactIds:   鏈€鏂板伐浠?ID 鍒楄〃
鈹溾攢 listingOutput:       鎴挎簮鎼滅储缁撴灉
鈹溾攢 tradeOutput:         浜ゆ槗鍙鎬ц瘎浼?鈹溾攢 mergeSummary:        骞惰缁撴灉鐨勫悎骞舵憳瑕?鈹溾攢 copyDraftArtifactId: 钀ラ攢鏂囨鑽夌 ID
鈹溾攢 contractSummaryArtifactId: 鍚堝悓鎽樿 ID
鈹斺攢 ...鐗瑰畾浜庡煙鐨勮緭鍑哄瓧娈?```

#### handoff_invoke 鈥?鎵ц浜ゆ帴璋冪敤

**瀹炵幇**锛歚HandoffNode.handoff()`

```
1. 閫氳繃 SupervisorAgentRoutingService 閫夋嫨涓嬩竴涓?Agent
2. 娓呯悊涓婁笅鏂囷紙sanitizeHandoffContext锛夛紝閫夋嫨鎬у鍒跺叧閿瓧娈?3. 鏋勫缓鏂扮殑 AgentTaskInvokeRequest锛堝惈涓婃父涓婁笅鏂囷級
4. 閫氳繃 A2aExecutionService 璋冪敤涓嬩竴涓?Agent
5. 鎸佷箙鍖栨柊浜у嚭鐨勫伐浠?6. 鍦?MemoryStore 涓褰曚氦鎺ュ叧绯伙紙createHandoffRelation锛?```

### 3.6 閲嶆柊鐢熸垚娴佺▼锛圧egenerate锛?
瀹℃壒鎷掔粷鍚庣殑鏍稿績娴佺▼锛?
```
ResumeSubgraph 鈫?WorkflowResumeSupport.resume()
鈫?resumeAction = "regenerate"
鈫?regenerateSingle(state, feedback):
  1. 妫€鏌ラ噸璇曟鏁版槸鍚﹁秴闄?鈫?瓒呴檺鍒?failRetryLimit()
  2. 鐢?approvalFeedback 鏋勯€犳柊鐨?AgentTaskInvokeRequest
  3. 閲嶆柊 A2A 璋冪敤锛堣皟鐢ㄥ悓涓€涓?Agent锛?  4. 鎸佷箙鍖栨柊宸ヤ欢锛屽垹闄ゆ棫宸ヤ欢
  5. retryCount += 1
  6. 閲嶆柊杩涘叆绛夊緟瀹℃壒鐘舵€?```

---

## 鍥涖€丄2A 閫氫俊锛氫笌瀛?Agent 浜や簰

### 4.1 閫氫俊鏋舵瀯

```
Supervisor Agent
      鈹?      鈻?DelegatingA2aAgentClient   鈫?涓诲叆鍙ｏ紝@Primary Bean
      鈹?      鈹溾攢鈹€ descriptor.runtimeType == ALIBABA_A2A
      鈹?  鈹斺攢鈹€ OfficialA2aAgentClient  鈫?闃块噷宸村反 A2A 鍗忚
      鈹?        鈹溾攢鈹€ 缁撴瀯鍖栬礋杞芥ā寮?(structured)
      鈹?        鈹?  鈹斺攢鈹€ A2AClient.sendMessage() 鈫?Message/SendTask
      鈹?        鈹斺攢鈹€ Plain 妯″紡
      鈹?            鈹斺攢鈹€ A2aRemoteAgent.invoke(instruction, config)
      鈹?      鈹斺攢鈹€ 鏍囧噯 HTTP
          鈹斺攢鈹€ HttpA2aAgentClient  鈫?浼犵粺 HTTP A2A
                鈹斺攢鈹€ RestClient 鈫?POST /a2a
```

### 4.2 OfficialA2aAgentClient 璇﹁В

#### 鍚屾璋冪敤锛坕nvoke锛?
```
invoke(descriptor, request):
  if (descriptor.officialPayloadMode == "structured"):
    1. 鏋勫缓 MessageSendParams:
       Message {
         role: USER,
         parts: [TextPart(instruction)],
         metadata: {
           threadId, sessionId, taskId, traceId,
           sourceAgentId, targetAgentId, intent, domain,
           structuredContext: {...}
         }
       }
    2. A2AClient.sendMessage() 鈫?闃诲绛夊緟鍝嶅簲
    3. 瑙ｆ瀽鍝嶅簲:
       - 杩斿洖 Message 鈫?鎻愬彇鏂囨湰
       - 杩斿洖 Task 鈫?鎻愬彇 text 杈撳嚭
    4. 灏濊瘯灏嗚緭鍑哄弽搴忓垪鍖栦负 AgentTaskInvokeResponse
       - 鎴愬姛 鈫?杩斿洖 AgentTaskInvokeResponse
       - 澶辫触 鈫?鍖呰涓洪€氱敤鍝嶅簲 {officialA2a: true, output: text}
  else (plain):
    1. A2aRemoteAgent.invoke(instruction, runnableConfig)
    2. 浠?OverAllState.output("output") 鎻愬彇缁撴灉
```

#### 寮傛璋冪敤锛坰ubmitAsync + queryAsyncStatus锛?
```
submitAsync(request):
  1. A2AClient.sendMessage() 鈫?涓嶇瓑寰呭畬鎴?  2. 杩斿洖 Task {id, status}

queryAsyncStatus(asyncTaskId):
  1. A2AClient.getTask(asyncTaskId)
  2. switch(task.status.state):
       SUBMITTED  鈫?"SUBMITTED"
       WORKING    鈫?"RUNNING"
       INPUT_REQUIRED / AUTH_REQUIRED 鈫?"WAITING"
       COMPLETED  鈫?"COMPLETED" 鈫?鎻愬彇缁撴灉
       FAILED     鈫?"FAILED" 鈫?鎻愬彇閿欒
       CANCELED   鈫?"CANCELLED"
```

### 4.3 A2aExecutionService 鈥?璋冪敤缂栨帓

```
A2aExecutionService.execute(request, descriptor):
  1. 鏉冮檺妫€鏌? agentPermissionService.assertCanInvokeChildAgent()
  2. 鍒ゆ柇鏄惁寮傛:
     if (agent鏀寔寮傛 && request.forceAsyncA2a):
       submitAsync 鈫?杞(闂撮殧1绉? 鈫?鐩村埌瀹屾垚/澶辫触
       鍙戦€佺姸鎬佷簨浠? task.a2a_async_submitted 鈫?task.a2a_async_running
     else:
       a2aAgentClient.invoke() 鍚屾璋冪敤
```

### 4.4 Agent 鍙戠幇娴佺▼

**DynamicAgentRegistry** 瀹氭湡浠?Nacos 鍙戠幇 Agent 瀹炰緥锛?
```
1. 闈欐€佹敞鍐岋紙鍙€夛級
   agent.distributed.agents.trade-agent.agentId=trade-agent
   agent.distributed.agents.trade-agent.baseUrl=http://...

2. Nacos 鏈嶅姟鍙戠幇
   - 鍙戠幇 ServiceInstance
   - 鎻愬彇鍏冩暟鎹? agent-id, agent-domains, agent-runtime-provider, agent-card-path

3. Agent Card 鑾峰彇
   HTTP GET {baseUrl}/.well-known/agent.json
   鈫?AgentCard {name, description, skills, domains, capabilities, endpoints}

4. 鍒锋柊闂撮殧: refreshIntervalSeconds锛堥粯璁?0绉掞級
```

---

## 浜斻€佸伐鍏疯皟鐢ㄦ満鍒?
### 5.1 Supervisor 灞傜殑宸ュ叿鏋舵瀯

```
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?         AgentServiceConfiguration                鈹?鈹?                                                 鈹?鈹? localToolCallbackProvider    mcpToolCallbacks    鈹?鈹? (Local @Tool beans)         (MCP auto-config)    鈹?鈹?        鈹?                         鈹?             鈹?鈹?        鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?             鈹?鈹?                   鈻?                             鈹?鈹?    combinedToolCallbackProvider                  鈹?鈹?    (ToolCallback[] 鍚堝苟)                          鈹?鈹?                   鈹?                             鈹?鈹?                   鈻?                             鈹?鈹?             chatClient                           鈹?鈹?    ChatClient.builder(chatModel)                 鈹?鈹?      .defaultToolCallbacks(combined)             鈹?鈹?      .build()                                   鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?```

### 5.2 鏈湴宸ュ叿

**鏂囦欢**锛歚BaseToolsConfig.java`

鎵€鏈夊疄鐜颁簡 `AgentTool` 鎺ュ彛鐨?Spring Bean 閮戒細琚嚜鍔ㄦ敹闆嗭紝閫氳繃 `MethodToolCallbackProvider` 鍖呰涓?`ToolCallback[]`銆?
褰撳墠鏈湴宸ュ叿鍖呮嫭锛?- `AgentMilvusTool.milvusKnowledgeSearch`锛歁ilvus 鍚戦噺鐭ヨ瘑搴撴悳绱?
### 5.3 MCP 宸ュ叿

**鏂囦欢**锛歚application.yml` 涓殑 `spring.ai.mcp.client.connections`

| MCP Server | 鍦板潃 | 鎻愪緵鐨勫伐鍏?|
|-----------|------|-----------|
| business-mcp-server | localhost:9010 | queryMonthlyKpis |
| compare-mcp-server | localhost:9006 | compareListings |
| marketing-mcp-server | localhost:9008 | publishMarketingContent |
| contract-mcp-server | localhost:9012 | 锛堝悎鍚岀浉鍏冲伐鍏凤級 |
| settlement-mcp-server | localhost:9013 | 锛堢粨绠楃浉鍏冲伐鍏凤級 |
| notification-mcp-server | localhost:9003 | 锛堥€氱煡鐩稿叧宸ュ叿锛?|
| media-mcp-server | localhost:9007 | 锛堝獟浣撶浉鍏冲伐鍏凤級 |

**璋冪敤娴佺▼**锛坄HttpAgentMcpClient`锛夛細

```
callTool(serverName, toolName, arguments):
  1. clientsByName.get(serverName) 鈫?McpSyncClient
  2. client.callTool(new CallToolRequest(toolName, arguments))
  3. 鎻愬彇缁撴灉:
     - 浼樺厛鎻愬彇 structuredContent.resultText
     - 鍚﹀垯鎻愬彇 content[] 涓殑鎵€鏈夋枃鏈?  4. 杩斿洖 AgentMcpCallResult {serverName, toolName, text, payload}
```

### 5.4 AgentChatService 鈥?Supervisor LLM 璋冪敤

```java
// 鍏ㄩ噺宸ュ叿锛堢敤浜庢剰鍥捐鍒掔瓑闇€瑕佸叏閮ㄥ伐鍏风殑鍦烘櫙锛?chatClient.prompt()
    .system(systemPrompt)
    .user(userPrompt)
    .call()
    .content()

// 浠呮湰鍦板伐鍏凤紙鐢ㄤ簬涓嶉渶瑕?MCP 宸ュ叿鐨勫満鏅級
localToolChatClient.prompt()
    .system(systemPrompt)
    .user(userPrompt)
    .call()
    .content()
```

`allowMcp` 鏍囧織鎺у埗鏄惁鍔犺浇 MCP 宸ュ叿銆傛剰鍥捐鍒掞紙`LlmIntentPlanNode`锛変娇鐢?`allowMcp=false` 璋冪敤锛屽洜涓哄畠鍙渶瑕?LLM 鍋氭剰鍥惧垎绫伙紝涓嶉渶瑕佽皟鐢ㄤ笟鍔″伐鍏枫€?
### 5.5 涓?Skills 鏈哄埗鐨勬暣鍚?
鎸夌収 Skills 鏈哄埗鐨勮璁★紝Supervisor 鐨勫伐鍏峰姞杞藉彲杩涗竴姝ヤ紭鍖栵細

```
褰撳墠: combinedToolCallbackProvider 鈫?鍏ㄩ儴 20+ 宸ュ叿濮嬬粓鍙
浼樺寲鍚? SkillAwareToolProvider 鈫?鎸?domain 鍔ㄦ€佽繃婊?  渚嬪: domain=trade 鈫?鍙姞杞?business-mcp-server 鐨勫伐鍏?```

---

## 鍏€佺姸鎬佺鐞嗘満鍒?
### 6.1 鐘舵€侀敭浣撶郴

鍏?**29 涓姸鎬侀敭**锛坄OfficialSupervisorGraphKeys`锛夛紝鍒嗕负 7 绫伙細

| 绫诲埆 | 閿悕 | 绛栫暐 |
|------|------|------|
| **杈撳叆** | SESSION_ID, TASK_ID, TRACE_ID, USER_ID, USER_MESSAGE | Replace |
| **鎺у埗** | REQUEST_STREAM, WORKFLOW_STATUS, SHARED_CONTEXT | Replace |
| **瑙勫垝** | INTENT, DOMAIN, WORKFLOW_TYPE, REQUIRE_PARALLEL, REQUIRE_APPROVAL | Replace |
| **鎵ц** | SELECTED_AGENT_ID, PARALLEL_DOMAINS, ARTIFACT_IDS, HANDOFF_HISTORY, FINAL_ANSWER | Append(ARTIFACT_IDS, HANDOFF_HISTORY) |
| **瀹℃壒** | PENDING_APPROVAL, LATEST_APPROVAL_DECISION, APPROVAL_RESUME_ACTION, RESUME_FEEDBACK | Replace |
| **A2A** | CURRENT_INVOKE_REQUEST, LATEST_AGENT_RESPONSE | Replace |
| **浜ゆ帴** | NEXT_DOMAIN, HANDOFF_TYPE | Replace |

### 6.2 鐘舵€佸悎骞剁瓥鐣?
- **ReplaceStrategy**锛堥粯璁わ級锛氭柊鍊煎畬鍏ㄨ鐩栨棫鍊笺€傜敤浜庡崟鍊煎瓧娈点€?- **AppendStrategy**锛氭柊鍊艰拷鍔犲埌宸叉湁鍒楄〃銆傜敤浜?`ARTIFACT_IDS` 鍜?`HANDOFF_HISTORY`锛岀‘淇濇墽琛屼骇鐗╃殑瀹屾暣杩芥函銆?
### 6.3 鐘舵€侀€傞厤灞?
```
OverAllState (闃块噷宸村反 Graph 妗嗘灦鍘熺敓鐨?Map 缁撴瀯)
    鈫?OfficialGraphStateAdapters 鍙屽悜杞崲
SupervisorGraphState (瑙勫垝闃舵鐢? / SupervisorWorkflowState (鎵ц闃舵鐢?
```

**鍏抽敭杞崲**锛?- `toSupervisorGraphState(OverAllState)`锛氳鍒?DAG 杈撳嚭 鈫?涓氬姟瀵硅薄
- `toWorkflowState(OverAllState)`锛氭墽琛屽瓙鍥捐緭鍑?鈫?涓氬姟瀵硅薄锛堢壒娈婂鐞?AgentTaskInvokeResponse銆丄pprovalRequest 绛夊鏉傜被鍨嬶級
- `toMap(SupervisorGraphState/WorkflowState)`锛氫笟鍔″璞?鈫?鍥炬鏋惰緭鍏?
### 6.4 妫€鏌ョ偣鎸佷箙鍖栵紙DbGraphCheckpointStore锛?
鍩轰簬鏁版嵁搴撶殑澶氱増鏈鏌ョ偣鏈哄埗锛屾敮鎾戝鎵规殏鍋溾啋鎭㈠娴佺▼锛?
```
save(state):
  1. 鏌ヨ褰撳墠鏈€鏂扮増鏈彿
  2. 鏂板涓€琛岋紙version + 1锛?  3. 瀛樺偍瀛楁:
     - taskId, sessionId, traceId
     - workflowStatus
     - selectedAgentId
     - pendingApprovalId
     - snapshotJson (瀹屾暣鐘舵€佺殑 JSON 搴忓垪鍖?

load(taskId):
  1. SELECT * WHERE task_id = ? ORDER BY version DESC LIMIT 1
  2. 浠?snapshotJson 鍙嶅簭鍒楀寲瀹屾暣鐘舵€?
delete(taskId):
  1. 宸ヤ綔娴佸畬鎴?缁堟鍚庡垹闄ゆ墍鏈夌増鏈殑妫€鏌ョ偣璁板綍
```

---

## 涓冦€佸畬鏁村満鏅蛋鏌?
### 鍦烘櫙 1锛氱畝鍗曞崟 Agent 璋冪敤

**鐢ㄦ埛杈撳叆**锛?杩欎釜鎴垮瓙鑳芥垚浜ゅ悧锛熻瘎浼颁竴涓嬮闄?

```
1. SupervisorWorkflowService.startWorkflow()
2. Planning DAG:
   LoadSessionNode    鈫?sharedContext: {sessionMemory, userPreferences}
   LlmIntentPlanNode  鈫?璺宠繃锛坮ule-first 绛栫暐锛?   PlanValidationNode 鈫?鏃?WorkflowPlan锛岄€忎紶
   ParseIntentNode    鈫?domain=trade, intent=trade.feasibility_analysis
   PlanTaskNode       鈫?workflowType=single_agent, requireApproval=false
   SelectAgentNode    鈫?selectedAgentId=trade-agent
3. SingleAgentSubgraph.execute():
   build_invoke_request 鈫?AgentTaskInvokeRequest(instruction="璇勪及椋庨櫓", domain=trade)
   invoke_agent         鈫?A2A 璋冪敤 trade-agent 鈫?AgentTaskInvokeResponse
   persist_artifacts    鈫?鎸佷箙鍖?trade_assessment 宸ヤ欢
   merge_result         鈫?鍚堝苟鍒?SupervisorWorkflowState
4. 鏃犲鎵?鈫?CompletionSubgraph 鈫?COMPLETED
```

### 鍦烘櫙 2锛氬苟琛屾墽琛?+ 璺敱鍐崇瓥

**鐢ㄦ埛杈撳叆**锛?甯垜鎵惧嚑濂椾笁鎴跨殑鎴垮瓙锛岃瘎浼板摢濂楁渶鍊煎緱涔?

```
1. Planning DAG:
   ParseIntentNode 鈫?鍚屾椂鍖归厤 listing 鍜?trade
   PlanTaskNode    鈫?requireParallel=true, parallelDomains=["listing", "trade"]
   SelectAgentNode 鈫?selectedAgentId="parallel:listing,trade"

2. ParallelAgentSubgraph.execute():
   parallel_invoke:
     鈹溾攢 listing-agent: 鎼滅储涓夋埧鎴挎簮 鈫?杩斿洖 5 濂楁埧婧?     鈹斺攢 trade-agent:   绛夊緟 listing 缁撴灉锛岄€愪竴璇勪及
   merge_parallel_result:
     sharedContext.listingOutput = [{鎴挎簮1}, {鎴挎簮2}, ...]
     sharedContext.tradeOutput = {璇勪及鎶ュ憡, needsMoreDocuments: false}
     mergeSummary = "鍏辨壘鍒?5 濂楁埧婧愶紝鍏朵腑绗?3 濂楁€т环姣旀渶楂?.."

3. shouldAutoRouteAfterParallel() 鈫?true锛坙isting+trade 閮芥秹鍙婏級
4. RouteDecisionSubgraph.execute():
   RouteDecisionNode.evaluate():
     - 妫€鏌?tradeOutput.assessment:
       decision = "PROCEED" 鈫?涓嶉渶瑕佸悎鍚屽鏌?     - 瀹屾垚
5. CompletionSubgraph 鈫?COMPLETED
```

### 鍦烘櫙 3锛氬鎵规祦绋?
**鐢ㄦ埛杈撳叆**锛?鐢熸垚灏忕孩涔﹁惀閿€鏂囨骞跺彂甯?

```
1. Planning DAG:
   ParseIntentNode 鈫?domain=marketing, intent=marketing.generate_copy
   PlanTaskNode    鈫?workflowType=marketing_with_approval, requireApproval=true

2. SingleAgentSubgraph.execute():
   鈫?marketing-agent 鐢熸垚鏂囨鑽夌

3. ApprovalSubgraphService.enterWaitingState():
   workflowStatus = WAITING_USER_APPROVAL
   鎸佷箙鍖栨鏌ョ偣鍒?DB
   杩斿洖缁欑敤鎴? {status: WAITING, draft: "鏂囨鍐呭...", approvalId: "xxx"}

4. 鐢ㄦ埛瀹℃壒锛堥€氳繃鍥炶皟 API锛?
   POST /callback {approvalId: "xxx", decision: "APPROVED"}

5. SupervisorWorkflowService.handleCallback():
   checkpointStore.load(taskId) 鈫?鎭㈠鐘舵€?   receiveDecision() 鈫?璁板綍 APPROVED
   applyDecisionTransition() 鈫?resumeAction = "invoke-next-agent"

6. ResumeSubgraph 鈫?WorkflowResumeSupport.resume("invoke-next-agent"):
   鈫?HandoffSubgraph:
     build_next_agent_context 鈫?鍖呭惈鏂囨鑽夌鐨勪笂涓嬫枃
     handoff_invoke 鈫?璋冪敤 marketing-agent 鐨勬墽琛屽彂甯冩楠?
7. CompletionSubgraph 鈫?COMPLETED
```

### 鍦烘櫙 4锛氭嫆缁濆悗閲嶆柊鐢熸垚

```
瀹℃壒鍥炶皟: {decision: "REJECTED", feedback: "鏂囨澶寮忥紝鏀规垚鍙ｈ鍖栦竴鐐?}

handleCallback():
  receiveDecision() 鈫?REJECTED
  applyDecisionTransition() 鈫?resumeAction = "regenerate"

ResumeSubgraph 鈫?WorkflowResumeSupport.resume("regenerate"):
  regenerateSingle(state, feedback):
    1. 妫€鏌?retryCount < maxRetries (榛樿 3)
    2. 閲嶅缓 AgentTaskInvokeRequest {
         instruction: "閲嶆柊鐢熸垚鏂囨",
         structuredContext: {approvalFeedback: "鏂囨澶寮忥紝鏀规垚鍙ｈ鍖栦竴鐐?}
       }
    3. 閲嶆柊 A2A 璋冪敤 marketing-agent
    4. 鏂版枃妗堟寔涔呭寲锛屾棫宸ヤ欢鍒犻櫎
    6. 閲嶆柊杩涘叆绛夊緟瀹℃壒鐘舵€?
```

---

## 鍏€佸叧閿枃浠剁储寮?
| 妯″潡 | 鏂囦欢 | 鑱岃矗 |
|------|------|------|
| 鍏ュ彛 | `SupervisorWorkflowService.java` | 涓荤紪鎺掑櫒锛屾墍鏈夊伐浣滄祦鐨勫叆鍙?|
| 瑙勫垝 DAG | `OfficialPlanningGraphFactory.java` | 6 鑺傜偣瑙勫垝绠￠亾鐨勫畾涔?|
| | `SupervisorGraphPlanner.java` | 璋冪敤瑙勫垝 DAG 鐨勯棬闈?|
| | `LoadSessionNode.java` | 鍔犺浇浼氳瘽璁板繂/鍋忓ソ/绾︽潫 |
| | `LlmIntentPlanNode.java` | LLM 鎰忓浘瑙勫垝锛堝彲閫夛級 |
| | `PlanValidationNode.java` | 楠岃瘉 LLM 鐢熸垚鐨勮鍒?|
| | `ParseIntentNode.java` | 鎰忓浘/鍩?宸ヤ綔娴佺被鍨嬭В鏋?|
| | `PlanTaskNode.java` | 骞惰鍜屽鎵归渶姹傚垽鏂?|
| | `SelectAgentNode.java` | Agent 韬唤閫夋嫨 |
| 鎵ц瀛愬浘 | `SingleAgentSubgraph.java` | 鍗?Agent 鎵ц |
| | `ParallelAgentSubgraph.java` | 骞惰 Agent 鎵ц |
| | `HandoffSubgraph.java` | Agent 浜ゆ帴鎵ц |
| | `ApprovalSubgraphService.java` | 瀹℃壒绛夊緟/鍐崇瓥閫昏緫 |
| | `CompletionSubgraph.java` | 宸ヤ綔娴佸畬鎴?|
| | `ResumeSubgraph.java` | 瀹℃壒鍚庣户缁?|
| | `RouteDecisionSubgraph.java` | 骞惰鍚庤矾鐢卞喅绛?|
| 缁х画閫昏緫 | `WorkflowResumeSupport.java` | 鎭㈠鎿嶄綔锛坮esume/regenerate/route锛?|
| | `RouteDecisionNode.java` | 骞惰鍚?contract 浜ゆ帴璇勪及 |
| A2A 閫氫俊 | `DelegatingA2aAgentClient.java` | A2A 瀹㈡埛绔鎵?|
| | `OfficialA2aAgentClient.java` | 闃块噷宸村反 A2A 鍗忚瀹炵幇 |
| | `A2aExecutionService.java` | A2A 璋冪敤缂栨帓 |
| | `BuildInvokeRequestNode.java` | 鏋勫缓 Agent 璋冪敤璇锋眰 |
| | `BuildNextAgentContextNode.java` | 鏋勫缓浜ゆ帴涓婁笅鏂?|
| 宸ュ叿 | `AgentServiceConfiguration.java` | 缁勫悎宸ュ叿鍥炶皟 |
| | `AgentChatService.java` | Supervisor LLM 璋冪敤 |
| | `HttpAgentMcpClient.java` | MCP 宸ュ叿璋冪敤 |
| | `BaseToolsConfig.java` | 鏈湴宸ュ叿娉ㄥ唽 |
| Agent 鍙戠幇 | `DynamicAgentRegistry.java` | Nacos + 闈欐€?Agent 娉ㄥ唽 |
| | `SupervisorAgentRoutingService.java` | Agent 璺敱閫夋嫨 |
| 鐘舵€?| `OfficialSupervisorGraphKeys.java` | 29 涓姸鎬侀敭 |
| | `OfficialSupervisorGraphSchema.java` | 鐘舵€佸悎骞剁瓥鐣?|
| | `OfficialGraphStateAdapters.java` | OverAllState 鈫?涓氬姟瀵硅薄 |
| | `SupervisorGraphState.java` | 瑙勫垝闃舵鐘舵€?|
| | `SupervisorWorkflowState.java` | 鎵ц闃舵鐘舵€?|
| | `DbGraphCheckpointStore.java` | DB 妫€鏌ョ偣鎸佷箙鍖?|
| 瑙勫垝鏈嶅姟 | `SupervisorIntentPlanningService.java` | LLM 璁″垝鐢熸垚 + 瑙ｆ瀽 |
