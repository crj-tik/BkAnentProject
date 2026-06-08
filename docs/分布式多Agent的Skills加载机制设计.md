# 鍒嗗竷寮忓 Agent 鐨?Skills 鍔犺浇鏈哄埗璁捐

## 涓€銆佽儗鏅笌闂

### 1.1 褰撳墠鏋舵瀯鐨勭棝鐐?
鏈郴缁熸槸涓€涓熀浜?Spring Cloud Alibaba + Spring AI + Dubbo + MCP + Nacos 鐨?*澶?Agent 鎴垮湴浜т腑鍙?*銆傛灦鏋勬牳蹇冿細

- **Supervisor Agent**锛坅gent-service锛夛細璐熻矗鎰忓浘璇嗗埆銆佷换鍔¤鍒掋€丄gent 璺敱
- **瀛?Agent**锛坆usiness-service, compare-engine-service, marketing-content-service 绛夛級锛氬悇鑷礋璐ｇ壒瀹氫笟鍔￠鍩熺殑鎵ц
- **MCP 鍗忚**锛氬瓙 Agent 閫氳繃 MCP 鏆撮湶宸ュ叿缁?Supervisor
- **A2A 鍗忚**锛歋upervisor 閫氳繃 A2A 鍚戝瓙 Agent 涓嬪彂浠诲姟

褰撳墠瀛樺湪涓変釜鏍稿績闂锛?
**闂涓€锛歋upervisor 灞傚伐鍏峰叏闆嗘憡骞?*

```java
// AgentServiceConfiguration.java 鈥?褰撳墠瀹炵幇
@Bean("combinedToolCallbackProvider")
public ToolCallbackProvider combinedToolCallbackProvider(
        @Qualifier("localToolCallbackProvider") ToolCallbackProvider localToolCallbackProvider,
        @Qualifier("mcpToolCallbacks") ToolCallbackProvider mcpToolCallbacks) {
    return () -> {
        ToolCallback[] localCallbacks = localToolCallbackProvider.getToolCallbacks();
        ToolCallback[] mcpCallbacks = mcpToolCallbacks.getToolCallbacks();
        ToolCallback[] combined = Arrays.copyOf(localCallbacks, localCallbacks.length + mcpCallbacks.length);
        System.arraycopy(mcpCallbacks, 0, combined, localCallbacks.length, mcpCallbacks.length);
        return combined;
    };
}
```

7 涓?MCP Server 鐨勬墍鏈夊伐鍏?+ 鏈湴宸ュ叿锛屽叡 20+ 涓?tool锛屽叏閮?merge 鍦ㄤ竴璧锋敞鍏ョ粰 Supervisor 鐨?ChatClient銆傝繖瀵艰嚧锛?- 涓婁笅鏂囩獥鍙ｈ宸ュ叿瀹氫箟娑堣€楋紙姣忎釜 tool 鐨?JSON Schema 鍗犵敤鍑犵櫨鍒颁笂鍗?token锛?- LLM 閫夋嫨鍑嗙‘鐜囬殢宸ュ叿鏁伴噺澧炲姞鑰屼笅闄嶏紙attention dilution锛?- 閮ㄥ垎宸ュ叿瀵?Supervisor 鏍规湰鏃犳剰涔夛紙Supervisor 鍙渶瑕佸仛璺敱鍐崇瓥锛?
**闂浜岋細瀛?Agent 宸ュ叿鍏ㄩ泦鍔犺浇**

姣忎釜瀛?Agent锛堝 `TradeOfficialA2aAgent`锛夌殑 ReactAgent 鏋勫缓鏃讹紝涓€娆℃€у姞杞借 Agent 鐨勬墍鏈?`@Tool` 鏂规硶銆傚綋涓氬姟宸ュ叿澧炲鏃讹紙濡?TradeTools 浠?3 涓闀垮埌 10 涓級锛孯eAct 寰幆涓殑 LLM 姣忔閮借浠?10 涓伐鍏蜂腑鍋氶€夋嫨锛屽ぇ閮ㄥ垎鍦烘櫙涓嬬敤鎴峰彧闇€瑕佸叾涓?2-3 涓€?
**闂涓夛細缂轰箯"鎶€鑳?鎶借薄灞?*

Claude Code 鐨?Skills 浣撶郴鎻愪緵浜嗕竴绉嶄紭绉€鐨勬娊璞★細灏?鑳藉姏鍚嶇О"鍜?宸ュ叿缁嗚妭"鍒嗙锛孯outer 鍏堝尮閰?Skill锛屽啀鍔犺浇璇?Skill 涓撳睘鐨勫伐鍏峰拰 prompt銆傜洰鍓嶇郴缁熶腑缂哄皯杩欎竴灞傦紝瀵艰嚧锛?- 鍥哄畾鐨?tool鈫抎omain 鏄犲皠闅句互缁存姢
- 鏃犳硶涓虹壒瀹氫笟鍔″満鏅畾鍒朵笓灞炵殑 prompt + tool 缁勫悎
- 鏂板涓氬姟鍦烘櫙鏃跺繀椤讳慨鏀逛唬鐮佽€岄潪鏂板閰嶇疆鏂囦欢

### 1.2 Skills 鏈哄埗鐨勬牳蹇冨師鐞?
LLM 鏈韩鍙槸 token 棰勬祴鍣紝"浣跨敤宸ュ叿"鐨勮兘鍔涙潵鑷笁娈靛紡閰嶅悎锛?
```
鐢ㄦ埛杈撳叆 鈫?LLM锛堝惈 tool definitions 鐨?system prompt锛夆啋 杈撳嚭 tool_call JSON
鈫?瀹夸富绋嬪簭鎵ц 鈫?缁撴灉鍥炴敞 鈫?LLM 缁х画鎺ㄧ悊
```

**鍏抽敭娲炲療锛歵ool definitions 鏈川涓婂氨鏄?prompt銆?* 浣犵殑浠ｇ爜鎶婂伐鍏锋竻鍗曞簭鍒楀寲鎴?JSON Schema 濉炶繘 `tools` 鍙傛暟锛孡LM 鍍忚鑿滃崟涓€鏍疯鍙栧畠浠紝鐒跺悗"鐐硅彍"銆?
Skills 瑙ｅ喅涓変釜闂锛?
| 闂 | Skills 鐨勮В娉?|
|------|--------------|
| 涓婁笅鏂囩獥鍙ｈ宸ュ叿瀹氫箟鍚冩帀 | 鎳掑姞杞斤細Router 鍏堝仛鎰忓浘鍒嗙被锛屾寜闇€鍔犺浇 tools |
| LLM 閫夋嫨鍑嗙‘鐜囦笅闄嶏紙attention dilution锛?| 浣滅敤鍩熼殧绂伙細涓嶅悓 skill 鐨勫伐鍏蜂笉浜掔浉姹℃煋锛孡LM 姣忔鍙湅鍒?3-10 涓伐鍏?|
| 鏁忔劅淇℃伅娉勯湶 | 鏉冮檺杈圭晫锛氭煇浜涘伐鍏峰彧瀵圭壒瀹?skill 鍙 |

Claude Code 鐨?Skills 鏈哄埗鏈川鏄細**鎳掑姞杞?+ 浣滅敤鍩熼殧绂?*銆?
```
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹? System Prompt锛堟案杩滃姞杞斤級                         鈹?鈹? "鍙敤鐨勬妧鑳? code-review, security-review, ..."    鈹?鈹? 涓嶅姞杞芥瘡涓?skill 鍐呴儴鐨勫伐鍏峰拰璇︾粏 prompt             鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                      鈹?                      鈻?鐢ㄦ埛璇?"/code-review"
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹? Router 鍖归厤 鈫?鍔犺浇 code-review skill             鈹?鈹? - 娉ㄥ叆璇?skill 鐨?system prompt                  鈹?鈹? - 娉ㄥ唽璇?skill 涓撳睘鐨勫伐鍏凤紙鍙兘灏?3-5 涓級           鈹?鈹? - 杩欎簺宸ュ叿鍙湪褰撳墠 skill session 涓彲瑙?            鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?```

