# Subagent 显式技能发布契约

九服务的官方 Agent Card 保留原业务 skill，同时发布本地可执行技能。`capabilities.extensions` 的 `urn:bkagent:skill-selection:v1` 扩展包含 `contractVersion: "1"`、owner 和 skills 数组。每条映射有 cardSkillId、name、owner、version、contentHash；同一 Card 中 ID 必须唯一。

发布 owner 为 listing、compare、marketing、trade、media、contract、settlement、notification、interview。Card skill ID 为 `bk-skill/<owner>/<name>`，但消费者必须读取扩展中的精确映射并核对 Card ID，不自行拼接或从业务 intent 推断本地技能。

Supervisor 只有明确选择下游 skill 时才发送 `supervisor.skillSelection`；父技能不自动变成子技能。下游未支持、身份或版本不符时返回结构化失败，不能降级成建议性 skillHint。技能热更新后的新请求读取新内容；已有执行使用已加载快照。热更新后旧 Card 指向的内容哈希可能暂时失配，须重新发布 Card 后接收该显式选择。

验证：九 owner 的发布契约回归、实际 ReAct 工具守卫、旧 executor 不支持 explicit 与版本不符的零模型调用测试通过；全模块编译通过。分布式发布验收按 OpenSpec 第 8 组执行。
