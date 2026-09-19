"""Rebuild the source-reviewed architecture atlas: Python 3 + Pillow, Windows CJK fonts."""
from pathlib import Path
from html import escape
import hashlib
import json
import math
import subprocess
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent
ROOT = OUT.parent.parent
FONT = Path('C:/Windows/Fonts/msyh.ttc')
BOLD = Path('C:/Windows/Fonts/msyhbd.ttc')
W, H = 1800, 1220
INK, MUTED, BG = '#142b45', '#52677d', '#f3f6fa'
COLORS = {'blue': ('#eaf1ff', '#336ddd'), 'green': ('#e5f5ef', '#168568'),
          'purple': ('#f1eaff', '#8652be'), 'orange': ('#fff1dc', '#bd7a19')}
POS = [(90,230),(665,230),(1240,230),(1240,500),(665,500),(90,500),(90,770),(665,770),(1240,770)]
GRAPHS = []

def graph(slug, title, subtitle, nodes, notes, sources, edges=None, loop=False):
    GRAPHS.append(dict(slug=slug,title=title,subtitle=subtitle,nodes=nodes,notes=notes,
                       sources=sources,edges=edges if edges is not None else [(i,i+1,'') for i in range(len(nodes)-1)],loop=loop))

def n(title, body, color='blue'):
    return [title,body,color]

graph('01-service-architecture','服务架构｜模块与职责','16 个 Maven 模块 · 8 个官方 A2A 子 Agent · 以当前工作区源码为准',[
 n('访问入口','业务前端 / API 调用方\nHTTP 请求与 SSE 订阅'),
 n('gateway + auth-service','统一路由与认证权限\n业务 API / Agent API'),
 n('agent-service','Supervisor · 官方 Graph\nA2A 客户端 / MCP / RAG','purple'),
 n('8 个领域 Agent','listing / compare / marketing / media\ntrade / contract / settlement / notification','purple'),
 n('本地工具与领域服务','ReactAgent → 本地 @Tool\nREST / MCP / Dubbo 为其他入口'),
 n('业务存储与外部接口','MySQL · ES · MinIO\n媒体 / 邮件 / 签章 / 发布 Provider','green'),
 n('其他业务与共享模块','customer-service / promotion-service\ncommon：DTO、RPC 契约'),
 n('状态与公共配置','memory-service：共享记忆\ncommon-config-manager：配置支持','green'),
 n('基础设施与模型','Nacos / Redis / RocketMQ / Milvus\nDeepSeek / DashScope','green')],
 ['箭头表示主调用方向；底部为配套能力，基础设施按各服务实际配置接入。',
  '图表示代码结构，不表示所有服务已启动；外部 Provider 是否可用取决于集成模式。'],
 ['pom.xml','gateway/src/main/resources/application.yml','nacos/agent-service.yaml'],
 [(0,1,'HTTP'),(1,2,'Agent API'),(2,3,'A2A'),(3,4,'进程内'),(4,5,'读写 / 调用'),(6,5,'业务数据'),(7,8,'按配置接入')])

graph('02-supervisor-flow','Supervisor｜规划、路由与执行','官方 Spring AI Alibaba Graph 主链路；审批详细分支见图 11',[
 n('请求与访问控制','任务 / 工作流 / 异步入口\nuserId、sessionId、taskId、traceId'),
 n('PLAN：规划','加载上下文、匹配 Skills\n规则优先；可选 LLM 规划','purple'),
 n('ROUTE：路由决策','选择领域与 Agent\n审批 / 单 Agent / 并行 / 失败','purple'),
 n('执行分支','SINGLE_AGENT 或 PARALLEL_FAN_OUT\n需审批时先进入 APPROVAL_GATE','orange'),
 n('官方 A2A 调用','权限检查 → Agent Card / 实例\nMessage / Task / 流式事件'),
 n('结果汇合','单次结果或 PARALLEL_AGGREGATE\n合并共享上下文与产物引用','green'),
 n('ROUTE_AFTER_EXECUTION','handoff / complete / fail\n由图决定后续执行','purple'),
 n('HANDOFF 或 COMPLETE','交接后再次路由；完成则汇总\n更新记忆、产物与事件'),
 n('最终状态','COMPLETED / FAILED / CANCELED\n审批暂停时返回等待状态','green')],
 ['官方并行节点覆盖 7 个领域：listing、marketing、media、trade、contract、settlement、notification。',
  'compare 已有 A2A Provider，但未列入主图的并行节点映射；单次路由能力与并行能力应区分。'],
 ['agent-service/src/main/java/com/bkanent/agent/graph/official/OfficialSupervisorGraphFactory.java','agent-service/src/main/java/com/bkanent/agent/service/A2aExecutionService.java','nacos/agent-service.yaml'])