### 1.3 瀵规湰绯荤粺鐨勫惎绀?
鏈郴缁?*澶╃劧鍏峰 Skills 鏋舵瀯鐨勫熀纭€**锛?
| 鏈郴缁熺粍浠?| Skills 绛変环鐗?|
|-----------|--------------|
| Supervisor Planner | Router / 鎰忓浘鍒嗙被鍣?|
| 瀛?Agent锛坱rade, compare, marketing...锛?| 涓€涓釜 Skill |
| 瀛?Agent 鏆撮湶鐨?MCP tools | Skill 涓撳睘宸ュ叿闆?|
| A2A 鍗忚璋冨害 | Skill 璋冪敤鍗忚 |

**浣嗛棶棰樺嚭鍦ㄥ疄鐜板眰闈?*锛歚combinedToolCallbackProvider` 鎶婃墍鏈?MCP 宸ュ叿 merge 鍒颁簡 Supervisor 鐨?LLM 闈㈠墠锛孲kills 鐨勯殧绂讳紭鍔胯鎶垫秷浜嗐€?
---

## 浜屻€佷笁灞?Skills 鏋舵瀯璁捐

### 2.1 鏋舵瀯鎬昏

```
                         鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                         鈹?      鐢ㄦ埛杈撳叆             鈹?                         鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                    鈹?         鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?         鈹? Layer 1: Supervisor 鐭ヨ瘑鎶€鑳?                       鈹?         鈹? SkillMatchNode 鈫?matchSupervisorSkills()           鈹?         鈹? 鍖归厤鍒?real-estate-knowledge 鈫?娉ㄥ叆琛屼笟鐭ヨ瘑鍒?       鈹?         鈹? sharedContext 鈫?LlmIntentPlanNode 鎷垮埌鏇翠赴瀵岀殑涓婁笅鏂? 鈹?         鈹? 鈹溾攢 涓嶅姞杞介澶?tool                                   鈹?         鈹? 鈹斺攢 绾煡璇嗗寮猴紝鎻愰珮鎰忓浘璇嗗埆鍑嗙‘鐜?                     鈹?         鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                    鈹?         鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?         鈹? Planner 璺敱锛氱‘瀹?domain + selectedAgentId          鈹?         鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                    鈹?         鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈻尖攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?         鈹? Layer 2: 瀛?Agent 鎿嶄綔鎶€鑳?                          鈹?         鈹? SubAgentSkillSupport.match(userMessage, "trade")    鈹?         鈹? 鈹溾攢 鍖归厤鍒?kpi-report.md                              鈹?         鈹? 鈹?  鈫?鍔ㄦ€佹瀯寤?ChatClient(skill.systemPrompt):       鈹?         鈹? 鈹?    tools=[queryMonthlyKpis, calculateKpiAssessments, 鈹?         鈹? 鈹?           queryRankings]  # 鍙姞杞?3 涓紝涓嶆槸鍏ㄩ儴      鈹?         鈹? 鈹?  鈫?鎸?skill 涓殑姝ラ鎵ц锛屼竴娆¤姹傚嵆寮?             鈹?         鈹? 鈹斺攢 鏈尮閰嶄换浣?skill                                   鈹?         鈹?     鈫?鐢?defaultAgent锛堝叏閮?tool + 鍚妧鑳界洰褰曟竻鍗曠殑 prompt锛夆攤
         鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?```

### 2.2 Layer 1 鈥?Supervisor 鐭ヨ瘑鎶€鑳?
**璁捐鐩爣**锛氶儴鍒?tool 鍙互缁?Supervisor Agent 鍋氭剰鍥剧煡璇嗘敮鎾戯紙渚嬪琛屼笟鐭ヨ瘑锛夛紝鑰屼笉鏄搷浣滄€у姛鑳芥敮鎸併€?
**瀹炵幇鏂瑰紡**锛?- Supervisor 鎷ユ湁鑷繁鐨?skill.md 鏂囦欢锛堜綅浜?`agent-service/src/main/resources/skills/supervisor/`锛?- 杩欎簺 skill 鐨?`supervisor_skill: true`锛宍tools: []`锛堜笉鍔犺浇宸ュ叿锛?- 鍦?Planning Graph 涓柊澧?`SkillMatchNode`锛屾彃鍏ュ湪 `LoadSession` 鍜?`LlmIntentPlan` 涔嬮棿
- 鍖归厤鍒扮殑鐭ヨ瘑鎶€鑳藉唴瀹规敞鍏ュ埌 `sharedContext.skillKnowledge`锛屼笅娓歌妭鐐癸紙LlmIntentPlan 绛夛級鍙洿鎺ュ紩鐢?
**Graph 鑺傜偣娴佺▼**锛堜慨鏀瑰悗锛夛細

```
START 鈫?LOAD_SESSION 鈫?SKILL_MATCH 鈫?LLM_INTENT_PLAN 鈫?PLAN_VALIDATION
鈫?PARSE_INTENT 鈫?PLAN_TASK 鈫?SELECT_AGENT 鈫?END
```

**绀轰緥**锛氱敤鎴疯緭鍏?婊′簲鍞竴鐨勬埧瀛愯浜ゅ灏戠◣锛?

1. `SkillMatchNode` 鍖归厤鍒?`real-estate-knowledge` skill
2. 灏嗘埧鍦颁骇绋庤垂鐭ヨ瘑锛堝绋庛€佷釜绋庛€佸鍊肩◣瑙勫垯锛夋敞鍏?`sharedContext`
3. `LlmIntentPlanNode` 鍦ㄨ皟鐢?LLM 鏃舵惡甯﹁繖浜涚煡璇嗕笂涓嬫枃
4. LLM 鑳芥洿鍑嗙‘鍦拌瘑鍒剰鍥?鈫?`domain=trade, intent=trade.feasibility_analysis`

### 2.3 Layer 2 鈥?瀛?Agent 鎿嶄綔鎶€鑳斤紙閲嶆瀯鐗堬級

**璁捐鐩爣**锛氫负瀛?Agent 鎻愪緵 LLM 鍔犺浇 skill 鐨勬満鍒讹紝鍑忓皯鍥烘湁鍦烘櫙涓氬姟鐨勫伐鍏锋剰鍥捐瘑鍒€傚皢鏌愮涓氬姟锛堝疄鐜版煇涓€涓搷浣滐級鍐欐垚涓€涓?skill.md锛屽綋 LLM 鎰忓浘璇嗗埆鍖归厤涓婃椂鎵嶅姞杞藉搴旂殑宸ュ叿鍜?prompt銆?
**鏍稿績娲炲療**锛氱涓€鐗堣璁￠寤轰簡 N 涓?ReactAgent锛堟瘡涓?skill 涓€涓級锛屼絾鐢ㄦ埛鎸囧嚭杩欐槑鏄句笉鍚堢悊鈥斺€斿嚑鍗佷釜 skill 鎰忓懗鐫€鍑犲崄涓?ReactAgent 瀹炰緥銆傛纭殑鍋氭硶鏄弬鑰?Claude Code锛?*defaultAgent 鐨?LLM 鍙姞杞芥墍鏈?skill 鐨?YAML 娓呭崟锛堣交閲忕洰褰曪紝涓嶅惈鎵ц缁嗚妭锛夛紝鍖归厤鍒版煇涓?skill 鏃舵墠鍔ㄦ€佹瀯寤鸿交閲?ChatClient 鍘绘墽琛屻€?*

