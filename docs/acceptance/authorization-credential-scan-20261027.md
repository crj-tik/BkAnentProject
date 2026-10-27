# 授权凭据存量扫描验收（2026-10-27）

## 访谈票据副本扫描

- 目标：确认访谈数据库的文本与 JSON 字段中没有可恢复的登录令牌、访谈票据、Authorization、request proof 或长随机凭据副本。
- 环境：开发 Compose 栈；MySQL healthy，`bk_interview` 数据库。
- 执行：`python scripts/deploy/scan_interview_credential_shapes.py --database bk_interview --docker-container bk-anent-dev-mysql-1`。
- 结果：只读扫描 61 个文本/JSON 字段，`matched_column_rows=0`。扫描器只输出列名和计数；没有读取或回显匹配值。专用 `ticket_hash` 字段及 hash/checksum 字段按设计排除，因为它们保存的是不可逆摘要，不是可恢复票据。
- 处置：没有发现可恢复的凭据副本，因此没有撤销或删除业务数据。结果仅证明本次扫描范围内没有命中，不代表其他数据库或运行时缓存已完成同类扫描。

## 回归验证

- `python scripts/deploy/test_interview_credential_scan.py`：8 项通过。
- Auth 与 Interview 定向 Maven 回归：54 项通过，失败 0、错误 0、跳过 0。覆盖 outbox 发布顺序、授权版本发布围栏、迁移 fresh/upgrade/repeat 夹具、访谈票据用途与撤销、URL 凭据拒绝、工具/归档脱敏。

## 未覆盖边界

本记录不验证浏览器 Fetch/SSE 请求头接线，也不代替真实 MySQL/Redis 并发发布、完整开发栈 send/stream/get/cancel/resubscribe 或安全制品回滚验收。
