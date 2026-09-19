from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import math

OUT = Path(__file__).resolve().parent
W, H = 3200, 1800
BG = '#F5F8FC'
NAVY = '#17324D'
MUTED = '#5B7087'
BLUE = '#3978E8'
PURPLE = '#8755C5'
GREEN = '#15916F'
ORANGE = '#C9801F'
WHITE = '#FFFFFF'
FONT = 'C:/Windows/Fonts/msyh.ttc'
BOLD = 'C:/Windows/Fonts/msyhbd.ttc'

def f(size, bold=False):
    return ImageFont.truetype(BOLD if bold else FONT, size)

def rounded(d, box, fill, outline, radius=18, width=3):
    d.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)

def text(d, xy, value, size=26, color=NAVY, bold=False, anchor=None):
    d.text(xy, value, font=f(size, bold), fill=color, anchor=anchor)

def centered(d, box, value, size=26, color=NAVY, bold=False):
    x1, y1, x2, y2 = box
    text(d, ((x1+x2)//2, y1+24), value, size, color, bold, 'ma')

def arrow(d, a, b, color=MUTED, width=5, dashed=False):
    x1, y1 = a; x2, y2 = b
    if dashed:
        length = math.hypot(x2-x1, y2-y1)
        steps = max(1, int(length // 24))
        for i in range(0, steps, 2):
            t1, t2 = i/steps, min((i+1)/steps, 1)
            d.line((x1+(x2-x1)*t1, y1+(y2-y1)*t1,
                    x1+(x2-x1)*t2, y1+(y2-y1)*t2), fill=color, width=width)
    else:
        d.line((x1, y1, x2, y2), fill=color, width=width)
    angle = math.atan2(y2-y1, x2-x1)
    p = [(x2, y2),
         (x2-22*math.cos(angle-0.45), y2-22*math.sin(angle-0.45)),
         (x2-22*math.cos(angle+0.45), y2-22*math.sin(angle+0.45))]
    d.polygon(p, fill=color)

def section(d, y, h, title, color):
    d.rounded_rectangle((70, y, W-70, y+h), radius=26, fill='#FFFFFF', outline='#D7E1EC', width=3)
    d.rounded_rectangle((70, y, 88, y+h), radius=9, fill=color)
    text(d, (115, y+22), title, 31, color, True)

def box(d, x, y, w, h, title, body, accent, body_size=20):
    rounded(d, (x, y, x+w, y+h), '#FFFFFF', accent, 16, 3)
    d.rounded_rectangle((x, y, x+w, y+12), radius=8, fill=accent)
    compact = h <= 100
    title_size = 21 if compact else 24
    text(d, (x+20, y+(17 if compact else 27)), title, title_size, NAVY, True)
    yy = y+(48 if compact else 72)
    for line in body.split('\n'):
        size = 16 if compact else body_size
        text(d, (x+20, yy), line, size, MUTED)
        yy += size + (5 if compact else 9)

def render():
    im = Image.new('RGB', (W, H), BG)
    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, W, 160), fill='#EAF1FF')
    d.rectangle((0, 0, 26, H), fill=BLUE)
    text(d, (82, 38), '房地产中台多 Agent 系统｜当前代码架构', 46, NAVY, True)
    text(d, (84, 104), '分层视图：访问入口 → Supervisor 编排 → 领域 Agent → 业务服务 → 数据与基础设施', 25, MUTED)
    text(d, (W-90, 48), 'PNG 架构图', 22, BLUE, True, 'ra')
    text(d, (W-90, 92), '2026-09-20', 18, MUTED, False, 'ra')

    section(d, 205, 205, '1 访问与接入层', BLUE)
    box(d, 130, 275, 560, 90, '业务前端 / API 调用方', 'HTTP 请求 · SSE 订阅', BLUE, 22)
    box(d, 810, 275, 560, 90, 'gateway', '统一路由 · 健康检查', BLUE, 22)
    box(d, 1490, 275, 560, 90, 'auth-service', '认证 · token · 权限入口', BLUE, 22)
    box(d, 2170, 275, 840, 90, 'Agent API', '/agent/chat · /supervisor/tasks · async / stream', BLUE, 22)
    arrow(d, (690, 320), (810, 320), BLUE)
    arrow(d, (1370, 320), (1490, 320), BLUE)
    arrow(d, (2050, 320), (2170, 320), BLUE)

    section(d, 445, 315, '2 Agent 编排与治理层｜agent-service', PURPLE)
    box(d, 130, 525, 540, 160, 'Supervisor Graph', 'PLAN · ROUTE · SINGLE / PARALLEL\nAPPROVAL · HANDOFF · COMPLETE', PURPLE)
    box(d, 735, 525, 540, 160, 'A2A 运行时', 'Agent Card / Registry\n官方 A2A sync · async · stream', PURPLE)
    box(d, 1340, 525, 540, 160, '治理与状态', '权限 · 限流 · 灰度 · 审计\n检查点 · 异步任务 · 工作流状态', PURPLE)
    box(d, 1945, 525, 540, 160, '工具与检索', 'MCP client / catalog\nRAG：关键词 + Milvus + rerank', PURPLE)
    box(d, 2550, 525, 460, 160, '会话反馈', 'SSE 流\n可选 RocketMQ event bus', PURPLE)
    for x in (670, 1275, 1880, 2485): arrow(d, (x, 605), (x+65, 605), PURPLE)
    arrow(d, (2390, 365), (2390, 525), BLUE)

    section(d, 800, 305, '3 领域智能体层｜官方 A2A ReactAgent Provider', PURPLE)
    agents = [
        ('listing-agent', '房源检索与摘要'), ('compare-agent', '房源对比报告'),
        ('marketing-agent', '内容创建与状态'), ('media-agent', '媒体任务与素材'),
        ('trade-agent', 'KPI / 看板 / 排名'), ('contract-agent', '合同详情与风险'),
        ('settlement-agent', '佣金 / 汇总 / 批次'), ('notification-agent', '站内信 / 邮件'),
    ]
    x0, y0, bw, bh, gap = 125, 875, 670, 80, 35
    for i, (name, desc) in enumerate(agents):
        x = x0 + (i % 4) * (bw + gap)
        y = y0 + (i // 4) * 115
        box(d, x, y, bw, bh, name, desc + ' · ChatModel + @Tool', PURPLE, 18)
    arrow(d, (1520, 685), (1520, 875), PURPLE)

    section(d, 1145, 275, '4 业务服务与领域工具层', GREEN)
    services = [
        ('listing-master', '房源主数据 / ES'), ('customer-service', '客源与业主管理'),
        ('compare-engine', '报告缓存 / PDF'), ('marketing-content', '内容资产 / 检索'),
        ('promotion-service', '多平台发布 / 效果'), ('business-service', '经营分析 / KPI'),
        ('contract-service', '合同生命周期'), ('settlement-service', '结算与分佣'),
        ('notification-service', '通知记录 / 消费'), ('media-worker', 'RocketMQ 媒体 Worker'),
    ]
    sx, sy, sw, sh, sg = 125, 1215, 540, 75, 35
    for i, (name, desc) in enumerate(services):
        x = sx + (i % 5) * (sw + sg)
        y = sy + (i // 5) * 105
        box(d, x, y, sw, sh, name, desc, GREEN, 18)
    arrow(d, (1520, 1080), (1520, 1215), GREEN)

    section(d, 1450, 270, '5 数据、消息、配置与外部 Provider', ORANGE)
    infra = [
        ('MySQL', '业务表 / Agent 状态'), ('Redis', '限流 / 缓存'), ('Elasticsearch', '关键词检索'),
        ('Milvus', '向量检索 / 记忆'), ('MinIO', '媒体与报告文件'), ('RocketMQ', '媒体任务 / 工作流事件'),
        ('Nacos', '配置与服务发现'), ('DeepSeek / DashScope', 'ChatModel / Embedding / rerank'),
    ]
    ix, iy, iw, ih, ig = 125, 1515, 670, 75, 35
    for i, (name, desc) in enumerate(infra):
        x = ix + (i % 4) * (iw + ig)
        y = iy + (i // 4) * 105
        box(d, x, y, iw, ih, name, desc, ORANGE, 18)
    arrow(d, (1520, 1420), (1520, 1515), ORANGE)

    # Cross-layer support lines and labels.
    arrow(d, (1000, 690), (1000, 875), '#8AA0B7', 4, True)
    text(d, (1020, 748), 'A2A 分发', 17, MUTED)
    arrow(d, (1710, 685), (1710, 875), '#8AA0B7', 4, True)
    text(d, (1730, 748), '检索 / 工具', 17, MUTED)
    arrow(d, (370, 1390), (370, 1515), '#8AA0B7', 4, True)
    text(d, (395, 1440), '业务读写', 17, MUTED)
    arrow(d, (2390, 1390), (2390, 1515), '#8AA0B7', 4, True)
    text(d, (2415, 1440), '异步与外部集成', 17, MUTED)

    # Footer notes, kept inside image so it remains self-contained.
    d.rounded_rectangle((70, 1710, W-70, 1775), radius=12, fill='#FFFFFF', outline='#D7E1EC', width=2)
    text(d, (100, 1727), '实现边界：官方子 Agent 直接绑定领域本地 @Tool；营销 publishContent 当前更新本地状态；媒体 Worker 当前生成图片素材；外部 Provider 是否可用由集成模式与配置决定。', 17, MUTED)
    path = OUT / 'architecture-overview.png'
    im.save(path, 'PNG', optimize=True)
    # JPEG copy is useful for systems that preview JPG more reliably than PNG.
    im.save(OUT / 'architecture-overview.jpg', 'JPEG', quality=95, optimize=True)
    print(path)

if __name__ == '__main__':
    render()