**瀹炵幇鏂瑰紡**锛?
1. **鍚姩鏃?*锛氬彧鏋勫缓 **1 涓?defaultAgent**锛堝叏閮?tool + 榛樿 systemPrompt + skill 鐩綍娓呭崟锛?2. **璇锋眰鍒拌揪鏃?*锛氬厛鐢?`SkillMatcher` 鍋氬叧閿瘝蹇€熷尮閰?3. **鍛戒腑 skill**锛氬姩鎬佹瀯寤鸿交閲?`ChatClient`锛堟敞鍏ヨ skill 鐨勫畬鏁?systemPrompt + 鍙姞杞藉叾 tools锛夆€斺€旀瀯寤烘垚鏈瀬浣庯紝ChatModel 鏄叡浜崟渚?4. **鏈懡涓?*锛歚defaultAgent` 鑷澶勭悊锛堝叏閲?tools + 鍚妧鑳芥竻鍗曠殑 system prompt锛孡LM 鐪嬪埌鐩綍鍚庝篃鑳借嚜琛屽垽鏂級

**涓庣涓€鐗堣璁＄殑瀵规瘮**锛?
| 缁村害 | 绗竴鐗堬紙棰勫缓 ReactAgent锛?| 閲嶆瀯鐗堬紙鍔ㄦ€?ChatClient锛?|
|------|--------------------------|--------------------------|
| 鍐呭瓨鍗犵敤 | 1 + N 涓?ReactAgent | 浠?1 涓?ReactAgent |
| 鏂板 skill | 闇€瑕侀噸鍚紙JAR 鍐?classpath锛?| 澶栭儴鐩綍鐑姞杞斤紝鏃犻渶閲嶅惎 |
| 宸ュ叿杩囨护 | skillAgent 鍥哄畾杩囨护 | 姣忔璇锋眰鍔ㄦ€佽繃婊?|
| LLM 鎰熺煡 skills | 姣忎釜 skillAgent 鍙煡閬撹嚜宸辩殑 prompt | defaultAgent 鐪嬪埌瀹屾暣鐩綍娓呭崟 |

**瀛?Agent 闆嗘垚浠ｇ爜**锛堜互 TradeOfficialA2aAgent 涓轰緥锛夛細

```java
@Component
public class TradeOfficialA2aAgent {

    private final ReactAgent defaultAgent;
    private final SubAgentSkillSupport skillSupport;

    public TradeOfficialA2aAgent(ChatModel chatModel,
                                  TradeAgentProperties properties,
                                  TradeTools tradeTools,
                                  SkillRegistry skillRegistry) {
        // 1. 鏋勫缓鍏ㄩ噺宸ュ叿鍥炶皟
        ToolCallback[] allCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tradeTools).build().getToolCallbacks();

        // 2. 鍒濆鍖栨妧鑳芥敮鎸佺粍浠讹紙涓嶅啀闇€瑕?outputKey 鍙傛暟锛?        this.skillSupport = new SubAgentSkillSupport(
                "trade", chatModel, allCallbacks, skillRegistry);

        // 3. 灏嗘妧鑳界洰褰曟竻鍗曟敞鍏?defaultAgent 鐨?system prompt
        String fullSystemPrompt = properties.getSystemPrompt()
                + skillSupport.buildCatalogPrompt();

        // 4. 鏋勫缓鍞竴鐨?defaultAgent锛堝叏閲?tools + 鍚洰褰曟竻鍗曠殑 prompt锛?        this.defaultAgent = ReactAgent.builder()
                .name("trade-agent")
                .description("浜ゆ槗鍙鎬у垎鏋愪笌椋庨櫓璇勪及")
                .model(chatModel)
                .systemPrompt(fullSystemPrompt)  // 鈫?鍖呭惈鎶€鑳界洰褰?                .tools(allCallbacks)
                .outputKey("output")
                .build();
    }

    /**
     * 鍦?A2A handler 涓娇鐢ㄧ殑鏍稿績閫昏緫锛?     *
     * SkillMatchResult match = skillSupport.match(userMessage);
     * ChatClient skillClient = skillSupport.buildSkillClient(match);
     * if (skillClient != null) {
     *     // 鍛戒腑 skill 鈫?杞婚噺 ChatClient 鎵ц锛坰kill prompt + 杩囨护 tools锛?     *     String result = skillClient.prompt().user(userMessage).call().content();
     *     return buildResponse(result);
     * } else {
     *     // 鏈懡涓?鈫?defaultAgent 鍏ㄩ噺鎵ц锛堝寘鍚妧鑳界洰褰曟竻鍗曠殑 system prompt锛?     *     Optional<OverAllState> result = defaultAgent.invoke(userMessage, config);
     *     return buildResponse(result);
     * }
     */
    public ReactAgent getDefaultAgent() { return defaultAgent; }
    public SubAgentSkillSupport getSkillSupport() { return skillSupport; }
}
```

### 2.4 Layer 3 鈥?Fallback 瀹归敊

**璁捐鐩爣**锛氬綋鎰忓浘璇嗗埆璁や负鎵€鏈?skill.md 閮戒笉鍖归厤鏃讹紝鑷姩鍥為€€鍒?defaultAgent 鍏ㄩ噺宸ュ叿妯″紡銆?
**瀹炵幇鏂瑰紡**锛?- `SkillMatcher.match()` 杩斿洖 `SkillMatchResult.noMatch()` 鏃讹細
  - `SubAgentSkillSupport.buildSkillClient()` 杩斿洖 `null`
  - 璋冪敤鏂圭洿鎺ヤ娇鐢?`defaultAgent.invoke()`锛堝叏閲忓伐鍏?+ 鍚妧鑳界洰褰曟竻鍗曠殑 system prompt锛?  - defaultAgent 鐨?LLM 浠嶇劧鑳界湅鍒版妧鑳界洰褰曟竻鍗曪紝鍙互鑷鍒ゆ柇鏄惁搴旇浣跨敤鏌愪釜鎶€鑳?- 瀹屽叏閫€鍥炲埌鐜版湁琛屼负锛岄浂椋庨櫓

**defaultAgent 濡備綍鎰熺煡鎶€鑳?*锛氬叾 system prompt 鏈熬鍖呭惈浜?`buildCatalogPrompt()` 鐢熸垚鐨勬妧鑳界洰褰曡〃鏍硷紙绾?name + description锛夛紝LLM 鍦ㄦ帹鐞嗘椂鍙互锛?- 鐪嬪埌鐢ㄦ埛闂鍖归厤鏌愪釜鎶€鑳界殑 description 鈫?鑷富鎸夎鎶€鑳界殑鎵ц閫昏緫澶勭悊
- 鍗充娇 Java 灞傜殑鍏抽敭璇嶅尮閰嶆病鍛戒腑锛孡LM 灞傞潰鐨勮涔夊尮閰嶄粛鏈夋満浼氱敓鏁?
---

## 涓夈€佹牳蹇冪粍浠惰瑙?
### 3.1 鏂囦欢缁撴瀯

```
common/src/main/java/com/bkanent/common/skill/
鈹溾攢鈹€ SkillDefinition.java          # 鎶€鑳藉畾涔夋ā鍨嬶紙浠?YAML frontmatter 瑙ｆ瀽锛?鈹斺攢鈹€ SkillMatchResult.java         # 鍖归厤缁撴灉锛坰kill + score + strategy锛?
agent-service/src/main/java/com/bkanent/agent/skill/
鈹溾攢鈹€ SkillFileLoader.java          # 瑙ｆ瀽 classpath + 澶栭儴鐩綍鐨?skill.md
鈹溾攢鈹€ SkillRegistry.java            # 鎶€鑳芥敞鍐屼腑蹇冿紝鏀寔鐑姞杞?鈹溾攢鈹€ SkillMatcher.java             # 澶氱瓥鐣ュ尮閰嶅櫒锛坘eyword 鈫?semantic 鈫?none锛?鈹溾攢鈹€ SkillAwareToolProvider.java   # 鍔ㄦ€佸伐鍏疯繃婊ゅ櫒锛氭寜 skill.tools 杩囨护 ToolCallback[]
鈹溾攢鈹€ SkillFileWatcher.java         # 鏂囦欢绯荤粺鐩戞帶锛宻kill.md 鍙樻洿鍗宠Е鍙戠儹鍔犺浇
鈹溾攢鈹€ SupervisorSkillService.java   # Supervisor 鐭ヨ瘑娉ㄥ叆鏈嶅姟
鈹溾攢鈹€ SubAgentSkillSupport.java     # 瀛?Agent 鎶€鑳界粍浠讹細match 鈫?buildSkillClient锛堝姩鎬?ChatClient锛?鈹斺攢鈹€ SkillMatchNode.java           # 鎻掑叆 planning graph 鐨勮妭鐐?
agent-service/src/main/java/com/bkanent/agent/config/
鈹斺攢鈹€ SkillConfiguration.java       # Spring Bean 瑁呴厤

