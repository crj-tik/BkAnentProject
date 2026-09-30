# MySQL 只读 MCP

```text
Agent -- Authorization: Bearer ... --> 127.0.0.1:18081/mcp
                                          |
                                     mysql-mcp:8000
                                          |
                                      mysql:3306
```

MySQL 使用原有官方镜像与数据卷。MCP 使用独立 Python 镜像，进程、健康检查和升级均独立；`mysql-mcp-init` 是启动前自动退出的一次性账号管理任务。正常运行时只有 MySQL 与 MCP 两个常驻容器。

## 启动

在仓库根目录运行：

```powershell
powershell -NoProfile -File scripts/start-mysql-mcp.ps1
```

该脚本不会输出密钥，只在缺失时向被 Git 忽略的 `.env` 写入随机 `MYSQL_MCP_PASSWORD`、`MYSQL_MCP_TOKEN`。已有环境变量或 `.env` 中的非空值会保留。已有 MySQL 数据卷必须配置正确的 `MYSQL_ROOT_PASSWORD`；空白开发环境沿用现有 Compose 的开发 root 密码。不会重建数据库、清空数据或重复执行 `sql/mysql-init.sql`。

手动管理凭据时，先在 `.env` 设置 `MYSQL_MCP_PASSWORD` 和不少于 32 字节的 `MYSQL_MCP_TOKEN`，然后运行：

```powershell
docker compose --profile mcp up -d --build --wait mysql-mcp
docker compose --profile mcp ps -a mysql mysql-mcp-init mysql-mcp
```

`MYSQL_MCP_DATABASES` 使用逗号分隔的数据库白名单（不要加空格）。默认配置包含项目的 11 个业务库；授权前会确认这些库已经存在，不包含 Nacos。专用账号 `mcp_ro` 由初始化任务管理，会撤销旧权限后重新授予白名单库的 `SELECT`、`SHOW VIEW`。不要将它复用为其他应用账号。MCP 容器只持有该只读账号，root 凭据仅用于一次性初始化任务。

## 客户端接入

- 传输：Streamable HTTP。
- 宿主机地址：`http://127.0.0.1:18081/mcp`。
- 同一 Compose 网络内：`http://mysql-mcp:8000/mcp`。
- 请求头：`Authorization: Bearer <MYSQL_MCP_TOKEN>`。

支持以下工具，均声明为只读：

| 工具 | 参数 | 返回 |
| --- | --- | --- |
| `list_databases` | 无 | 数据库白名单 |
| `list_tables` | `database` | 表、视图及注释 |
| `describe_table` | `database`, `table` | 字段、类型、键及注释 |
| `query` | `database`, `sql` | `columns`、`rows`、`truncated` |

调用示例：

```json
{
  "name": "query",
  "arguments": {
    "database": "bk_listing",
    "sql": "SELECT * FROM listing_info LIMIT 10"
  }
}
```

查询支持单条 SELECT、CTE 与 UNION；拒绝写操作、多语句、文件输出、锁定读取、优化器提示和休眠/加锁等函数。SQL 经 MySQL 方言解析后重新生成。每次连接开启只读事务，数据库账号权限构成最终保护。默认最多返回 200 行（可调范围 1–1000），SELECT 执行超时默认 5000 毫秒（可调范围 100–60000），会读取额外一行判断 `truncated`。时间值使用 ISO 文本，DECIMAL 使用字符串保持精度，二进制使用 `{"base64":"..."}`。

## 验证与维护

健康检查请求 `/healthz`，同样需要 Bearer Token，实际执行数据库探测；数据库不可用时返回 503，容器显示 unhealthy。未鉴权的 MCP 请求返回 401。

启动前可单独生成凭据：

```powershell
powershell -NoProfile -File scripts/start-mysql-mcp.ps1 -PrepareOnly
docker compose --profile mcp config --quiet
```

本地协议和权限策略测试（无需数据库，数据库操作使用测试替身）：

```powershell
python -m venv .codex-temp-mysql-mcp-venv
.\.codex-temp-mysql-mcp-venv\Scripts\python.exe -m pip install -r mysql-mcp/requirements.txt
.\.codex-temp-mysql-mcp-venv\Scripts\python.exe -m unittest discover -s mysql-mcp/tests -v
```

只升级 MCP 镜像并重启 MCP，不重启 MySQL：

```powershell
docker compose --profile mcp build mysql-mcp
docker compose --profile mcp up -d --no-deps mysql-mcp
```

修改数据库密码或白名单后，先重新运行初始化任务，再更新 MCP：

```powershell
docker compose --profile mcp run --rm mysql-mcp-init
docker compose --profile mcp up -d --no-deps mysql-mcp
```

宿主机直连 MySQL 是可选项：

```powershell
docker compose -f docker-compose.yml -f docker-compose.mysql-client.yml up -d mysql
```

后续启动也应带上此覆盖文件，才能继续保留端口映射。容器网络内的 MCP 和 Java 服务始终使用 `mysql:3306`。

远程访问时设置 `MYSQL_MCP_BIND_ADDRESS` 和允许的 Host（`MYSQL_MCP_ALLOWED_HOSTS`），通过 HTTPS 反向代理接入。默认只允许 `127.0.0.1:*`、`localhost:*`、`mysql-mcp:8000`；MCP 暴露全部白名单数据，不处理业务租户权限，应按调用者需要缩小数据库授权。当前采用预配置 Bearer Token，客户端须支持自定义 Authorization 头；要求 OAuth 自动发现的客户端需另行接入身份服务。

实现采用 [官方 MCP Python SDK v1](https://py.sdk.modelcontextprotocol.io/v1/) 的 Streamable HTTP 和 [MySQL Connector/Python 只读事务](https://dev.mysql.com/doc/connector-python/en/connector-python-api-mysqlconnection-start-transaction.html)。依赖版本在 `requirements.txt` 固定，避免 SDK 大版本变化影响部署。