AGENTS = [
 ('listing','房源','listing-master-service','listing','Listing',
  '关键词 / 房源 ID / topK\n上游需求与结构化上下文',
  '详情、摘要、关键词检索\ngetListingDetail / searchListingSummaries\nsearchListingsByKeyword / getListingSummary',
  'ListingManagementService\n按所选工具读取详情或查询摘要',
  'MySQL 房源与关联资产\n关键词检索可走 ES BM25\nES 关闭或异常时回退 MySQL',
  '候选房源 / 分数 / 房源详情\n由模型整理为当前任务回答',
  '房源工具未直接调用 Milvus；向量混合检索是 agent-service 的独立 RAG 能力。'),
 ('compare','房源对比','compare-engine-service','compare','Compare',
  '多个房源 ID 或 shareCode\n目标对比维度与上下文',
  'compareListings\ngetSharedReport\n两条工具路径按需选择',
  'CompareAnalysisService\n报告缓存命中则复用\n未命中时通过 Dubbo 读取房源',
  'listing-master-service 房源数据\n报告：内存索引 + 本地持久化\n分享码 / PDF 能力由报告服务提供',
  '对比指标、Markdown 表格、结论\n模型可进一步解读报告',
  '报告服务的 generateAiConclusion 为代码规则生成；外层 ReactAgent 的模型解读是另一层。'),
 ('marketing','营销内容','marketing-content-service','marketing','Marketing',
  '房源 / 文案需求 / 平台\n可选图片与视频 URL',
  'createMarketingContent\nsearchContents / publishContent\n创建、检索或更新发布状态',
  'MarketingAssetService\n文案由模型生成后作为工具参数\n维护内容记录与发布状态',
  'MySQL 内容与素材关联\n检索可用 Elasticsearch\nES 异常或关闭可回退 MySQL',
  'contentId / 内容详情 / 状态\n回传任务结果供后续步骤使用',
  'publishContent 仅更新本地状态，不调用真实发布平台；promotion-service 发布通道是独立能力。'),
 ('media','媒体素材','media-worker-service','media','Media',
  'listingId + prompt\n或已有媒体 taskId',
  'submitMediaTask / getTaskResult\n提交任务或查询结果\n提交后返回可跟踪的任务 ID',
  'MediaGenerationTaskService\n校验 → 创建 QUEUED 任务\n发送 RocketMQ 生成消息',
  '消费者 → 图片生成 Provider\n上传 MinIO → 记录成功 / 失败\ncontentId 存在时 Dubbo 回写素材',
  '查询任务状态与 assetUrls\n未完成时不等同于素材已生成',
  '当前消费者调用 generateListingImages，成功结果 videoUrl 为 null；不能画成已实现的视频流水线。'),
 ('trade','经营分析','business-service','business','Trade',
  '月份 / 门店 / 日期 / 排名范围\n经营问题与分析目标',
  '月度 KPI / 周转 / 门店看板\nKPI 考核 / 业绩排名 / 日工作量\n共 6 个本地工具',
  'BusinessAnalyticsService\n读取业务统计并执行指标计算',
  'MySQL：KPI 与员工工作量\n房源周转、门店快照等统计数据',
  '统计 DTO / 看板 / 排名\nReactAgent 生成经营分析回答',
  'trade-agent 的当前官方工具是经营统计；旧 AgentService 的交易可行性逻辑并非官方 Provider 入口。'),
 ('contract','合同审查','contract-service','contract','Contract',
  'contractId 或类型 / 状态\n合同审查问题与约束',
  'getContractDetail / listContracts\nreviewContractRisks\n读取合同与规则风险检查',
  'ContractManagementService\n获取状态、附件、OCR 摘要\n工具层检查盖章、归档等风险',
  'MySQL：合同与附件记录\n风险依据：印章 / 归档 / OCR\n以及附件数量',
  '风险标记与合同详情\nReactAgent 给出解释与建议',
  '官方 ContractTools 没有签章工具；电子签章 Provider 属于合同管理 API 的其他业务能力。'),
 ('settlement','佣金结算','settlement-service','settlement','Settlement',
  '员工 / 合同 / 房源 / 成交金额\n月份与汇总范围',
  'getSettlementDetail\ncalculateSettlement / getMonthlySummary\ncreatePayoutBatch',
  'SettlementManagementService\n佣金计算、分佣、月度汇总\n或创建发放批次',
  'MySQL：规则与阶梯\n结算、分佣、月汇总、发放批次',
  '结算明细 / 汇总 / 批次结果\n模型解释计算与处理结果',
  '创建发放批次不等于完成真实银行付款；审批由 Supervisor 的规划与审批路径负责。'),
 ('notification','消息通知','notification-service','notification','Notification',
  '收件用户 / 标题 / 内容\n邮件还需要 receiverAddress',
  'sendStationMessage / sendEmailMessage\nlistUserMessages / countUnreadMessages\n发送、查询或统计',
  'NotificationManagementService\n按站内信 / 邮件通道分发\n返回消息 ID 或查询结果',
  'MySQL 通知记录\n邮件：按集成模式选择 Provider\n真实发送依赖外部接口配置',
  '消息 ID / 消息列表 / 未读数\n由 ReactAgent 回传结果',
  '另有独立 RocketMQ 工作流事件消费者：筛选、去重、重试、死信；这条链不经过 ReactAgent。')
]

