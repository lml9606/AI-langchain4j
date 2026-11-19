-- ============================================================
-- AI Agent 业务表结构（PostgreSQL + pgvector）
-- 幂等脚本：应用启动后由 SchemaInitializer 异步执行
-- 注意：LangGraph4j Checkpoint 相关表由 PostgresSaver.setup() 自动创建，不在此维护
-- ============================================================

-- 向量扩展（pgvector/pgvector:pg16 镜像已内置）
CREATE EXTENSION IF NOT EXISTS vector;

-- 用户表（JWT 认证：BCrypt 密码哈希，用户名全局唯一；user_id 直接作为数据隔离维度）
CREATE TABLE IF NOT EXISTS ai_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,           -- 登录名（注册时唯一校验，查询走唯一索引）
    password_hash VARCHAR(128) NOT NULL,                  -- BCrypt 哈希（含盐，不可逆；明文永不落库）
    role          VARCHAR(16)  NOT NULL DEFAULT 'USER',   -- 角色：USER / ADMIN（注册默认 USER；管理员由运维 SQL 指定，不提供注册提权通道）
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 存量库迁移：旧表补 role 列（幂等；新库 CREATE 已含该列，此处自动跳过）
ALTER TABLE ai_user ADD COLUMN IF NOT EXISTS role VARCHAR(16) NOT NULL DEFAULT 'USER';

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
-- 已存在表补列（幂等）：assistant 消息的检索上下文快照（截断存储），
-- 改写视图锚定资料原文用（追问指代消解不锚定可能带偏的生成答案）；user 消息为空
ALTER TABLE ai_message ADD COLUMN IF NOT EXISTS context TEXT;

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
-- 已存在表补列（幂等）：检索根因（COMPLEX_QUERY/EXPRESSION_ISSUE/KNOWLEDGE_MISSING，正常命中为空）
-- 数据闭环：按 node_name='workflow' 与 root_cause 分组统计知识缺口/表述问题，驱动补知识与改写优化
ALTER TABLE ai_graph_execution ADD COLUMN IF NOT EXISTS root_cause VARCHAR(32);
-- 根因统计索引（按根因聚合审计记录，支撑知识缺口报表）
CREATE INDEX IF NOT EXISTS idx_execution_root_cause ON ai_graph_execution (root_cause, created_at DESC);

