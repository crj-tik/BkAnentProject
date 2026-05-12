# Repository Guidelines

## Project Structure & Module Organization
This repository is a multi-module Maven project for a real-estate middle-platform system. The root [`pom.xml`](/D:/project/BkAnentProject/BkAnentProject/pom.xml:1) manages shared versions and all service modules. Shared DTOs, RPC contracts, and base models live in `common/src/main/java`. Each microservice follows the same layout: `src/main/java` for code and `src/main/resources` for configuration. Current core modules include `gateway`, `agent-service`, `auth-service`, `listing-master-service`, `customer-service`, `business-service`, `contract-service`, `settlement-service`, and support services such as `marketing-content-service`. SQL bootstrap scripts belong in `sql/`.

## Build, Test, and Development Commands
- `mvn -gs .mvn-settings.xml -s .mvn-settings.xml compile`
  Compiles every module with the repository-local Maven settings.
- `mvn -gs .mvn-settings.xml -s .mvn-settings.xml test`
  Runs unit and integration tests once they are added.
- `mvn -pl agent-service -am spring-boot:run`
  Starts one service and builds required upstream modules.
- `mvn -q -DskipTests package`
  Produces runnable jars quickly for local verification.

## Coding Style & Naming Conventions
Use Java 17, 4-space indentation, and UTF-8 files. Keep package names lowercase (`com.bkanent.agent...`), class names PascalCase, methods/fields camelCase, and constants UPPER_SNAKE_CASE. Controllers should end with `Controller`, Dubbo providers with `RpcServiceImpl`, MyBatis Plus entities with `Entity`, mappers with `Mapper`, and domain services with `Service` / `ServiceImpl`. Prefer concise methods and explicit DTO mapping over leaking entities across service boundaries.

## Testing Guidelines
There is no committed test suite yet; new business logic should add tests under `src/test/java` in the owning module. Prefer Spring Boot tests for controller/service wiring and focused unit tests for mapping or rule logic. Name test classes `*Test` and mirror production package structure. At minimum, cover RPC providers, RAG/Milvus integration adapters, and MyBatis query behavior.

## Commit & Pull Request Guidelines
The repository has no commit history yet, so adopt short imperative commit messages such as `feat: add listing rag indexing` or `fix: handle empty Milvus search response`. Keep one logical change per commit. Pull requests should include scope, affected modules, config or schema changes, verification commands, and sample requests/responses for API changes.

## Security & Configuration Tips
Do not hardcode secrets. Supply MySQL, Nacos, DeepSeek, and Milvus values through environment variables in `application.yml`. Review `sql/mysql-init.sql` before applying it to shared environments.