鎶€鑳芥枃浠讹紙skill.md锛屽悇鏈嶅姟鑷繁鐨?classpath 涓嬶級:
agent-service/src/main/resources/skills/supervisor/
鈹斺攢鈹€ real-estate-knowledge.md      # Supervisor 鐭ヨ瘑鎶€鑳?business-service/src/main/resources/skills/trade/
鈹斺攢鈹€ kpi-report.md                 # Trade 瀛?Agent 鎿嶄綔鎶€鑳?marketing-content-service/src/main/resources/skills/marketing/
鈹斺攢鈹€ copy-generation.md            # Marketing 瀛?Agent 鎿嶄綔鎶€鑳?compare-engine-service/src/main/resources/skills/compare/
鈹斺攢鈹€ listing-comparison.md         # Compare 瀛?Agent 鎿嶄綔鎶€鑳?```

### 3.2 SkillDefinition 鈥?鎶€鑳藉畾涔夋ā鍨?
浠?skill.md 鏂囦欢鐨?YAML frontmatter 瑙ｆ瀽鍑虹殑缁撴瀯鍖栨ā鍨嬶細

```java
public record SkillDefinition(
    String name,              // 鍞竴鏍囪瘑锛屽 "trade-kpi-report"
    String description,       // 浜虹被鍙鎻忚堪锛岀敤浜庡尮閰嶅拰灞曠ず
    String domain,            // 鎵€灞為鍩燂細trade, compare, marketing, supervisor...
    List<String> triggerKeywords,  // 瑙﹀彂鍏抽敭璇嶏紝鐢ㄤ簬蹇€熷尮閰?    List<String> tools,       // 鍖归厤鍚庡姞杞界殑 tool 鍚嶇О鍒楄〃锛堢┖ = 鍏ㄩ噺锛?    String systemPrompt,      // skill.md 涓?--- 涔嬪悗鐨?Markdown 姝ｆ枃
    int priority,            // 浼樺厛绾э紝澶?skill 鍚屾椂鍛戒腑鏃堕€夋嫨鏈€楂樼殑
    boolean supervisorSkill   // true = supervisor 鐭ヨ瘑鎶€鑳?)
```

### 3.3 SkillFileLoader 鈥?skill.md 瑙ｆ瀽鍣?
鎵弿 `classpath:skills/**/*.md` 鍜屽彲閫夌殑澶栭儴鐩綍锛岃В鏋?YAML frontmatter + Markdown body銆?
鏀寔涓ょ鍔犺浇婧愶細
1. **classpath**锛欽AR 鍐呯殑 `skills/` 鐩綍锛岃窡闅忛儴缃插寘鍙戝竷
2. **澶栭儴鐩綍**锛堝彲閫夛級锛氶€氳繃 `agent.skills.external-dir` 閰嶇疆鐨勬枃浠剁郴缁熻矾寰勶紝澶栭儴鐩綍鐨勫悓鍚嶆枃浠朵細瑕嗙洊 classpath 鐗堟湰鈥斺€旇繖鏄儹鍔犺浇鐨勫熀纭€

**skill.md 鏂囦欢鏍煎紡瑙勮寖**锛?
```yaml
---
name: trade-kpi-report           # 蹇呭～锛屽敮涓€鏍囪瘑
description: 鐢熸垚KPI缁╂晥鎶ュ憡      # 蹇呭～锛岀敤浜庡尮閰嶅拰灞曠ず
domain: trade                    # 蹇呭～锛屾墍灞為鍩?trigger_keywords:                # 鍙€夛紝鐢ㄤ簬鍏抽敭璇嶅尮閰?  - KPI
  - 缁╂晥
  - 鏈堝害鎶ュ憡
  - 涓氱哗鏁版嵁
tools:                           # 鍙€夛紝鍖归厤鍚庡彧鍔犺浇杩欎簺 tool锛堢┖=鍏ㄩ儴锛?  - queryMonthlyKpis
  - calculateKpiAssessments
  - queryRankings
priority: 10                     # 鍙€夛紝浼樺厛绾э紙榛樿5锛夛紝澶歴kill鍚屾椂鍛戒腑鏃堕€夋嫨鏈€楂樼殑
supervisor_skill: false          # 鍙€夛紝true=supervisor鐭ヨ瘑鎶€鑳斤紙榛樿false锛?---
# System Prompt锛?-- 涔嬪悗鐨?Markdown 姝ｆ枃锛?杩欓噷鏄粰 LLM 鐨勮缁嗘寚浠わ細鎵ц姝ラ銆佽緭鍑烘牸寮忋€佹敞鎰忎簨椤圭瓑銆?```

**瑙ｆ瀽閫昏緫**锛?
```
1. 璇诲彇 .md 鏂囦欢鍐呭
2. 妫€鏌ユ槸鍚︿互 "---" 寮€澶?3. 鎵惧埌绗簩涓?"---" 鍒嗛殧绗?4. 涓棿閮ㄥ垎 鈫?SnakeYAML 瑙ｆ瀽涓?Map<String, Object>
5. 鍚庡崐閮ㄥ垎 鈫?浣滀负 systemPrompt
6. 鏋勫缓 SkillDefinition 瀵硅薄
```

### 3.4 SkillRegistry 鈥?鎶€鑳芥敞鍐屼腑蹇?
```java
@Component
public class SkillRegistry {
    // 鏍稿績绱㈠紩
    private final List<SkillDefinition> skills;              // 鍏ㄩ噺鎶€鑳?    private final Map<String, SkillDefinition> byName;       // 鎸夊悕绉扮储寮?    private final Map<String, List<SkillDefinition>> byDomain; // 鎸夐鍩熺储寮?
    // 鍏抽敭鏌ヨ鏂规硶
    public List<SkillDefinition> findByDomain(String domain);     // 鑾峰彇鏌愰鍩熺殑鍏ㄩ儴鎶€鑳?    public List<SkillDefinition> findSupervisorSkills();          // 鑾峰彇Supervisor鐭ヨ瘑鎶€鑳?    public List<SkillDefinition> findOperationalSkills(String domain); // 鑾峰彇鎿嶄綔鎶€鑳?鎺掗櫎鐭ヨ瘑鎶€鑳?
    public void reload();  // 鏀寔杩愯鏃剁儹鍔犺浇
}
```