for i,(key,cn,module,pkg,cls,inp,tool,svc,data,result,note) in enumerate(AGENTS,3):
    base=f'{module}/src/main/java/com/bkanent/{pkg}'
    graph(f'{i:02d}-{key}-agent',f'{cn} Agent｜执行流程',f'{key}-agent  ·  {module}  ·  官方 A2A Provider',[
      n('01  接收子任务',inp),
      n('02  A2A 上下文注入','Supervisor metadata → 模型上下文\n任务 / 会话 / 追踪 / 约束 / 预期输出'),
      n('03  ReactAgent 推理','ChatModel + 领域 systemPrompt\n决定直接回答或调用本地工具','purple'),
      n('04  选择工具',tool,'purple'),
      n('05  领域服务',svc),
      n('06  数据与执行',data,'green'),
      n('07  工具结果返回',result,'green'),
      n('08  整理最终输出','工具结果回填模型，可继续调用\n满足任务要求后生成 output'),
      n('09  返回 Supervisor','官方 A2A 响应 / 任务 / 流\n客户端适配后合并工作流状态')],
      [note,'虚线表示可多轮工具推理；模型也可直接回答。图中工具是可选分支，不代表每次全部执行。'],
      [f'{base}/a2a/{cls}OfficialA2aAgent.java',f'{base}/a2a/A2aSupervisorContextInterceptor.java',f'{base}/tool/{cls}Tools.java'],loop=True)