-- 知识块表（pgvector 向量检索）
CREATE TABLE IF NOT EXISTS ai_knowledge_chunk (
    id           BIGSERIAL PRIMARY KEY,
    content      TEXT NOT NULL,
    embedding    vector(1024),                             -- text-embedding-v3 输出维度
    metadata     JSONB,                                    -- 来源、标签等扩展信息
    document_id  VARCHAR(64),                              -- 逻辑文档 ID（同一次导入的父/子块共享）
    version      INTEGER NOT NULL DEFAULT 1,               -- 文档版本（同 document_id 重新导入时递增）
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- 已存在表补列（幂等）：所属用户（NULL = 公共库对所有用户可见；非 NULL 仅本用户可见/可查）
ALTER TABLE ai_knowledge_chunk ADD COLUMN IF NOT EXISTS user_id VARCHAR(64);
-- 用户隔离检索索引：混合检索双通道均按 (user_id, id) 过滤，支撑单用户知识库下推
CREATE INDEX IF NOT EXISTS idx_knowledge_user ON ai_knowledge_chunk (user_id);
-- 已存在表补列（幂等）：知识块更新时间（知识治理更新闭环审计）
ALTER TABLE ai_knowledge_chunk ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;
-- 已存在表补列（幂等）：父子分块结构（文档导入：父块存整章上下文不嵌入，子块嵌入供检索）
ALTER TABLE ai_knowledge_chunk ADD COLUMN IF NOT EXISTS parent_id BIGINT;
ALTER TABLE ai_knowledge_chunk ADD COLUMN IF NOT EXISTS chunk_type VARCHAR(8) NOT NULL DEFAULT 'CHILD';
ALTER TABLE ai_knowledge_chunk ADD COLUMN IF NOT EXISTS title VARCHAR(256);
-- 文档版本：document_id 标识逻辑文档，version 标识该文档的一次完整导入；父/子块共享同一组值
ALTER TABLE ai_knowledge_chunk ADD COLUMN IF NOT EXISTS document_id VARCHAR(64);
ALTER TABLE ai_knowledge_chunk ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 1;
-- 父子关联索引：命中子块后按 parent_id 取兄弟块/父块（滑窗装配与父块内容重建）
CREATE INDEX IF NOT EXISTS idx_knowledge_parent ON ai_knowledge_chunk (parent_id);
-- 文档版本索引：按用户 + 文档版本过滤 / 清理旧版本
CREATE INDEX IF NOT EXISTS idx_knowledge_document
    ON ai_knowledge_chunk (user_id, document_id, version);
-- HNSW 向量索引（余弦距离；写入量大的场景可考虑先写后建索引）
CREATE INDEX IF NOT EXISTS idx_knowledge_embedding
    ON ai_knowledge_chunk USING hnsw (embedding vector_cosine_ops);

-- 混合检索稀疏通道：稀疏向量列（jieba 分词 + log-TF 加权 + hashing trick → pgvector sparsevec，2^20 维槽位）；
-- 与稠密语义向量双路召回 RRF 融合：稠密管"语义泛化"，稀疏管"专有名词/型号/缩写精确命中"
ALTER TABLE ai_knowledge_chunk ADD COLUMN IF NOT EXISTS sparse_vector sparsevec(1048576);
-- 稀疏向量 HNSW 索引（内积；稀疏向量均已 L2 归一化，内积即余弦相似度）
CREATE INDEX IF NOT EXISTS idx_knowledge_sparse
    ON ai_knowledge_chunk USING hnsw (sparse_vector sparsevec_ip_ops);
-- 历史词面通道（pg_trgm GIN 索引）已由稀疏向量通道取代：删除旧索引避免双份维护开销（扩展本身保留）
DROP INDEX IF EXISTS idx_knowledge_content_trgm;

-- 文档主表（文档聚合根）：active_version 是"当前对外可读版本"的唯一事实来源。
-- 导入时先写新版本块但不改指针，成功后只更新这一行；因此切换成本与文档大小无关，
-- 导入中的新版本对检索完全不可见，失败也不会污染旧版本。
CREATE TABLE IF NOT EXISTS ai_document (
    id             BIGSERIAL PRIMARY KEY,
    document_id    VARCHAR(64) NOT NULL,                  -- 逻辑文档 ID（与 ai_knowledge_chunk.document_id 对应）
    user_id        VARCHAR(64) NOT NULL,                  -- 归属用户（私有库）
    active_version INTEGER     NOT NULL DEFAULT 0,        -- 当前对外可读版本；0 = 尚未成功导入过
    status         VARCHAR(16) NOT NULL DEFAULT 'READY',  -- READY / IMPORTING / FAILED（导入互斥与可观测）
    content_hash   VARCHAR(64),                           -- 内容指纹（SHA-256），用于识别无变更重传
    source         VARCHAR(512),                          -- 来源（文件名等，便于运维定位）
    activated_at   TIMESTAMPTZ,                           -- 最近一次成功发布时间（旧版本保留窗口起点）
    purged_at      TIMESTAMPTZ,                           -- 最近一次旧版本回收时间（避免重复全量扫描）
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_document_user UNIQUE (document_id, user_id)
);
-- 文档列表/管理页索引：按用户 + 最近更新时间倒序
CREATE INDEX IF NOT EXISTS idx_document_user ON ai_document (user_id, updated_at DESC);
-- 旧版本回收扫描索引：按发布时间取已过保留期的文档
CREATE INDEX IF NOT EXISTS idx_document_purge ON ai_document (activated_at);

-- 存量回填（幂等）：已有文档按块里的 MAX(version) 作为当前可读版本；
-- document_id 为空的存量块不参与（检索侧对其保留"不按版本过滤"的旧行为）。
INSERT INTO ai_document (document_id, user_id, active_version, status, activated_at)
SELECT c.document_id, c.user_id, MAX(c.version), 'READY', now()
FROM ai_knowledge_chunk c
WHERE c.document_id IS NOT NULL
  AND c.user_id IS NOT NULL
GROUP BY c.document_id, c.user_id
ON CONFLICT (document_id, user_id) DO NOTHING;

-- 工具演示数据表：读工具查询、写工具修改的对象（按 user_id 隔离，不同用户互不可见）
CREATE TABLE IF NOT EXISTS ai_tool_demo_record (
    id         BIGSERIAL PRIMARY KEY,
    user_id    VARCHAR(64)  NOT NULL,
    title      VARCHAR(200) NOT NULL,
    content    TEXT,
    status     VARCHAR(16)  NOT NULL DEFAULT 'OPEN',   -- OPEN / DONE
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_tool_demo_user ON ai_tool_demo_record (user_id, status, id);

-- 工具执行台账：写工具幂等与"执行结果可查询"的唯一事实源。
-- Redis 里的确认令牌只是短期授权；即使令牌被删/过期/Redis 重启，这张表仍能回答"到底执行没执行"。
CREATE TABLE IF NOT EXISTS ai_tool_execution (
    id              BIGSERIAL PRIMARY KEY,
    execution_id    VARCHAR(128) NOT NULL,                -- 幂等键（当前= confirmationId）
    user_id         VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(64)  NOT NULL,
    tool            VARCHAR(64)  NOT NULL,
    args_hash       VARCHAR(64),                          -- 参数指纹：同键不同参直接拒绝
    status          VARCHAR(16)  NOT NULL,                -- RUNNING / SUCCEEDED / FAILED
    result          TEXT,                                 -- 成功结果（重放时原样返回）
    error_code      VARCHAR(64),
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_tool_execution UNIQUE (user_id, execution_id)
);
CREATE INDEX IF NOT EXISTS idx_tool_execution_user ON ai_tool_execution (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tool_execution_status ON ai_tool_execution (status, updated_at);

-- 工单表（运营闭环：转人工自动建单，运营回复后结单；根因字段联动审计表驱动知识治理）
CREATE TABLE IF NOT EXISTS ai_ticket (
    id              BIGSERIAL PRIMARY KEY,
    ticket_no       VARCHAR(64)  NOT NULL UNIQUE,        -- 工单号（展示用）
    user_id         VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(64)  NOT NULL,               -- 触发转人工的会话
    question        TEXT         NOT NULL,               -- 触发转人工的问题
    root_cause      VARCHAR(32),                         -- 检索根因（KNOWLEDGE_MISSING 等，可空）
    status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN',-- OPEN / RESOLVED / CLOSED
    assignee        VARCHAR(64),                         -- 处理人（运营侧）
    reply           TEXT,                                -- 运营回复（知识补录后告知用户）
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_ticket_user ON ai_ticket (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ticket_status ON ai_ticket (status, created_at DESC);

-- 敏感词表（输出侧审核：生成答案命中任一启用词即拦截替换为拒答文案；专业场景应接入内容安全 API）
CREATE TABLE IF NOT EXISTS ai_sensitive_word (
    id         BIGSERIAL PRIMARY KEY,
    word       VARCHAR(128) NOT NULL UNIQUE,             -- 敏感词（字面包含匹配）
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Prompt 配置表（配置化版本化：DB 覆盖代码默认值，保存后热生效；version 每次保存递增）
-- 企业级可扩展 ai_prompt_version 历史表做多版本回滚，此处保留当前生效版本 + 版本号
CREATE TABLE IF NOT EXISTS ai_prompt_config (
    id         BIGSERIAL PRIMARY KEY,
    prompt_key VARCHAR(64) NOT NULL UNIQUE,              -- 提示词 key（对应 PromptTemplates 常量名）
    prompt_text TEXT NOT NULL,                           -- 生效文本（覆盖代码默认值）
    version    INTEGER     NOT NULL DEFAULT 1,           -- 版本号（每次保存 +1，支持审计）
    updated_by VARCHAR(64),                              -- 最后修改人
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
