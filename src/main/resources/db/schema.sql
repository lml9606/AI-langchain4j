-- ============================================================
-- AI Agent 业务表结构（PostgreSQL + pgvector）
-- 幂等脚本：应用启动时由 spring.sql.init 执行
-- 注意：LangGraph4j Checkpoint 相关表由 PostgresSaver.setup() 自动创建，不在此维护
-- ============================================================

-- 向量扩展（pgvector/pgvector:pg16 镜像已内置）
CREATE EXTENSION IF NOT EXISTS vector;

-- 会话表
CREATE TABLE IF NOT EXISTS ai_conversation (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL UNIQUE,          -- 业务会话 ID（同时作为 LangGraph thread_id）
    user_id         VARCHAR(64)  NOT NULL,
    title           VARCHAR(256),                          -- 会话标题（取首条用户消息截断）
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',-- ACTIVE / CLOSED
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_conversation_user ON ai_conversation (user_id, created_at DESC);

-- 消息表（全量对话事实源：user/assistant 只追加；segment_id 标记话题段归属）
CREATE TABLE IF NOT EXISTS ai_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    role            VARCHAR(16) NOT NULL,                  -- user / assistant / system
    content         TEXT        NOT NULL,
    model           VARCHAR(64),
    token_usage     INTEGER,                               -- 该条消息消耗 token 数
    segment_id      INTEGER,                               -- 话题段归属（user 落库后回填；assistant 继承同轮 user）
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_message_conversation ON ai_message (conversation_id, id);
-- 段内查询索引：生成视图/摘要按段号过滤（切回旧话题时不混入他段消息）
CREATE INDEX IF NOT EXISTS idx_message_segment ON ai_message (conversation_id, segment_id, id);
-- 已存在表补列（幂等）
ALTER TABLE ai_message ADD COLUMN IF NOT EXISTS segment_id INTEGER;

-- 会话段摘要表（话题段索引：不存对话内容，只记段的 topic/摘要/起点/摘要进度，
-- 消息内容全在 ai_message，靠消息的 segment_id 与段号关联）
CREATE TABLE IF NOT EXISTS ai_conversation_summary (
    id                 BIGSERIAL PRIMARY KEY,
    conversation_id    VARCHAR(64) NOT NULL,
    segment_id         INTEGER NOT NULL,              -- 会话内话题段序号（从1递增）
    topic              VARCHAR(128),                  -- 话题标签（改写器输出）
    content            TEXT NOT NULL,                 -- 段摘要内容（增量追加）
    start_message_id   BIGINT,                        -- 段起点消息id（含）
    covered_message_id BIGINT,                        -- 摘要已覆盖到的消息id（增量锚点，可空）
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (conversation_id, segment_id)
);
CREATE INDEX IF NOT EXISTS idx_summary_conversation ON ai_conversation_summary (conversation_id, segment_id);

-- 历史数据回填（幂等）：把旧消息按段区间打上段号（只补空值，新数据不触碰）
UPDATE ai_message m
SET segment_id = sub.segment_id
FROM (
    SELECT s.conversation_id, s.segment_id, s.start_message_id,
           COALESCE(LEAD(s.start_message_id) OVER (PARTITION BY s.conversation_id ORDER BY s.segment_id),
                    9223372036854775807) AS next_start
    FROM ai_conversation_summary s
) sub
WHERE m.conversation_id = sub.conversation_id
  AND m.id >= sub.start_message_id
  AND m.id < sub.next_start
  AND m.segment_id IS NULL;

-- 图执行审计表（节点级可观测：每次节点执行一行）
CREATE TABLE IF NOT EXISTS ai_graph_execution (
    id              BIGSERIAL PRIMARY KEY,
    execution_id    VARCHAR(64) NOT NULL,                  -- 单次图执行 ID
    conversation_id VARCHAR(64) NOT NULL,                  -- 会话 ID（thread_id）
    node_name       VARCHAR(64) NOT NULL,                  -- 图节点名
    status          VARCHAR(16) NOT NULL,                  -- RUNNING / SUCCESS / FAILED
    input_json      TEXT,
    output_json     TEXT,
    error_msg       TEXT,
    cost_ms         BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_execution_thread ON ai_graph_execution (conversation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_execution_id ON ai_graph_execution (execution_id);

-- 知识块表（pgvector 向量检索）
CREATE TABLE IF NOT EXISTS ai_knowledge_chunk (
    id           BIGSERIAL PRIMARY KEY,
    content      TEXT NOT NULL,
    embedding    vector(1024),                             -- text-embedding-v3 输出维度
    metadata     JSONB,                                    -- 来源、标签等扩展信息
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- HNSW 向量索引（余弦距离；写入量大的场景可考虑先写后建索引）
CREATE INDEX IF NOT EXISTS idx_knowledge_embedding
    ON ai_knowledge_chunk USING hnsw (embedding vector_cosine_ops);
