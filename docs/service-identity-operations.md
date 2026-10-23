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

## HMAC 迁移兼容

迁移期 Auth 与 Memory verifier 可接受显式的 HMAC-v1 旧请求或 Ed25519-v2 新请求。新签发方只发 Ed25519-v2；版本、算法、key ID 和协议域必须匹配，不做跨协议验签或算法降级。`.env.example` 中旧 `*_A2A_SIGNING_SECRET` 与 `*_MEMORY_V2_SIGNING_SECRET` 仅用于仍在运行的旧二进制；部署环境不需要为新二进制生成或新增这些秘密。RAG 的 `*_RAG_RPC_*` HMAC 契约不属于本次迁移，不能因此删除。

只有采集到足够的运行证据、确认旧 HMAC 请求流量为零后，才能移除 Auth/Memory 的 HMAC verifier 代码和旧秘密配置。当前开发环境没有真实业务流量证明这一点，所以兼容 verifier 保留，不能把迁移实现视为 HMAC 已退役。

Auth 与 Memory 在成功验签后增加 `service.identity.signature.verified` 计数，固定标签只有 `protocol`（`a2a-auth` 或 `memory-v2`）与 `algorithm`（`HMAC-SHA256` 或 `Ed25519`）。通过对应服务的 `/actuator/metrics/service.identity.signature.verified` 查询，再用 `tag=protocol:a2a-auth`、`tag=algorithm:HMAC-SHA256` 或 Memory 对应标签筛选。指标不含 caller、key ID、owner 或请求数据；观察 HMAC 计数在完整观察期内不再增加，才可作为退役证据的一部分。服务重启会重置本机计数，需结合监控后端/部署时间记录并覆盖最长调用周期和回滚窗口。

## key ID 轮换与撤销

轮换时先生成新 key ID 的独立密钥对：

```powershell
.\scripts\security\New-ServiceIdentities.ps1 --rotate agent-service agent-service-2026-rotation-1
```

生成器拒绝覆盖已有目标。随后按以下顺序操作：

1. 将新 `public-key.pem` 的公钥值登记到 Auth 及该服务实际调用的其他 verifier，使用新的 key ID；保持旧公钥和精确授权仍有效。公钥可以进入受管 Nacos 配置，私钥不能。
2. 将该服务的私钥只读挂载切换到新 key ID 对应的文件，并把调用服务的 `service.identity.key-id` / Memory signer key ID 更新为同一个新 ID。重启该服务后确认验签和 ACL 正常。
3. 过渡期结束并确认不再有旧 key ID 请求后，从所有 verifier 删除旧公钥登记。撤销时直接移除对应 key ID 的公钥，后续该身份请求会失败关闭。

Compose 的私钥源路径按服务分别写在 `docker-compose.yml` 的 bind mount 中；轮换时只修改目标服务路径并更新其 key ID。不要把轮换后的新私钥放进旧容器可读目录或通过环境变量传递私钥内容。

## 故障排查

- 启动时报 `service identity private key is not configured`：确认已运行首次创建脚本、目标服务私钥文件存在，Compose 的只读挂载路径与服务一致。
- Auth/Memory 返回认证失败：核对请求算法、协议版本、key ID 与 verifier 对应公钥；检查 caller/domain/agent/operation 授权是否登记。不要通过扩大 ACL 或启用 HMAC 回退来绕过不匹配。
- Compose 找不到 `public-keys.env`：先在仓库根目录运行生成脚本；该文件只含公钥，不应手工把私钥粘贴进去。
