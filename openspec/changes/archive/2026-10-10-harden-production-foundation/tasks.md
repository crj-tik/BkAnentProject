## 1. Shared authentication context

- [x] 1.1 Add a serializable authenticated-principal DTO and principal lookup method to the common RPC contract while retaining the boolean compatibility method.
- [x] 1.2 Make authentication RPC resolve token claims only after checking signature, expiry, revocation, and active non-deleted account status.
- [x] 1.3 Update the gateway filter to strip client identity headers, call the principal lookup RPC, and inject trusted account context for authenticated downstream requests.
- [x] 1.4 Add focused authentication and gateway tests for invalid accounts, forged headers, valid principal propagation, and RPC fallback/error behavior.

## 2. Recoverable asynchronous runtime

- [x] 2.1 Extend async task and workflow persistence models and MySQL initialization with attempt count, lease owner, lease expiry, and next-attempt time fields.
- [x] 2.2 Add bounded executor and retry/lease configuration properties with safe development defaults and distributed overrides.
- [x] 2.3 Replace startup failure reconciliation with stale-lease recovery and atomic claim/update methods for tasks and workflows.
- [x] 2.4 Make task and workflow execution classify transient/permanent failures, honor cancellation, apply bounded retry delay, and perform idempotent terminal transitions.
- [x] 2.5 Add focused async runtime policy tests for stale leases, bounded retries, and safe failure classification; verify cancellation and duplicate-claim guards in the atomic update conditions.

## 3. Provider safety

- [x] 3.1 Inventory provider selection points and add explicit local versus distributed integration-mode configuration for contract, notification, media, and promotion services.
- [x] 3.2 Prevent simulated providers from loading in distributed/production mode and add visible simulation markers to permitted local mock results and logs.
- [x] 3.3 Ensure real-provider failures remain failure/pending outcomes without silent mock fallback, and add focused provider-selection tests.

## 4. Readiness and diagnostics

- [x] 4.1 Configure actuator liveness/readiness groups and a safe dependency readiness indicator for each distributed service profile.
- [x] 4.2 Align preflight diagnostics and service readiness messages with selected mode, required dependency categories, and non-secret actionable guidance.
- [x] 4.3 Add verification for local auth readiness and distributed failure readiness when Nacos, database, broker, or object storage is unavailable.

## 5. CI and repository verification

- [x] 5.1 Add a Java 17 GitHub Actions workflow for compile, tests, deployable packaging, and strict OpenSpec validation.
- [x] 5.2 Add a checked-in credential-safety scan command and make the CI workflow fail on detected usable credentials.
- [x] 5.3 Run the full Maven verification, OpenSpec strict validation, and clean configuration scan; record any intentionally environment-dependent checks.

### Verification notes

- `mvn -B -s .mvn-settings.xml clean package`: passed; all 16 modules compiled, tested, and packaged. 34 tests passed with no failures.
- `openspec validate --all --strict --no-interactive`: passed; 5 items validated.
- `pwsh -NoProfile -File .\\scripts\\local\\check-credentials.ps1`: passed; no usable credentials found.
- Local `auth-service` smoke test: passed with the `local` profile, H2 database, `/auth/health`, `/actuator/health/readiness`, and `/actuator/health/liveness` returning HTTP 200/UP.
- Distributed dependency failure checks remain environment-dependent by design and require Nacos, database, broker, and object-storage endpoints supplied through deployment configuration; no external infrastructure was available in this local run.
