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

-- 消息表
CREATE TABLE IF NOT EXISTS ai_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    role            VARCHAR(16) NOT NULL,                  -- user / assistant / system
    content         TEXT        NOT NULL,
    model           VARCHAR(64),
    token_usage     INTEGER,                               -- 该条消息消耗 token 数
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_message_conversation ON ai_message (conversation_id, id);

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