graph('11-approval-resume','审批与恢复｜暂停、决策、继续','审批是 Supervisor Graph 的控制节点；与外部业务 Provider 的执行状态分开',[
 n('ROUTE 判定需审批','规划结果 requireApproval\n进入 APPROVAL_GATE','purple'),
 n('构造审批与检查点','记录 pendingApproval 与图状态\n等待用户决策','green'),
 n('审批回调','/supervisor/approvals/callback\napproved / rejected / terminated','orange'),
 n('校验与决策写回','检查待审批工作流与决策\n审批 claim / 防重复恢复'),
 n('RESUME_DECISION','根据恢复动作选择分支\n不重新盲目执行已完成步骤','purple'),
 n('继续执行','single / parallel\n恢复对应执行路径'),
 n('重新规划','regenerate → PLAN\n携带审批反馈重新生成计划','orange'),
 n('终止或失败','cancel → CANCELED\n无效恢复动作 → FAILED','orange'),
 n('结果持久化与反馈','检查点 / 审计 / 工作流状态\n通过查询或 SSE 呈现','green')],
 ['中间决策向不同分支跳转：继续、重新规划、终止/失败；不是顺序执行三个分支。',
  '图展示已有控制结构，不代表所有写操作都自动触发审批；是否审批取决于规划和路由结果。'],
 ['agent-service/src/main/java/com/bkanent/agent/graph/official/OfficialSupervisorGraphFactory.java','agent-service/src/main/java/com/bkanent/agent/graph/official/OfficialApprovalDecisionGraphFactory.java','sql/migrations/20260918_agent_workflow_approval_claim.sql'],
 [(0,1,''),(1,2,'等待'),(2,3,''),(3,4,''),(4,5,'继续'),(4,7,'终止 / 失败'),(4,6,'重新规划'),(7,8,'写回')])

graph('12-data-flow','数据流｜请求、上下文、产物与记忆','关联主键：userId · sessionId · taskId · traceId · artifactId',[
 n('用户请求','消息、业务参数与身份\nController 校验及权限控制'),
 n('Supervisor 上下文','读取会话 / 偏好 / 约束\n合成计划与共享工作流状态','purple'),
 n('A2A 子任务载荷','用户消息 + metadata\n上下文、约束、预期输出'),
 n('领域 Agent 与工具','模型选择本地工具\n工具参数 → 业务操作'),
 n('业务数据库 / 外部接口','结构化实体与业务 DTO\n媒体文件进入对象存储','green'),
 n('官方 A2A 返回','Message / Task / Artifact / 事件\n客户端映射内部调用结果'),
 n('Supervisor 合并','结构化输出与产物引用\n为下一 Agent 构造最小上下文','purple'),
 n('状态与共享记忆','agent DB：检查点、异步、审计\nmemory-service：会话、产物、交接','green'),
 n('用户输出 / 下一步骤','最终回答、产物引用、状态\n查询 API / SSE / handoff')],
 ['memory-service 通过 HTTP 接入，实体由 MyBatis 写入 MySQL；向量记忆属于独立 Milvus 能力。',
  '产物 ID 与业务内容 ID、媒体 taskId 是不同标识；上游 handoff 可优先携带产物引用。'],
 ['agent-service/src/main/java/com/bkanent/agent/client/OfficialA2aAgentClient.java','agent-service/src/main/java/com/bkanent/agent/memory/HttpMemoryStoreClient.java','memory-service/src/main/java/com/bkanent/memory/controller/MemoryController.java'])

graph('13-rag-flow','RAG｜房源索引与混合检索','agent-service 独立检索能力；并非 Listing ReactAgent 每次必经路径',[
 n('索引入口','POST /agent/rag/listings/index\n按房源 ID 读取权威数据'),
 n('文档与向量','ListingMasterRpcService → 文本\nEmbedding 模型生成向量'),
 n('Milvus 房源集合','房源文本 / sourceId / metadata\n向量索引持久化','green'),
 n('查询入口','POST /agent/rag/listings/query\n查询文本、结构化过滤与 topK'),
 n('双路召回','关键词：Dubbo → ES / MySQL\n向量：Embedding → Milvus','purple'),
 n('候选合并与过滤','以房源 ID 合并候选\n结构化条件与相似度阈值'),
 n('Rerank 重排','ListingRerankService\n按配置调用重排模型','purple'),
 n('构建证据上下文','排序后的房源与分数\n拼接 context 和 matches'),
 n('返回 RAG 响应','检索结果供调用方使用\n回答生成由上层入口决定')],
 ['上排为索引写入链；第二、三排为查询链。查询读 Milvus，不要求每次先重新索引。',
  '当前 Nacos 模板重排模型默认 qwen3-rerank；具体模型和降级行为由配置与实现决定。'],
 ['agent-service/src/main/java/com/bkanent/agent/milvus/listing/ListingMilvusService.java','agent-service/src/main/java/com/bkanent/agent/milvus/listing/BgeRerankService.java','nacos/agent-service.yaml'],
 [(0,1,''),(1,2,'写入'),(3,4,'查询'),(4,5,'合并'),(5,6,''),(6,7,''),(7,8,'')])

