# 服务身份密钥运维

## 密钥边界

一个服务只拥有自己的一组 Ed25519 私钥/公钥。调用方用同一把私钥签 A2A/Auth 与 Memory v2 请求；协议签名域分别为 `BK-ANENT:A2A-AUTH:v1` 和 `BK-ANENT:MEMORY-V2:v1`，所以两种协议的签名不能互换。每个 verifier 只登记需要验证的公钥，并独立配置 caller、operation、domain、agent 等授权；登记公钥本身不会授予访问权限。

开发 Compose 使用 `.secrets/service-identities/` 保存本机密钥。私钥目录受 `.gitignore` 忽略，Windows ACL 限定为当前用户、SYSTEM 和 Administrators；公钥以 Base64 X.509 格式写入 `public-keys.env`。Compose 将公钥文件加载为配置，并把某个服务自己的私钥文件只读挂载到该服务的 `/run/secrets/service-identity.pem`。不要将私钥复制到 `.env`、Nacos、镜像或版本库。

## 首次创建本机密钥

在仓库根目录 `D:\project\BkAnentProject` 的 PowerShell 窗口执行：

```powershell
.\scripts\security\New-ServiceIdentities.ps1
```

脚本用本机 Java 17 为 Auth 和 11 个调用服务各生成一组密钥；再次执行只校验既有密钥对，不覆盖它们。脚本不输出私钥。生成后 Compose 才能找到 `public-keys.env` 与各服务的私钥文件。部署配置中 `SERVICE_IDENTITY_PRIVATE_KEY_FILE` 指向容器内的只读挂载路径；各调用服务默认使用自己的 `<service>-current` key ID，Auth 响应用 `AUTH_SERVICE_IDENTITY_KEY_ID=auth-service-current`。

## verifier 登记范围

- Auth 登记所有当前需要 A2A/Auth 信标调用的服务公钥；只有 `agent-service` 可执行 `ISSUE_BEACON`，其他已登记调用方仅可执行 `RESOLVE_BEACON`，与 Auth 服务端的签发约束一致。
- Memory 只登记现有获准的八个 Memory caller：agent、marketing、interview、listing、business、contract、compare、settlement。每个 caller 的 domain/agent 允许表、service delivery 和 experience publisher 权限仍单独控制。其他服务即使有服务身份，也没有 Memory 权限。
- 新增服务时只生成一对身份；仅把它的公钥登记到实际 verifier，并在同一变更中审查最小授权。不要为 A2A 与 Memory 各生成一把共享密钥。

## Memory 请求签名

Memory v2 从首个受管版本起只接受 Ed25519 proof，使用调用服务已有的身份私钥和 `BK-ANENT:MEMORY-V2:v1` 协议域；不配置 Memory 请求 HMAC 密钥，也不提供旧请求兼容。Auth/Memory verifier 均只按 key ID 查找公钥，并继续各自执行精确 caller、operation、domain、agent 与用户授权。Memory 的成功验签计数只记录 `protocol=memory-v2`、`algorithm=Ed25519`。

## Auth/A2A 旧 verifier 退役记录

本机 `bk-anent-dev` 栈已完成 Auth/A2A 旧 HMAC 观察和退役。Auth 于 `2026-10-23 14:57:48 +08:00` 启动；`protocol=a2a-auth, algorithm=HMAC-SHA256` 在 `15:15:51 +08:00` 的计数为 `0.0`，连续观察 18 分 03 秒，超过 300 秒调用租约加 300 秒回滚窗口。观察期间当前栈内运行的 A2A 调用服务是 Agent 与 Interview；二者实际 Ed25519 请求累计计数为 10。其他领域服务当时未运行，因此这份运行证据仅覆盖本机当前启用入口。

观察通过后已删除 Auth/A2A HMAC verifier、对应 `*_A2A_SIGNING_SECRET` 环境与配置项、旧启动脚本填充逻辑，以及 Auth 对应的 HMAC 计数器。退役后旧 HMAC 指标不存在；这不能解释为新的零值。Auth/A2A 当前只接受 Ed25519，并分别按来源 caller 的 `ISSUE_BEACON` 和 binding audience 的 `RESOLVE_BEACON` 执行公钥与 operation ACL。Memory v2 从首个受管版本起只接受 Ed25519。`*_RAG_RPC_*` HMAC 凭据仍属于独立 RAG RPC 合约，不在本次退役范围。

## key ID 轮换与撤销

轮换时先生成新 key ID 的独立密钥对：

```powershell
.\scripts\security\New-ServiceIdentities.ps1 --rotate agent-service agent-service-2026-rotation-1
```

生成器拒绝覆盖已有目标。随后按以下顺序操作：

1. 将新 `public-key.pem` 的公钥值登记到 Auth 及该服务实际调用的其他 verifier，使用新的 key ID；保持旧公钥和精确授权仍有效。公钥可以进入受管 Nacos 配置，私钥不能。
2. 将该服务的私钥只读挂载切换到新 key ID 对应的文件，并把调用服务的 `service.identity.key-id` 更新为同一个新 ID。重启该服务后确认验签和 ACL 正常。
3. 过渡期结束并确认不再有旧 key ID 请求后，从所有 verifier 删除旧公钥登记。撤销时直接移除对应 key ID 的公钥，后续该身份请求会失败关闭。

Compose 的私钥源路径按服务分别写在 `docker-compose.yml` 的 bind mount 中；轮换时只修改目标服务路径并更新其 key ID。不要把轮换后的新私钥放进旧容器可读目录或通过环境变量传递私钥内容。

## 故障排查

- 启动时报 `service identity private key is not configured`：确认已运行首次创建脚本、目标服务私钥文件存在，Compose 的只读挂载路径与服务一致。
- Memory 返回认证失败：核对 Ed25519 算法、协议版本、key ID 与 verifier 对应公钥；检查 caller/domain/agent/operation 授权是否登记。不要通过扩大 ACL 或改变签名算法来绕过不匹配。
- Auth/A2A 返回认证失败：核对该协议的签名版本、key ID 与 verifier 对应公钥，并检查 caller/operation 授权。
- Compose 找不到 `public-keys.env`：先在仓库根目录运行生成脚本；该文件只含公钥，不应手工把私钥粘贴进去。
