# Repository Guidelines

## Project Structure & Module Organization
This repository is a multi-module Maven project for a real-estate middle-platform system. The root [`pom.xml`](/D:/project/BkAnentProject/BkAnentProject/pom.xml:1) manages shared versions and all service modules. Shared DTOs, RPC contracts, and base models live in `common/src/main/java`. Each microservice follows the same layout: `src/main/java` for code and `src/main/resources` for configuration. Current core modules include `gateway`, `agent-service`, `auth-service`, `listing-master-service`, `customer-service`, `business-service`, `contract-service`, `settlement-service`, and support services such as `marketing-content-service`. SQL bootstrap scripts belong in `sql/`.

## Build, Test, and Development Commands
- `mvn -gs .mvn-settings.xml -s .mvn-settings.xml compile`
  Compiles every module with the repository-local Maven settings.
- `mvn -gs .mvn-settings.xml -s .mvn-settings.xml test`
  Runs unit and integration tests once they are added.
- `mvn -pl auth-service -am -DskipTests install`
  Builds and installs the selected service's upstream modules.
- `mvn -pl auth-service "-Dspring-boot.run.profiles=local" spring-boot:run`
  Starts the dependency-free local authentication smoke profile. Run `spring-boot:run` only on the child module; do not combine it with `-am`.
- `mvn -pl auth-service "-Dspring-boot.run.profiles=distributed" spring-boot:run`
  Starts the Nacos-backed distributed profile after importing the matching data ID and setting the required environment variables.
- `mvn -q -DskipTests package`
  Produces executable Spring Boot jars for deployable modules.

## Coding Style & Naming Conventions
Use Java 17, 4-space indentation, and UTF-8 files. Keep package names lowercase (`com.bkanent.agent...`), class names PascalCase, methods/fields camelCase, and constants UPPER_SNAKE_CASE. Controllers should end with `Controller`, Dubbo providers with `RpcServiceImpl`, MyBatis Plus entities with `Entity`, mappers with `Mapper`, and domain services with `Service` / `ServiceImpl`. Prefer concise methods and explicit DTO mapping over leaking entities across service boundaries.

## Testing Guidelines
New business logic should add tests under `src/test/java` in the owning module. Prefer focused unit tests for pure logic and Spring Boot tests for controller/service wiring. Name test classes `*Test` and mirror production package structure. At minimum, cover RPC providers, authentication, RAG/Milvus integration adapters, and MyBatis query behavior.

## Commit & Pull Request Guidelines
The repository has no commit history yet, so adopt short imperative commit messages such as `feat: add listing rag indexing` or `fix: handle empty Milvus search response`. Keep one logical change per commit. Pull requests should include scope, affected modules, config or schema changes, verification commands, and sample requests/responses for API changes.

## 项目提交与推送规则
每次修改完成并验证通过后，必须立即创建 commit 并 push 到当前对应的远程分支。commit message 必须使用中文，内容应简洁准确地概括本次修改。

## 项目认知清单（AI 必读）
本仓库维护两份动态清单，所有 AI 助手与开发者在改动相关模块前必须先查阅：
- `docs/logic-rationale.md`（逻辑释义清单，编号 LR-N）：记录「结论不显而易见、容易被误改」的设计根因——如凭据签发时机（LR-1）、状态机权限表（LR-5）、脱敏域自洽（LR-6）、MCP 只读边界（LR-8）。修改 interview-service、common-skill、agent-service 编排相关代码前，先查对应条目避免破坏既定逻辑；做出新的非显然设计决策后，必须在同一提交内追加条目（结论 → 根因 → 代码位置 → 关联）。
- `docs/known-issues.md`（Bug 与已知问题清单，编号 KI-N）：记录已修/待修缺陷与设计限制。修复任何 bug 前先在此查重，避免重复排查；修复后在条目上更新状态与修复 commit；发现新问题（含排查中确认的隐性缺陷）必须当日追加。修改运行面话轮或状态机时，KI-2 的防回归单测规则（新增 transition 调用点必须同步加单测）同样适用。
两份清单与代码同仓同提交维护，是项目设计决策与已知问题的唯一权威来源；条目编号稳定不复用，代码注释与 commit message 中引用时写 `LR-N` / `KI-N`。

## Security & Configuration Tips
Do not hardcode secrets. Supply MySQL, Nacos, DeepSeek, DashScope, token, and Milvus values through environment variables or a secret manager. Review `sql/mysql-init.sql` before applying it to shared environments; existing plaintext auth rows require a BCrypt migration.