graph('14-async-events','异步与事件｜任务执行、流式反馈、通知','三种异步机制分别承担：Supervisor 调度、会话事件、媒体任务',[
 n('Supervisor 异步请求','创建 task / workflow\n立即返回标识供查询'),
 n('数据库任务队列','持久化状态、attempt、lease\nDispatcher 领取与并发限制','green'),
 n('执行官方 Graph / A2A','完成、失败、重试或取消\n更新异步任务与工作流状态','purple'),
 n('SessionStreamEvent','任务进度 / 审批 / 产物 / 完成\n附带 sessionId、taskId、traceId'),
 n('会话事件总线','默认 memory；可选 RocketMQ\n跨实例行为依 provider 配置','orange'),
 n('用户 SSE 与事件审计','订阅会话 / 异步任务事件\n保留诊断与审计信息'),
 n('通知事件消费','RocketMQ 工作流事件\n事件筛选、去重、重试、死信'),
 n('站内通知持久化','NotificationManagementService\n通知记录与消费结果','green'),
 n('独立媒体队列','media_generate_task → Worker\n图片 Provider → MinIO → 状态','green')],
 ['默认 stream.provider=memory；不能把跨服务 RocketMQ 通知画成所有环境默认启用。',
  '媒体队列独立于 Supervisor 数据库调度；媒体执行细节见图 06。'],
 ['agent-service/src/main/java/com/bkanent/agent/service/SupervisorAsyncRuntimeDispatcher.java','agent-service/src/main/java/com/bkanent/agent/stream/RocketMqSessionEventBus.java','notification-service/src/main/java/com/bkanent/notification/service/NotificationWorkflowEventService.java','nacos/agent-service.yaml'],
 [(0,1,''),(1,2,'领取'),(2,3,'发布'),(3,4,''),(4,5,'订阅'),(4,6,'需 MQ'),(6,7,'发送')])

graph('15-skills-tools','Skills 与工具｜当前接入边界','区分已接入的 Supervisor Skills、官方 ReactAgent 工具与独立 MCP 通道',[
 n('Skill 文件','classpath / 外部目录\n声明领域、匹配规则、工具等'),
 n('加载与注册','SkillFileLoader → SkillRegistry\n按名称、领域维护索引'),
 n('Supervisor 匹配','SkillMatchNode / SupervisorSkillService\n为规划提供可用能力信息','purple'),
 n('官方子 Agent Provider','ReactAgent.builder\nsystemPrompt + ChatModel','purple'),
 n('本地工具绑定','MethodToolCallbackProvider\n直接绑定领域 *Tools 对象'),
 n('领域 Service','@Tool 调用领域服务\n返回 DTO / ID / 状态'),
 n('独立 MCP 通道','agent-service MCP 客户端\n发现服务器工具、对话调用'),
 n('业务 MCP Server','各业务模块 *McpTools\n工具能力与本地 Tools 未必相同'),
 n('尚未接入的辅助类','SubAgentSkillSupport 已存在\n未见 8 个官方 Provider 引用','orange')],
 ['上排是 Supervisor Skills 路径，中排是子 Agent 官方工具路径，底排展示独立能力及未接入边界。',
  '不要把 SubAgentSkillSupport 的设计能力视为所有官方子 Agent 已启用的动态 Skills 机制。'],
 ['agent-service/src/main/java/com/bkanent/agent/skill/SkillFileLoader.java','agent-service/src/main/java/com/bkanent/agent/graph/node/SkillMatchNode.java','agent-service/src/main/java/com/bkanent/agent/skill/SubAgentSkillSupport.java','contract-service/src/main/java/com/bkanent/contract/a2a/ContractOfficialA2aAgent.java'],
 [(0,1,''),(1,2,''),(3,4,'绑定'),(4,5,'调用'),(6,7,'MCP')])

