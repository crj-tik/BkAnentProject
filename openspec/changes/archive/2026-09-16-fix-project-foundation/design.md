## Context

The proposal identifies several independent foundation failures. The current repository keeps most runtime configuration in Nacos, but every service assumes a reachable Nacos endpoint during bootstrap. The root build manages the Spring Boot plugin without a repackage execution, the authentication module has no credential or token validation, and the checked-in templates contain prototype fallbacks and loopback A2A URLs.

## Goals / Non-Goals

**Goals:**

- Establish a deterministic local smoke path for `auth-service` that does not require Nacos or MySQL.
- Preserve the existing distributed/Nacos profile while making its prerequisites explicit and diagnosable.
- Produce executable service JARs and correct the documented Maven workflow.
- Add a minimal trustworthy authentication baseline without introducing a full identity platform.
- Remove usable provider-key fallbacks and make advertised service addresses environment-driven.
- Add focused tests and a non-destructive environment check.

**Non-Goals:**

- Implement real OCR, electronic-signature, email, robot, social-platform, Stable Diffusion, or object-storage integrations.
- Replace Nacos, Dubbo, MCP, A2A, or RocketMQ architecture.
- Build a complete RBAC/tenant-isolation subsystem.
- Make every business service runnable without its real backing infrastructure in this change.

## Decisions

### 1. Use an explicit local smoke profile for auth-service

The local profile will use an in-memory H2 database with a small schema and a development account, disable Nacos discovery/config and Dubbo remote registry/config-center, and keep the default distributed profile unchanged. This gives contributors a verifiable health/login path without pretending that the full multi-service system has no infrastructure requirements.

An all-services H2 profile was rejected because the services have materially different external integrations and would create a second, misleading production-like configuration that is difficult to keep in sync.

### 2. Make Nacos bootstrap an explicit distributed profile

The auth service keeps Nacos config import in the explicit `distributed` profile. The local profile does not initialize a Nacos client and proceeds with H2; distributed mode still requires the documented Nacos namespace, data IDs, and dependencies. The diagnostics script fails fast for that mode, and missing production configuration is not silently replaced with mock business behavior.

### 3. Use BCrypt for stored passwords and JDK HMAC for tokens

BCrypt provides a standard adaptive password hash with a small dependency footprint. Access and refresh tokens will use a versioned HMAC-signed payload containing user identity, token type, and expiry, with constant-time signature comparison and process-local revocation for logout. A deployment-provided secret is required for stable multi-instance validation; a random process secret is allowed only for the local smoke profile.

JWT was rejected for this foundation change because it would add token claims, key rotation, and library configuration beyond the current scope. A database-backed refresh-token/session store was also deferred; revocation is intentionally documented as process-local until a shared token store is designed.

### 4. Configure executable packaging through inherited plugin management

The root Maven plugin management will inherit a Spring Boot `repackage` execution into the deployable child modules. The root aggregator will remain a POM and will not receive application packaging. The documentation will use a separate upstream build/install step followed by a module-scoped `spring-boot:run` invocation.

### 5. Keep secrets external and make advertised addresses explicit

Nacos templates will use required environment references without usable API-key fallbacks. An `.env.example` will list variable names but no credentials. A2A cards will require `A2A_PUBLIC_BASE_URL`; loopback is available only in explicitly local operations and is not a distributed template fallback.

### 6. Track OpenSpec artifacts with the repository

The current ignore rules exclude the entire `openspec/` directory. That rule will be removed so the proposal, specs, design, and task status can be reviewed and versioned with the code change.

## Risks / Trade-offs

- [Existing demo users have plaintext seed values] → Replace the committed seed with a BCrypt value and document that existing databases require a password migration.
- [Process-local revocation does not work across auth instances] → Require a shared configured signing secret and document the limitation; defer shared revocation storage to a later change.
- [Optional Nacos import can hide missing distributed configuration] → Limit the local bypass to the auth local profile and provide a distributed-mode diagnostics command.
- [Executable repackaging changes artifact layout and size] → Update deployment instructions and verify both `java -jar` and module tests.
- [Removing fallback provider and infrastructure credentials can make an unconfigured service fail earlier] → Add `.env.example` and make missing variables explicit rather than silently using a committed credential.

## Migration Plan

1. Rotate/revoke any provider credentials previously present in the repository.
2. Apply code/config/build changes and run compile, tests, package, and local auth smoke verification.
3. For distributed environments, provide the new environment variables, import Nacos templates into the documented namespace, and set reachable A2A base URLs.
4. Existing auth database rows must be migrated to BCrypt hashes before enabling the new login flow.
5. Rollback is source-version based: restore the prior build/config revision only in an isolated development environment; do not restore exposed credentials.

## Open Questions

None for this foundation change. The choice of shared token revocation storage and real third-party providers can be specified in follow-up changes.
