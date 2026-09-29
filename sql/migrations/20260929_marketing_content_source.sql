-- 营销内容来源标识：manual（人工/外层模型提交）或 llm（专用生成路径）
ALTER TABLE marketing_content
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'manual' COMMENT '内容来源: manual/llm' AFTER copywriting;