graph('16-system-overview','全系统总览｜房地产中台多 Agent 系统','入口 → 编排 → 领域智能体 → 业务执行；共享状态、检索与事件提供支撑',[
 n('用户与统一入口','前端 / HTTP / SSE\ngateway → auth-service'),
 n('Supervisor · agent-service','Graph：规划、路由、并行、交接\n审批恢复、异步调度、权限治理','purple'),
 n('A2A · 8 个 ReactAgent','listing / compare / marketing / media\ntrade / contract / settlement / notification','purple'),
 n('领域工具与业务服务','本地 @Tool → 领域 Service\n另有 REST、MCP、Dubbo 入口'),
 n('数据与业务集成','MySQL / Elasticsearch / MinIO\ncustomer / promotion 支撑业务\n签章、媒体、邮件、平台 Provider','green'),
 n('共享状态与记忆','memory-service：会话 / 产物 / 交接\nagent DB：检查点 / 异步 / 审计\nHTTP 共享记忆访问','green'),
 n('检索与模型','RAG：关键词 + Milvus + 重排\nDeepSeek ChatModel / Embedding\n模型与 Provider 均受配置控制','purple'),
 n('事件与反馈','SSE + 可选 RocketMQ 会话总线\n通知消费 / 独立媒体生成队列\nRedis：可选限流等基础能力','green'),
 n('配置与公共基础','Nacos：配置与服务发现\ncommon：公共 DTO / RPC 契约\ncommon-config-manager：配置支持')],
 ['实线为主要业务调用，虚线为支撑关系；下方与左侧是配套能力，不代表顺序执行。',
  '当前实现边界：发布状态更新 ≠ 外部发布；媒体 Worker 生成图片；图基于源码而非运行环境验收。'],
 ['pom.xml','agent-service/src/main/java/com/bkanent/agent/graph/official/OfficialSupervisorGraphFactory.java','nacos/agent-service.yaml','memory-service/src/main/java/com/bkanent/memory/controller/MemoryController.java'],
 [(0,1,'HTTP'),(1,2,'官方 A2A'),(2,3,'本地工具'),(3,4,'读写 / 集成')])

def font(size,bold=False): return ImageFont.truetype(str(BOLD if bold else FONT),size)
def wrap(text, f, width):
    lines=[]
    for p in text.split('\n'):
        line=''
        for ch in p:
            if f.getlength(line+ch)>width and line:
                lines.append(line);line=ch
            else: line+=ch
        lines.append(line)
    return lines

