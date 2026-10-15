## Purpose

提供 Supervisor 与全部子 Agent 服务可共同依赖的技能（Skill）基建库：负责 skill.md 文件的加载、注册、按领域检索与热加载，作为技能目录、工具收窄清单与执行指引的单一数据源。该模块角色中立，不感知调用方是 Supervisor 还是子 Agent。

## ADDED Requirements

### Requirement: 技能基建模块 SHALL 作为独立共享库供双端依赖

技能基建 SHALL 独立于 `common` 与所有可部署服务，成为根 POM 聚合下的独立库模块；任何子 Agent 服务模块与 Supervisor 模块 SHALL 能够直接声明对该模块的编译期依赖，且该模块不得反向依赖任何服务模块或 Supervisor 模块。

#### Scenario: 子 Agent 服务声明依赖

- **WHEN** 任一子 Agent 服务模块在 pom 中声明技能基建模块依赖并执行全模块编译
- **THEN** 编译成功且该服务代码可引用技能基建的加载、注册与运行时组件

#### Scenario: 模块不反向依赖服务

- **WHEN** 检查技能基建模块的全部 import
- **THEN** 不存在对任何业务服务模块或 Supervisor 模块内部包的引用

### Requirement: 技能加载 SHALL 合并 classpath 与外部目录两个来源

技能基建 SHALL 扫描 classpath 的 `skills/**/*.md` 并解析 YAML frontmatter 与 Markdown 正文；SHALL 支持外部技能目录加载并与 classpath 结果合并，同名技能冲突时外部目录优先；frontmatter 缺失必需字段（name、description、domain）的文件 SHALL 被跳过并记录告警。

#### Scenario: 加载 classpath 技能

- **WHEN** 服务启动且 classpath 存在合法 skill md 文件
- **THEN** 每个文件被解析为一个技能定义，可通过其领域检索到

#### Scenario: 外部目录同名覆盖

- **WHEN** 外部目录中存在与 classpath 同名的合法技能文件
- **THEN** 该技能以外部目录版本生效

#### Scenario: 非法文件跳过

- **WHEN** 某技能文件缺失必需 frontmatter 字段
- **THEN** 该文件被跳过且不影响其他技能加载，同时输出告警日志

### Requirement: 技能注册表 SHALL 支持外部目录热加载

当配置开启监听且外部技能目录发生文件新增、修改或删除时，注册表 SHALL 在不重启进程的情况下刷新技能集合，并使后续请求可见变化。

#### Scenario: 外部目录新增技能

- **WHEN** 外部技能目录中放入新的合法 skill md 文件
- **THEN** 无需重启，后续请求可通过新技能名称检索到该技能

#### Scenario: 未配置外部目录

- **WHEN** 未配置外部目录路径
- **THEN** 仅使用 classpath 技能，热加载监听不启动且不报错

### Requirement: 技能配置 SHALL 收敛到统一前缀

技能基建的配置项 SHALL 统一使用 `agent.skills` 配置前缀并通过类型安全配置类绑定，外部目录路径与监听开关语义保持与既有行为一致。

#### Scenario: 配置绑定

- **WHEN** 服务以 `agent.skills.external-dir` 与 `agent.skills.watch-enabled` 配置启动
- **THEN** 配置值被类型安全地绑定到技能基建并按语义生效

### Requirement: 技能检索 SHALL 支持领域过滤

注册表 SHALL 支持按领域（domain）过滤操作类技能（排除 Supervisor 知识类技能），供各服务只获取本领域技能目录。

#### Scenario: 按领域检索

- **WHEN** 以领域 `contract` 检索操作类技能
- **THEN** 仅返回 domain 为 `contract` 且非 Supervisor 知识类的技能