### 3.5 SkillMatcher 鈥?澶氱瓥鐣ュ尮閰嶅櫒

**鍖归厤绛栫暐**锛堟寜浼樺厛绾ч€掑噺锛夛細

| 绛栫暐 | 瀹炵幇鏂瑰紡 | 閫傜敤鍦烘櫙 | 鍑嗙‘搴?| 寤惰繜 |
|------|---------|---------|--------|------|
| Keyword | 妫€鏌?trigger_keywords 鍦ㄧ敤鎴锋秷鎭腑鐨勫懡涓巼 | 鏄庣‘鐨勪笟鍔″叧閿瘝 | 涓?| 鏋佷綆 |
| Semantic锛堥鐣欙級 | 灏?skill description 鍚戦噺鍖栧瓨鍏?Milvus锛屼綑寮︾浉浼煎害鍖归厤 | 璇箟鐩歌繎浣嗙敤璇嶄笉鍚?| 楂?| 浣?|
| LLM锛堥鐣欙級 | 璋冪敤杞婚噺 LLM 鍋氭剰鍥惧垎绫伙紝杈撳嚭 skill name | 妯＄硦鎰忓浘 | 鏈€楂?| 楂?|

**褰撳墠瀹炵幇 鈥?鍏抽敭璇嶅尮閰嶇畻娉?*锛?
```
computeKeywordScore(userMessage, skill):
    lowerMessage = userMessage.toLowerCase()
    matched = 0
    for keyword in skill.triggerKeywords:
        if lowerMessage.contains(keyword.toLowerCase()):
            matched++
    return matched / skill.triggerKeywords.size()

# 绀轰緥锛?userMessage = "甯垜鐢熸垚涓婁釜鏈堢殑KPI缁╂晥鎶ュ憡"
skill trigger_keywords = [KPI, 缁╂晥, 鏈堝害鎶ュ憡, 瀛ｅ害鎶ュ憡, 涓氱哗鏁版嵁, 鍏抽敭鎸囨爣, 鎴愪氦閲? 鎴愪氦棰漖

鍛戒腑: "KPI" 鉁? "缁╂晥" 鉁?鈫?matched = 2
score = 2/8 = 0.25

# 闃堝€煎垽鏂細
score >= 0.95 鈫?楂樼疆淇″害鍖归厤锛團ULL_MATCH锛?score >= 0.60 涓?< 0.95 鈫?涓疆淇″害鍖归厤锛圥ARTIAL_MATCH锛?score < 0.60 鈫?鏃犲尮閰?鈫?Fallback
```

**闃堝€煎彲閰嶇疆**锛氶€氳繃璋冩暣 `MIN_CONFIDENCE_THRESHOLD`锛堥粯璁?0.6锛夋帶鍒剁伒鏁忓害銆傞槇鍊艰秺楂橈紝鍖归厤瓒婁繚瀹堬紝瓒婂鏄撹蛋 Fallback銆?
### 3.6 SkillAwareToolProvider 鈥?鍔ㄦ€佸伐鍏疯繃婊?
```java
public class SkillAwareToolProvider implements ToolCallbackProvider {
    private final ToolCallback[] allCallbacks;           // 鍏ㄩ噺宸ュ叿
    private final Map<String, ToolCallback> callbacksByName; // name 鈫?callback 绱㈠紩
    private final Map<String, ToolCallback[]> skillCache;    // 缂撳瓨: skillName 鈫?filtered callbacks

    /**
     * 鏍规嵁鍖归厤缁撴灉瑙ｆ瀽鏈夋晥鐨勫伐鍏烽泦锛?     * - 鍖归厤鍒?skill 涓?skill.tools 闈炵┖ 鈫?鍙繑鍥炲垪鍑虹殑宸ュ叿
     * - 鏃犲尮閰嶆垨 skill.tools 涓虹┖ 鈫?杩斿洖鍏ㄩ噺宸ュ叿锛坒allback锛?     */
    public ToolCallbackProvider resolveFor(SkillMatchResult match) {
        if (!match.isMatched() || match.skill().tools().isEmpty()) {
            return this; // fallback: 鍏ㄩ噺宸ュ叿
        }
        // 浠庣紦瀛樿幏鍙栨垨鏋勫缓杩囨护鍚庣殑宸ュ叿鏁扮粍
        ToolCallback[] filtered = skillCache.computeIfAbsent(
            match.skill().name(),
            name -> match.skill().tools().stream()
                .map(callbacksByName::get)
                .filter(Objects::nonNull)
                .toArray(ToolCallback[]::new)
        );
        return () -> filtered;
    }
}
```

### 3.7 SupervisorSkillService 鈥?Supervisor 鐭ヨ瘑鏈嶅姟

```java
@Service
public class SupervisorSkillService {
    // 娉ㄥ叆鍒?sharedContext 鐨?key
    public static final String SKILL_KNOWLEDGE_KEY = "skillKnowledge";
    public static final String MATCHED_SKILL_KEY = "matchedSkillName";

    /**
     * 灏嗗尮閰嶅埌鐨?supervisor 鐭ヨ瘑鎶€鑳芥敞鍏ュ埌 planning context锛?     * sharedContext.skillKnowledge = skill 鐨?Markdown 姝ｆ枃锛堣涓氱煡璇嗭級
     * sharedContext.matchedSkillName = skill 鐨勫悕绉?     * sharedContext.skillKeywords = [瑙﹀彂鍏抽敭璇嶅垪琛╙
     */
    public Map<String, Object> enrichContext(Map<String, Object> existingContext, SkillMatchResult match);
}
```

### 3.8 SkillMatchNode 鈥?璁″垝鍥捐妭鐐?
鎻掑叆鍦?`LoadSession` 鍜?`LlmIntentPlan` 涔嬮棿鐨勬柊鑺傜偣锛?
```java
@Component
public class SkillMatchNode implements SupervisorGraphNode {
    @Override
    public SupervisorGraphState apply(SupervisorGraphState state) {
        // 1. 濡傛灉娌℃湁 supervisor 鎶€鑳斤紝閫忎紶
        if (!supervisorSkillService.hasSupervisorSkills()) {
            return state;
        }
        // 2. 鍖归厤鐢ㄦ埛娑堟伅
        SkillMatchResult match = supervisorSkillService.matchKnowledge(state.userMessage());
        // 3. 娉ㄥ叆鐭ヨ瘑涓婁笅鏂?        if (match.isMatched()) {
            Map<String, Object> enriched = supervisorSkillService.enrichContext(
                state.sharedContext(), match);
            return state.withSharedContext(enriched);
        }
        return state;
    }
}
```

### 3.9 SubAgentSkillSupport 鈥?瀛?Agent 鍙鐢ㄧ粍浠讹紙閲嶆瀯鐗堬級