def render(g):
    im=Image.new('RGB',(W,H),BG);d=ImageDraw.Draw(im)
    svg=[f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">',f'<rect width="{W}" height="{H}" fill="{BG}"/>']
    def rect(box,fill,outline=None,r=16):
        d.rounded_rectangle(box,radius=r,fill=fill,outline=outline,width=2)
        x,y,x2,y2=box;svg.append(f'<rect x="{x}" y="{y}" width="{x2-x}" height="{y2-y}" rx="{r}" fill="{fill}" stroke="{outline or fill}" stroke-width="2"/>')
    def txt(x,y,t,size=24,color=INK,bold=False):
        f=font(size,bold);d.text((x,y),t,font=f,fill=color)
        svg.append(f'<text x="{x}" y="{y+size}" fill="{color}" font-family="Microsoft YaHei, sans-serif" font-size="{size}" font-weight="{700 if bold else 400}">{escape(t)}</text>')
    def line(points,color=MUTED,dashed=False):
        if dashed:
            for (ax,ay),(bx,by) in zip(points,points[1:]):
                length=math.hypot(bx-ax,by-ay)
                for start in range(0,int(length),18):
                    e=min(start+9,length)
                    d.line((ax+(bx-ax)*start/length,ay+(by-ay)*start/length,ax+(bx-ax)*e/length,ay+(by-ay)*e/length),fill=color,width=3)
        else:d.line(points,fill=color,width=3)
        p=' '.join(f'{x},{y}' for x,y in points)
        dash=' stroke-dasharray="9 9"' if dashed else ''
        svg.append(f'<polyline points="{p}" fill="none" stroke="{color}" stroke-width="3"{dash}/>')
        a,b=points[-2:];angle=math.atan2(b[1]-a[1],b[0]-a[0]);tri=[b,(b[0]-13*math.cos(angle-.45),b[1]-13*math.sin(angle-.45)),(b[0]-13*math.cos(angle+.45),b[1]-13*math.sin(angle+.45))]
        d.polygon(tri,fill=color);svg.append(f'<polygon points="'+ ' '.join(f'{x},{y}' for x,y in tri)+f'" fill="{color}"/>')
    rect((60,48,68,146),'#336ddd',r=3)
    txt(90,48,g['title'],43,bold=True);txt(90,117,g['subtitle'],23,MUTED)
    for a,b,label in g['edges']:
        ax,ay=POS[a];bx,by=POS[b]
        if ay==by:
            pts=[(ax+470 if bx>ax else ax,ay+90),(bx if bx>ax else bx+470,by+90)]
        elif ax==bx: pts=[(ax+235,ay+180 if by>ay else ay),(bx+235,by if by>ay else by+180)]
        else:
            pts=[(ax+235,ay+180),(ax+235,by-35),(bx+235,by-35),(bx+235,by)]
        line(pts)
        if label:
            segment=pts[1:3] if len(pts)>2 else pts[:2]
            px=(segment[0][0]+segment[1][0])/2;py=(segment[0][1]+segment[1][1])/2
            tw=font(17).getlength(label);rect((px-tw/2-6,py-27,px+tw/2+6,py-1),BG,r=3);txt(px-tw/2,py-27,label,17)
    if g['slug']=='16-system-overview':
        line([(325,500),(325,455),(900,455),(900,410)],'#168568',True)
        txt(560,424,'记忆 / 状态读写',17,'#168568')
        line([(560,860),(610,860),(610,190),(900,190),(900,230)],'#8652be',True)
        txt(685,162,'检索与模型支撑',17,'#8652be')
        line([(900,770),(900,725),(1185,725),(1185,320),(1135,320)],'#168568',True)
        txt(995,698,'事件与反馈',17,'#168568')
        line([(1710,860),(1765,860),(1765,190),(1475,190),(1475,230)],'#52677d',True)
        txt(1430,162,'发现 / 配置',17,MUTED)
    if g['loop']:
        line([(90,860),(35,860),(35,185),(1765,185),(1765,320),(1710,320)],'#8652be',True)
        txt(715,157,'可继续工具推理：结果回填模型',19,'#8652be')
    for idx,(title,body,color) in enumerate(g['nodes']):
        x,y=POS[idx];fill,accent=COLORS[color];rect((x,y,x+470,y+180),fill,accent)
        txt(x+22,y+17,title,27,bold=True)
        size=21
        while max(font(size).getlength(t) for t in body.split('\n'))>426 and size>17: size-=1
        lines=wrap(body,font(size),426)
        assert len(lines)<=4,(g['slug'],title,lines)
        for j,t in enumerate(lines):txt(x+22,y+66+j*26,t,size,MUTED)
    rect((90,1010,1710,1150),'#ffffff',r=14)
    txt(112,1025,'阅读说明 / 实现边界',21,bold=True)
    y=1060
    for note in g['notes']:
        for t in wrap('• '+note,font(20),1570):txt(112,y,t,20,MUTED);y+=27
    assert y<=1152,(g['slug'],'note overflow',y)
    txt(90,1171,'BK AGENT  /  CURRENT CODE ARCHITECTURE  /  2026-09-20',17,MUTED)
    txt(1475,1171,g['slug'].split('-')[0]+' / 16',17,MUTED)
    svg.append('</svg>')
    im.save(OUT/(g['slug']+'.png'))
    (OUT/(g['slug']+'.svg')).write_text('\n'.join(svg),encoding='utf-8')

def main():
    for g in GRAPHS:render(g)
    manifest={'basis':'Current working tree, including pre-existing uncommitted source changes; static code review, not deployment verification.',
              'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(), 'diagrams':GRAPHS,
              'source_sha256':{p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for g in GRAPHS for p in g['sources']}}
    (OUT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    cards=''.join(f'<article><h2>{escape(g["title"])}</h2><a href="{g["slug"]}.svg"><img loading="lazy" src="{g["slug"]}.png" alt="{escape(g["title"])}"></a><p><a href="{g["slug"]}.png">PNG</a> · <a href="{g["slug"]}.svg">SVG 可缩放</a></p></article>' for g in GRAPHS)
    (OUT/'index.html').write_text('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>房地产中台架构图集</title><style>body{margin:40px auto;max-width:1440px;padding:0 24px;background:#f3f6fa;color:#142b45;font-family:Microsoft YaHei,sans-serif}article{margin:40px 0}img{width:100%;border-radius:14px}a{color:#336ddd}p{line-height:1.8}</style><h1>房地产中台 · 当前架构图集</h1><p>2026-09-20 · 16 张独立图片 · 根据当前工作区源码梳理，包含已有未提交实现。点击图片打开 SVG。此图集不代表部署验收结果。</p>'+cards+'</html>',encoding='utf-8')
    rows='\n'.join(f'| {g["title"]} | [PNG]({g["slug"]}.png) | [SVG]({g["slug"]}.svg) |' for g in GRAPHS)
    sources='\n'.join(f'### {g["title"]}\n'+ '\n'.join(f'- [`{p}`](../../{p})' for p in g['sources']) for g in GRAPHS)
    (OUT/'README.md').write_text('# 当前架构图集\n\n2026-09-20，根据当前工作区源码生成，包含生成前已存在的未提交代码。描述实现结构，不表示已部署或端到端验收。\n\n打开 [图集浏览页](index.html) 查看全部图片。PNG 适合分享，SVG 适合放大与编辑。\n\n| 图 | 位图 | 矢量图 |\n|---|---|---|\n'+rows+'\n\n## 重要实现边界\n\n- 官方 A2A 子 Agent 共 8 个，直接绑定本地 @Tool；历史 AgentService 与 MCP 入口不能混画为官方必经路径。\n- 官方主图并行映射有 7 个领域，未包含 compare。\n- 营销 publishContent 仅更新本地发布状态。\n- 媒体消费者实际生成图片，不是完整视频生成链路。\n- 默认规划为 rule-first、LLM 规划关闭；会话流 provider 默认 memory。\n- SubAgentSkillSupport 已存在，但未见官方 Provider 接入。\n- 外部发布、邮件、签章、媒体 Provider 受集成模式约束，不能因本地模拟成功认定真实集成可用。\n\n## 复现\n\n使用安装 Pillow 的 Python 运行 `python docs/architecture-current/generate.py`。脚本使用 Windows 微软雅黑字体，并检查文本是否溢出。`manifest.json` 记录图内容、参考文件 SHA-256 及生成时 HEAD；HEAD 不代表工作区没有未提交修改。\n\n## 源码依据\n\n'+sources+'\n',encoding='utf-8')
    print(f'Generated {len(GRAPHS)} PNG + {len(GRAPHS)} SVG diagrams, index and source manifest.')

if __name__=='__main__':main()
