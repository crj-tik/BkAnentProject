# Interview 历史凭据形状只计数扫描

`scripts/deploy/scan_interview_credential_shapes.py` 是任务8.5的只读计数工具。它在传入的 MySQL schema 中发现文本/JSON列，对 Bearer、JWT、凭据赋值和高熵不透明串形状执行 `COUNT(*)`；输出仅包含 schema、表/列名与每列候选行数，不返回或记录匹配正文。`matched_column_rows` 是各列计数之和，同一数据库行可能在多个列重复计数。Schema迁移账本、Interview票据表中的 `ticket_hash` 以及列名显式标记为hash/digest/checksum/SHA/MD5的不可逆摘要会排除，避免把校验值报告成凭据候选。

## 执行前提

- 对共享或线上数据库，使用经授权的只读 MySQL 账号；账号仅需读取 `information_schema.columns` 与目标 schema 文本列。Docker示例只适用于本机开发容器，使用容器内root凭据并将MySQL session设为只读，不应用于共享/线上数据库。
- 将账号配置在 MySQL client 的受限 option file 或受管 login path 中，不把密码放进命令、日志或工单。
- 在授权的运维终端运行；脚本不会自动连接仓库/本机配置里的数据库，也不会启动服务、Docker 或迁移。

For a reachable MySQL server, use a protected read-only option file:

```powershell
python scripts/deploy/scan_interview_credential_shapes.py --database bk_interview --defaults-extra-file C:\secure\interview-readonly.cnf
```

For the local Compose MySQL container, the scanner can use the container's root credential without passing it through the host command line. It sets the MySQL session to read-only before scanning:

```powershell
python scripts/deploy/scan_interview_credential_shapes.py --database bk_interview --docker-container bk-anent-dev-mysql-1
```

若使用 MySQL client 已配置的默认安全凭据，可省略 `--defaults-extra-file`。`--database` 必须是简单 schema 标识符。脚本执行两次只读查询：一次取得列名，一次生成逐列计数；session 默认事务为只读，每列查询有10秒执行上限，连接建立有10秒超时，超时或权限不足会整次失败，不会输出部分结果。建议在低峰期运行。客户端错误详情会被抑制，避免输出连接配置；排错时应在受限终端单独核对客户端凭据配置。

## 解释与边界

- 正计数是候选行数，不表示每行都含有效凭据；脚本不显示匹配内容，不撤销票据，也不删除数据。应由受权人员按业务状态和凭据摘要采用既有撤销/清理路径处理，并只记录对象数和结果状态。
- 零计数只表示所选 schema 的当前文本/JSON列未匹配这些形状，不证明其他 schema、文件日志、备份、binlog、消息队列副本或未纳入此正则的秘密均已清除。
- 工具只扫描显式选择的一个 schema，需对其它纳入范围的数据库分别执行。它不替代文件日志、备份/binlog、消息队列副本与浏览器持久化核查。
- 2026-10-27在 `bk-anent-dev-mysql-1` 上以只读session对13个开发schema执行了扫描；未回显任何匹配正文，也未执行写入、撤销或删除。结果如下：

| Schema | 文本/JSON列 | 候选列行数合计 |
|---|---:|---:|
| bk_agent | 141 | 404 |
| bk_auth | 8 | 0 |
| bk_business | 16 | 0 |
| bk_contract | 36 | 0 |
| bk_customer | 18 | 0 |
| bk_interview | 61 | 0 |
| bk_listing | 26 | 12 |
| bk_marketing | 13 | 0 |
| bk_memory | 263 | 174 |
| bk_notification | 16 | 0 |
| bk_promotion | 15 | 0 |
| bk_rag | 15 | 131 |
| bk_settlement | 34 | 0 |

`bk_agent`、`bk_listing`、`bk_memory`和`bk_rag`的正数分布在请求、事件、artifact与RAG outbox的ID/JSON列中；当前只读计数无法区分长业务标识/序列化引用与可撤销凭据，不能将其称为泄漏或直接删除。KI-202和OpenSpec 8.5仍保持未完成，需由有权数据责任人复核候选类型并决定是否撤销/清理；另需核查上述数据库以外的副本。