姣忎釜瀛?Agent 瀹炰緥鍖栦竴涓?`SubAgentSkillSupport`銆傛牳蹇冭璁″彉鏇达細**涓嶅啀棰勫缓 ReactAgent锛屾敼涓烘寜闇€鍔ㄦ€佹瀯寤鸿交閲?ChatClient**銆?
```java
public class SubAgentSkillSupport {
    private final String domain;
    private final ChatModel chatModel;                    // 鍏变韩鍗曚緥锛屼笉閲嶅鍒涘缓
    private final Map<String, ToolCallback> toolIndex;     // name 鈫?ToolCallback 绱㈠紩
    private final SkillRegistry registry;
    private final SkillMatcher matcher;

    /**
     * 鏋勫缓杞婚噺鎶€鑳芥竻鍗?prompt锛堜粎 name + description 鐨勮〃鏍硷級銆?     * 灏嗘瀛楃涓茶拷鍔犲埌 defaultAgent 鐨?system prompt 鏈熬锛?     * 璁?LLM 鍦?fallback 妯″紡涓嬩篃鐭ラ亾鏈夊摢浜涙妧鑳藉彲鐢ㄣ€?     *
     * Claude Code 琛屼负瀵圭収锛?     *   "鍙敤鐨勬妧鑳? code-review, security-review, ..."
     */
    public String buildCatalogPrompt();

    /**
     * 鍖归厤鐢ㄦ埛娑堟伅 鈫?杩斿洖 SkillMatchResult
     */
    public SkillMatchResult match(String userMessage);

    /**
     * 涓哄尮閰嶅埌鐨?skill 鍔ㄦ€佹瀯寤?ChatClient銆?     * ChatClient 鍐呯疆 tool calling 寰幆锛堢瓑浠蜂簬 ReAct loop锛夛紝
     * 鍥犳涓嶉渶瑕?ReactAgent銆傛瘡娆¤姹傚嵆鏃舵瀯寤猴紝鏋勫缓鎴愭湰鏋佷綆銆?     *
     * @return skill 涓撳睘 ChatClient锛涙湭鍖归厤杩斿洖 null锛堣皟鐢ㄦ柟鐢?defaultAgent锛?     */
    public ChatClient buildSkillClient(SkillMatchResult match);

    /**
     * 瑙ｆ瀽 systemPrompt锛氬尮閰嶅埌 skill 鈫?skill 鐨勫畬鏁?prompt锛屽惁鍒?鈫?榛樿 prompt
     */
    public String resolveSystemPrompt(SkillMatchResult match, String defaultPrompt);
}
```

**鍏抽敭璁捐瀵规瘮**锛?
| 鏂归潰 | 鏃ц璁★紙棰勫缓 ReactAgent锛?| 鏂拌璁★紙鍔ㄦ€?ChatClient锛?|
|------|--------------------------|--------------------------|
| 鍐呭瓨鍗犵敤 | 1 + N 涓?ReactAgent锛堟瘡涓?skill 涓€涓級 | 浠?1 涓?ReactAgent |
| Skill 鏂板 | 闇€閲嶅惎鏈嶅姟 | 澶栭儴鐩綍鐑姞杞斤紝鏃犻渶閲嶅惎 |
| 璇锋眰澶勭悊 | `skillAgents.get(name)` 鈫?杩斿洖棰勫缓 agent | `ChatClient.builder(chatModel).defaultSystem(...).defaultTools(...).build()` |
| ChatModel | 姣忎釜 ReactAgent 鎸佹湁涓€涓紩鐢?| 鍏ㄦ湇鍔″叡浜竴涓?ChatModel 鍗曚緥 |

**ChatClient 涓轰粈涔堣兘鏇夸唬 ReactAgent**锛?
Spring AI 鐨?`ChatClient` 鍐呯疆浜?tool calling 寰幆锛氬綋 LLM 杩斿洖 tool_call 鏃讹紝ChatClient 鑷姩鎵ц宸ュ叿骞跺皢缁撴灉鍥炴敞缁?LLM锛岀洿鍒?LLM 浜у嚭鏈€缁堟枃鏈洖澶嶃€傝繖鍜?ReactAgent 鐨?ReAct 寰幆绛変环锛屼絾 ChatClient 鏄交閲忕殑涓€娆℃€у璞★紝閫傚悎姣忔璇锋眰鍔ㄦ€佹瀯寤恒€?
**璋冪敤鏂逛娇鐢ㄧず渚?*锛?
```java
// 鍦?A2A handler 涓細
String userMessage = request.userMessage();
SkillMatchResult match = skillSupport.match(userMessage);
ChatClient skillClient = skillSupport.buildSkillClient(match);

if (skillClient != null) {
    // 鍛戒腑 skill 鈫?杞婚噺 ChatClient锛坰kill prompt + 杩囨护鍚庣殑 tools锛?    String result = skillClient.prompt().user(userMessage).call().content();
    return buildResponse(result);
} else {
    // 鏈懡涓?鈫?defaultAgent锛堝叏閲?tools + 鍚洰褰曟竻鍗曠殑 system prompt锛?    Optional<OverAllState> result = defaultAgent.invoke(userMessage, config);
    return buildResponse(result);
}
```

### 3.10 SkillFileWatcher 鈥?鐑姞杞芥枃浠剁洃鎺?
**璁捐鐩爣**锛氬儚 Claude Code 涓€鏍凤紝鏂板涓€涓?skill.md 鏂囦欢鍚庢棤闇€閲嶅惎鏈嶅姟鍗冲彲鐢熸晥銆?
**瀹炵幇鏂瑰紡**锛?- 浣跨敤 Java `WatchService` 鐩戞帶閰嶇疆鐨勫閮ㄦ妧鑳界洰褰曪紙`agent.skills.external-dir`锛?- 妫€娴嬪埌 `.md` 鏂囦欢鐨勫垱寤?淇敼/鍒犻櫎鍚庯紝绛夊緟娑堟姈锛?绉掞級锛岃嚜鍔ㄨЕ鍙?`SkillRegistry.reload()`
- 澶栭儴鐩綍鐨勫悓鍚嶆枃浠朵細瑕嗙洊 classpath 鐗堟湰锛屾柟渚胯皟璇曞拰鐑洿鏂?- 濡傛灉涓嶉厤缃閮ㄧ洰褰曪紝鍒欎粎浠?classpath 鍔犺浇锛堢敓浜х幆澧冭窡闅忛儴缃插寘鍗冲彲锛?
**閰嶇疆**锛?
```yaml
# application.yml
agent:
  skills:
    external-dir: /path/to/external/skills   # 澶栭儴鎶€鑳界洰褰曪紙鍙€夛紝涓嶉厤鍒欏彧鐢?classpath锛?    watch-enabled: true                       # 鏄惁鍚敤鏂囦欢鐩戞帶锛堥粯璁?true锛?```

**宸ヤ綔鍘熺悊**锛?
```
澶栭儴鐩綍 /path/to/external/skills/
  鈹溾攢鈹€ trade/
  鈹?  鈹斺攢鈹€ new-skill.md     鈫?鏂板 鈫?WatchService 妫€娴嬪埌 CREATE 浜嬩欢
  鈹斺攢鈹€ compare/
      鈹斺攢鈹€ listing-comparison.md 鈫?淇敼 鈫?WatchService 妫€娴嬪埌 MODIFY 浜嬩欢
                    鈹?                    鈻?娑堟姈 2 绉?         SkillRegistry.reload()
                    鈹?                    鈻?    SkillRegistry 閲嶆柊鍔犺浇鎵€鏈?skill 鏂囦欢锛坈lasspath + 澶栭儴鐩綍锛?                    鈹?                    鈻?    SubAgentSkillSupport 涓嬫 match() 鑷姩浣跨敤鏂扮洰褰?```

**娑堟姈鏈哄埗**锛氭壒閲忔枃浠跺彉鏇达紙濡傚鍒朵竴鏁翠釜鐩綍鐨勬妧鑳芥枃浠讹級浼氬湪鏈€鍚庝竴娆″彉鏇村悗 2 绉掕Е鍙戜竴娆?reload锛岄伩鍏嶅弽澶嶉噸寤恒€?
**鎵嬪姩閲嶈浇**锛氫篃鍙€氳繃 REST 绔偣鎵嬪姩瑙﹀彂锛?
```java
@RestController
public class SkillAdminController {
    @PostMapping("/admin/skills/reload")
    public Map<String, Object> reload() {
        watcher.reloadNow();
        return Map.of("status", "ok", "skillCount", registry.size());
    }
}
```

