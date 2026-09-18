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

## Security & Configuration Tips
Do not hardcode secrets. Supply MySQL, Nacos, DeepSeek, DashScope, token, and Milvus values through environment variables or a secret manager. Review `sql/mysql-init.sql` before applying it to shared environments; existing plaintext auth rows require a BCrypt migration.
