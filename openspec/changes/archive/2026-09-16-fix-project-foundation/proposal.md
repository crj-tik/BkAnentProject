## Why

The repository currently compiles, but it is not reproducibly runnable or deployable: startup depends on an externally prepared Nacos environment, the documented Maven run command fails at the aggregator project, packaged JARs are not executable Spring Boot artifacts, and authentication/configuration still contain prototype-only behavior. These foundation problems should be fixed before expanding business features or claiming production readiness.

## What Changes

- Add a reproducible local-runtime contract for service startup, including explicit dependency checks, Nacos configuration bootstrap guidance, and a working single-service Maven command.
- Make service packaging produce executable Spring Boot JARs with dependency libraries included.
- Replace prototype authentication behavior with password verification, signed expiring access tokens, token revocation on logout, and database-backed account checks for RPC authorization.
- Remove committed API-key fallback values and document environment/secret-manager requirements with a safe example configuration.
- Make service and agent endpoint advertisement configurable instead of advertising loopback addresses in distributed deployments.
- Add foundation-level verification for build, packaging, configuration hygiene, authentication, and startup diagnostics.

## Capabilities

### New Capabilities

- `runtime-bootstrap`: Reproducible local startup and dependency diagnostics for the multi-module service system.
- `service-packaging`: Executable Spring Boot artifacts for deployable service modules.
- `authentication-baseline`: Credential verification, signed access-token lifecycle, and account-aware authorization baseline.
- `configuration-hygiene`: Secret-safe configuration and externally configurable distributed service endpoints.

### Modified Capabilities

None.

## Impact

- Root and child Maven build configuration, service startup documentation, and new local diagnostic scripts.
- `auth-service` login/logout/RPC authorization behavior and the demo database seed format.
- Nacos templates and service configuration properties, including DeepSeek/DashScope, A2A, and endpoint settings.
- New or expanded unit/integration-style tests for the foundation behavior.
- Existing clients must provide valid credentials and use the new token format; this is a breaking change for prototype mock tokens.