---

## 鍥涖€侀泦鎴愭寚鍗?
### 4.1 Supervisor 渚ч泦鎴?
**Step 1锛氫慨鏀?`OfficialPlanningGraphFactory`**锛屾彃鍏?SkillMatchNode锛?
```java
// 鍦?planning graph 鐨勮妭鐐瑰簭鍒椾腑鎻掑叆 SKILL_MATCH
stateGraph.addNode("SKILL_MATCH", skillMatchNode);
stateGraph.addEdge("LOAD_SESSION", "SKILL_MATCH");
stateGraph.addEdge("SKILL_MATCH", "LLM_INTENT_PLAN");
```

**Step 2锛氫慨鏀?`AgentChatService`**锛屾敮鎸佸姩鎬佸伐鍏峰姞杞斤細

```java
@Service
public class AgentChatService {
    private final SkillAwareToolProvider skillAwareToolProvider;
    private final SkillMatcher skillMatcher;

    /**
     * 浣跨敤鎶€鑳芥劅鐭ョ殑宸ュ叿闆嗚繘琛屽璇濄€?     * 褰?domain 宸茬‘瀹氭椂锛屽彧鍔犺浇璇?domain 鐩稿叧鐨勫伐鍏枫€?     */
    public String callWithSkillContext(String systemPrompt, String userPrompt,
                                        String resolvedDomain) {
        SkillMatchResult match = skillMatcher.match(userPrompt, resolvedDomain);
        ToolCallbackProvider effectiveTools = skillAwareToolProvider.resolveFor(match);

        ChatClient dynamicClient = ChatClient.builder(chatModel)
                .defaultToolCallbacks(effectiveTools)
                .build();

        return dynamicClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .options(defaultOptions())
                .call()
                .content();
    }
}
```

### 4.2 瀛?Agent 渚ч泦鎴愶紙閲嶆瀯鐗堬級

**Step 1锛氫慨鏀瑰悇瀛?Agent 鐨?A2A 瀹氫箟绫?*锛屼互 TradeOfficialA2aAgent 涓轰緥锛?
```java
@Component
public class TradeOfficialA2aAgent {
    private final ReactAgent defaultAgent;
    private final SubAgentSkillSupport skillSupport;

    public TradeOfficialA2aAgent(ChatModel chatModel,
                                  TradeAgentProperties properties,
                                  TradeTools tradeTools,
                                  SkillRegistry skillRegistry) {
        // 1. 鏋勫缓鍏ㄩ噺宸ュ叿鍥炶皟
        ToolCallback[] allCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tradeTools).build().getToolCallbacks();

        // 2. 鍏堟瀯寤烘妧鑳芥敮鎸佺粍浠讹紙涓嶅啀闇€瑕?outputKey锛?        this.skillSupport = new SubAgentSkillSupport(
                "trade", chatModel, allCallbacks, skillRegistry);

        // 3. 灏嗘妧鑳界洰褰曟竻鍗曟嫾鎺ヨ繘 system prompt
        String fullSystemPrompt = properties.getSystemPrompt()
                + skillSupport.buildCatalogPrompt();

        // 4. 鏋勫缓鍞竴鐨?defaultAgent锛堝叏閲?tools + 鍚洰褰曟竻鍗曠殑 prompt锛?        this.defaultAgent = ReactAgent.builder()
                .name("trade-agent")
                .model(chatModel)
                .systemPrompt(fullSystemPrompt)  // 鈫?鎶€鑳界洰褰曟竻鍗?                .tools(allCallbacks)
                .outputKey("output")
                .build();

        // 娉ㄦ剰锛氫笉鍐嶉渶瑕?warmUp()锛屽洜涓轰笉鍐嶉寤?ReactAgent
    }

    public ReactAgent getDefaultAgent() { return defaultAgent; }
    public SubAgentSkillSupport getSkillSupport() { return skillSupport; }
}
```

**Step 2锛氫慨鏀瑰悇瀛?Agent 鐨?A2A Server handler**锛屽湪涓や釜璺緞涔嬮棿閫夋嫨锛?
```java
// 鍦ㄥ悇 Agent 鐨?A2A 璇锋眰澶勭悊閫昏緫涓?public AgentTaskInvokeResponse handleRequest(AgentTaskInvokeRequest request) {
    String userMessage = request.userMessage();

    // 鎶€鑳藉尮閰?    SkillMatchResult match = skillSupport.match(userMessage);
    ChatClient skillClient = skillSupport.buildSkillClient(match);

    if (skillClient != null) {
        // 璺緞 A锛氬懡涓?skill 鈫?鍔ㄦ€?ChatClient锛坰kill prompt + 杩囨护鍚庣殑 tools锛?        String result = skillClient.prompt().user(userMessage).call().content();
        return buildResponse(result);
    } else {
        // 璺緞 B锛氭湭鍛戒腑 鈫?defaultAgent锛堝叏閲?tools + 鍚洰褰曟竻鍗曠殑 system prompt锛?        Optional<OverAllState> result = defaultAgent.invoke(userMessage, runnableConfig);
        return buildResponse(result);
}
```

### 4.3 鏂板鎶€鑳界殑鎿嶄綔娴佺▼

**无需改代码，只需新增一个 Markdown 文件。**
浠ユ柊澧?Trade Agent 鐨?浜ゆ槗椋庨櫓璇勪及"鎶€鑳戒负渚嬶細

1. 鍒涘缓鏂囦欢锛歚business-service/src/main/resources/skills/trade/risk-assessment.md`

```yaml
---
name: trade-risk-assessment
description: 瀵规埧浜т氦鏄撹繘琛岄闄╄瘎浼帮紝璇嗗埆璧勯噾銆佷骇鏉冦€佹硶寰嬬瓑椋庨櫓
domain: trade
trigger_keywords:
  - 椋庨櫓
  - 璧勯噾鐩戠
  - 浜ф潈
  - 鎶垫娂
  - 鏌ュ皝
  - 杩濈害
tools:
  - queryListingTurnover
  - getStoreDashboard
  - queryMonthlyKpis
priority: 8
supervisor_skill: false
---
# 浜ゆ槗椋庨櫓璇勪及

## 鎵ц姝ラ
1. 璋冪敤 queryListingTurnover 鑾峰彇鎴挎簮浜ゆ槗鍘嗗彶
2. 璋冪敤 getStoreDashboard 鑾峰彇闂ㄥ簵鏁版嵁
3. 缁煎悎鍒嗘瀽椋庨櫓绛夌骇

