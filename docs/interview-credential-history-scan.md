# Interview 历史凭据形状只计数扫描

`scripts/deploy/scan_interview_credential_shapes.py` 是任务8.5的只读准备工具。它在传入的 MySQL schema 中发现文本/JSON列，对 Bearer、JWT、凭据赋值和高熵不透明串形状执行 `COUNT(*)`；输出仅包含 schema、表/列名与每列候选行数，不返回或记录匹配正文。`matched_column_rows` 是各列计数之和，同一数据库行可能在多个列重复计数。Interview 票据表中的 `ticket_hash` 以及其它显式 hash/digest/checksum 列被排除，因为这些字段存储的是不可逆摘要，不是原始凭据。

## 执行前提

- 由有权限的数据库运维人员批准一个只读 MySQL 账号；账号仅需读取 `information_schema.columns` 与目标 schema 文本列。
- 将账号配置在 MySQL client 的受限 option file 或受管 login path 中，不把密码放进命令、日志或工单。
- 在授权的运维终端运行；脚本不会自动连接仓库/本机配置里的数据库，也不会启动服务、Docker 或迁移。

```powershell
python scripts/deploy/scan_interview_credential_shapes.py --database bk_interview --defaults-extra-file C:\secure\interview-readonly.cnf
```

若使用 MySQL client 已配置的默认安全凭据，可省略 `--defaults-extra-file`。`--database` 必须是简单 schema 标识符。脚本执行两次只读查询：一次取得列名，一次生成逐列计数；每列查询有10秒执行上限，连接建立有10秒超时，超时或权限不足会整次失败，不会输出部分结果。建议在低峰期运行。客户端错误详情会被抑制，避免输出连接配置；排错时应在受限终端单独核对客户端凭据配置。

## 解释与边界

- 正计数是候选行数，不表示每行都含有效凭据；脚本不显示匹配内容，不撤销票据，也不删除数据。应由受权人员按业务状态和凭据摘要采用既有撤销/清理路径处理，并只记录对象数和结果状态。
- 零计数只表示所选 schema 的当前文本/JSON列未匹配这些形状，不证明其他 schema、文件日志、备份、binlog、消息队列副本或未纳入此正则的秘密均已清除。
- 当前使用说明面向 Interview schema；工具只扫描显式选择的一个 schema，需对其它纳入范围的数据库分别执行。运行结果尚未产生。KI-202 和 OpenSpec 8.5 只有在受权现场扫描、正计数处置及未完成任务安全复核后才能关闭。
