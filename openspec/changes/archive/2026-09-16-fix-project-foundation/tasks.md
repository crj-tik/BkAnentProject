## 1. OpenSpec and build foundation

- [x] 1.1 Remove the repository-wide `openspec/` ignore rule so the change artifacts can be reviewed and versioned.
- [x] 1.2 Configure inherited Spring Boot `repackage` execution for deployable child modules while keeping the root aggregator as a POM.
- [x] 1.3 Update repository build and run documentation so upstream build/install and module-scoped `spring-boot:run` are separate commands.
- [x] 1.4 Package `auth-service` and verify its JAR contains a Spring Boot launcher manifest and runtime dependencies.

## 2. Local runtime and diagnostics

- [x] 2.1 Add an auth-service-only local smoke profile using H2, a local schema/data seed, and disabled Nacos/Dubbo remote registration.
- [x] 2.2 Make the auth-service Nacos config import explicit to the distributed profile, preserving the distributed contract.
- [x] 2.3 Add a non-destructive PowerShell environment diagnostic for local and distributed run modes.
- [x] 2.4 Document Nacos namespace, group, data IDs, template import, infrastructure prerequisites, and the distinction between smoke and distributed profiles.
- [x] 2.5 Run the local auth health endpoint and confirm it starts without Nacos or MySQL.

## 3. Authentication baseline

- [x] 3.1 Add BCrypt password verification and active/deleted account checks to the login flow.
- [x] 3.2 Implement signed, expiring access/refresh token issuance and validation with process-local logout revocation.
- [x] 3.3 Replace permissive auth RPC token and permission checks with token validation and account-aware authorization.
- [x] 3.4 Migrate the demo database seed and local H2 seed to a BCrypt password hash, and document existing-database migration.
- [x] 3.5 Add focused authentication tests for wrong password, valid login, tampered/expired token, logout revocation, and unknown/inactive users.

## 4. Configuration hygiene

- [x] 4.1 Remove usable DeepSeek and DashScope key fallbacks from all checked-in Nacos templates without printing or reintroducing credential values.
- [x] 4.2 Add a credential-free `.env.example` containing the required provider, database, infrastructure, token-secret, and endpoint variables.
- [x] 4.3 Make A2A advertised base URLs configurable and require reachable values in distributed templates.
- [x] 4.4 Add configuration documentation and a repository scan check for credential-shaped values and unreachable distributed endpoint defaults.

## 5. Verification and handoff

- [x] 5.1 Run OpenSpec validation in strict mode and update task completion status.
- [x] 5.2 Run full Maven compile and tests, then package the service modules.
- [x] 5.3 Run the local auth smoke test and the distributed dependency diagnostic with a deliberately unavailable Nacos endpoint.
- [x] 5.4 Review the final diff for accidental credentials, production-default changes, and unrelated business behavior changes.