## 杈撳嚭鏍煎紡
...
```

2. 灏嗘枃浠舵斁鍏ュ閮ㄦ妧鑳界洰褰曪紙濡傚凡閰嶇疆 `agent.skills.external-dir`锛夛紝`SkillFileWatcher` 鑷姩妫€娴嬪苟鐑姞杞斤紱鎴栬皟鐢?`POST /admin/skills/reload` 鎵嬪姩閲嶈浇銆俿kill 绔嬪嵆鐢熸晥锛屾棤闇€閲嶅惎鏈嶅姟銆?
---

## 浜斻€佸叧閿璁″喅绛?
| 鍐崇瓥 | 閫夋嫨 | 鐞嗙敱 |
|------|------|------|
| Skills 瀛樺偍浣嶇疆 | `classpath:skills/{domain}/*.md` + 鍙€夊閮ㄧ洰褰?| classpath 鐢ㄤ簬閮ㄧ讲鍖咃紝澶栭儴鐩綍鐢ㄤ簬鐑姞杞藉拰璋冭瘯 |
| 瑙ｆ瀽鏃舵満 | 鍚姩鏃跺叏閲忓姞杞?+ 澶栭儴鐩綍鍙樻洿鏃跺閲忛噸杞?| 鍚姩鍔犺浇淇濊瘉鍩虹鍙敤锛岀儹鍔犺浇淇濊瘉鐏垫椿鎬?|
| 鍖归厤绛栫暐锛堝綋鍓嶏級 | 鍏抽敭璇嶅尮閰?| 闆跺欢杩燂紝鏃犻渶棰濆鍩虹璁炬柦 |
| 鍖归厤绛栫暐锛堥鐣欙級 | Milvus 鍚戦噺鐩镐技搴?| 宸叉湁 Milvus 鍩虹璁炬柦锛岃涔夊尮閰嶆墿灞曟垚鏈綆 |
| Tool 杩囨护鏂瑰紡 | 鍔ㄦ€佹瀯寤?ChatClient | ChatClient 杞婚噺锛堜竴娆¤姹傚嵆寮冿級锛孋hatModel 鍏变韩鍗曚緥锛屾棤闇€棰勫缓缂撳瓨 |
| Supervisor 鐭ヨ瘑鎶€鑳?| 绾枃鏈敞鍏ワ紝涓嶆敼宸ュ叿闆?| Supervisor 鍙渶璺敱鍐崇瓥锛屼笉闇€瑕佽皟鐢ㄤ笟鍔″伐鍏?|
| 瀛?Agent 璺?Agent 宸ュ叿寮曠敤 | v1 涓嶆敮鎸?| 璺?Agent = A2A 璋冪敤锛屾槸鍙︿竴涓?Agent 鐨勮亴璐?|
| Supervisor 宸ュ叿闆嗙瓥鐣?| 鏂规 B锛堟寜 domain 鍒嗙粍锛?| 淇濈暀 `combinedToolCallbackProvider`锛岄€氳繃 `SkillAwareToolProvider` 鍋氬姩鎬佽繃婊?|
| 瀹归敊绛栫暐 | 鍏ㄩ儴 skill 鍔犺浇澶辫触 鈫?绛変环浜庢棤 skill 妯″紡 | 涓嶅奖鍝嶇幇鏈変笟鍔★紝闆堕闄╁崌绾?|
| 鐑姞杞?| WatchService 鏂囦欢鐩戞帶锛岃嚜鍔ㄨЕ鍙?| 绫讳技 Claude Code锛屾柊澧?skill.md 鏂囦欢鍗崇敓鏁堬紝鏃犻渶閲嶅惎 |

---

## 鍏€佷笌鐜版湁鏋舵瀯鐨勫鐓?
| 鏈郴缁熺幇鏈夎兘鍔?| Skills 鏈哄埗瀵瑰簲 | 鐘舵€?|
|---------------|----------------|------|
| Supervisor Planner 鍋氭剰鍥捐矾鐢?| Router / 鎰忓浘鍒嗙被鍣?| 鉁?宸叉湁 |
| 瀛?Agent 鐙珛宸ュ叿闆嗭紙鍚勮嚜鐨?MCP tools锛?| Skill 涓撳睘宸ュ叿闆?| 鉁?宸叉湁 |
| A2A 鍗忚璋冨害 | Skill 璋冪敤鍗忚 | 鉁?宸叉湁 |
| **Supervisor 灞傚伐鍏烽殧绂?* | 鎸?domain 鍔ㄦ€佸姞杞藉伐鍏?| 鉂?鈫?鉁?鏈瀹炵幇 |
| **杞婚噺鎰忓浘 鈫?鎶€鑳芥槧灏?* | SkillMatcher 鍏抽敭璇嶅尮閰?| 鉂?鈫?鉁?鏈瀹炵幇 |
| **瀛?Agent 鎶€鑳藉姩鎬佸姞杞?* | SubAgentSkillSupport.buildSkillClient() | 鉂?鈫?鉁?鏈瀹炵幇 |
| **Supervisor 鐭ヨ瘑娉ㄥ叆** | SupervisorSkillService + SkillMatchNode | 鉂?鈫?鉁?鏈瀹炵幇 |
| **skill.md 澹版槑寮忔妧鑳藉畾涔?* | YAML frontmatter + Markdown | 鉂?鈫?鉁?鏈瀹炵幇 |
| **鏂囦欢鐑姞杞?* | SkillFileWatcher锛圵atchService锛?| 鉂?鈫?鉁?鏈瀹炵幇 |
| **鎶€鑳界洰褰曟竻鍗?* | buildCatalogPrompt() 娉ㄥ叆 defaultAgent prompt | 鉂?鈫?鉁?鏈瀹炵幇 |

---

## 涓冦€佸悗缁墿灞曟柟鍚?
### 7.1 璇箟鍖归厤鎺ュ叆 Milvus

褰撳墠 `SkillMatcher` 宸查鐣欒涔夊尮閰嶆墿灞曠偣銆傛帴鍏ユ柟寮忥細

1. 灏嗘墍鏈?skill 鐨?`description` 鍚戦噺鍖栧瓨鍏?Milvus
2. 鐢ㄦ埛娑堟伅鍚戦噺鍖?鈫?Milvus 鐩镐技搴︽绱?top-K skills
3. 缁撳悎鍏抽敭璇嶅尮閰嶇殑鍒嗘暟鍋氬姞鏉冭瀺鍚?4. 闆舵柊澧炲熀纭€璁炬柦鎴愭湰锛堝凡鏈?Milvus锛?
### 7.2 Skill 闂翠緷璧栦笌缂栨帓

鍏佽 skill.md 涓０鏄庝緷璧栧叧绯伙細

```yaml
depends_on: [trade-risk-assessment]   # 蹇呴』鍏堝畬鎴愰闄╄瘎浼?next_skills: [contract-review]        # 瀹屾垚鍚庡缓璁墽琛屽悎鍚屽鏌?```

Supervisor Planner 鍙牴鎹緷璧栧叧绯昏嚜鍔ㄧ紪鎺掑姝ュ伐浣滄祦銆?
### 7.3 Skill 鍔ㄦ€佺粍鍚?
Supervisor 灞傞潰鐨勫伐鍏峰垎缁勬寜 domain 鍋氳嚜鍔ㄨ矾鐢憋細

```java
// domain 鈫?tool group 鏄犲皠
Map<String, List<String>> domainToolGroups = Map.of(
    "trade",     List.of("queryMonthlyKpis", "..."),  // business-mcp-server
    "compare",   List.of("compareListings"),           // compare-mcp-server
    "marketing", List.of("publishMarketingContent")    // marketing-mcp-server
);

// Planner 鍏堢‘瀹?domain 鈫?鍙姞杞借 domain 鐨?MCP 宸ュ叿
```

### 7.4 鎶€鑳界増鏈鐞嗕笌鐏板害鍙戝竷

缁撳悎鐜版湁鐨?`GrayReleaseProperties`锛屾敮鎸侊細
- 鎶€鑳界増鏈爣璁帮細`version: v2`
- A/B 娴嬭瘯锛氶儴鍒嗙敤鎴蜂娇鐢ㄦ柊鐗?skill锛岄儴鍒嗕娇鐢ㄦ棫鐗?- 鍥炴粴锛氬揩閫熷垏鎹㈠埌鏃х増 skill 鏂囦欢
