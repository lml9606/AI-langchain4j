-- ============================================================
-- AI Agent 业务表结构（PostgreSQL + pgvector）
-- 幂等脚本：应用启动时由 spring.sql.init 执行
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
-- 父子关联索引：命中子块后按 parent_id 取兄弟块/父块（滑窗装配与父块内容重建）
CREATE INDEX IF NOT EXISTS idx_knowledge_parent ON ai_knowledge_chunk (parent_id);
-- HNSW 向量索引（余弦距离；写入量大的场景可考虑先写后建索引）
CREATE INDEX IF NOT EXISTS idx_knowledge_embedding
    ON ai_knowledge_chunk USING hnsw (embedding vector_cosine_ops);

-- 混合检索词面通道：pg_trgm 三元组相似度（中文无需分词；pgvector 官方镜像内置 contrib，开箱可用）
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_knowledge_content_trgm
    ON ai_knowledge_chunk USING gin (content gin_trgm_ops);

-- 评测用例表（离线回归集：问题 + 期望关键词/参考答案；enabled 控制是否参与评测）
CREATE TABLE IF NOT EXISTS ai_eval_case (
    id              BIGSERIAL PRIMARY KEY,
    question        TEXT    NOT NULL,                   -- 评测问题
    keywords        TEXT,                               -- 期望关键词（JSON 数组字符串，召回评测命中判断）
    expected_answer TEXT,                               -- 参考答案（忠实度评测参考，可空）
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 评测结果表（runEval 每批一个 run_id，每用例一行；核心指标：召回率 + 忠实度）
CREATE TABLE IF NOT EXISTS ai_eval_result (
    id             BIGSERIAL PRIMARY KEY,
    run_id         VARCHAR(64) NOT NULL,                -- 单次评测批次 ID（一批跑全量启用用例）
    case_id        BIGINT      NOT NULL,
    question       TEXT        NOT NULL,
    retrieved_ctx  TEXT,                                -- 检索到的上下文（截断存储）
    answer         TEXT,                                -- 基于检索上下文生成的答案
    recall_score   NUMERIC(5,4),                        -- 召回率 0~1（期望关键词在检索上下文命中比例；无关键词为空）
    faithfulness   NUMERIC(5,4),                        -- 忠实度 0~1（LLM 判分；判分失败为空）
    status         VARCHAR(16) NOT NULL,                -- SUCCESS / FAILED
    error_msg      TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_eval_result_run ON ai_eval_result (run_id, id);
CREATE INDEX IF NOT EXISTS idx_eval_result_created ON ai_eval_result (created_at DESC);

-- 反馈表（运营闭环：点踩/点赞 + 补充说明，驱动知识补录与改写优化）
CREATE TABLE IF NOT EXISTS ai_feedback (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,                -- 会话 ID（归属校验）
    user_id         VARCHAR(64) NOT NULL,
    message_id      BIGINT,                              -- 被反馈的 assistant 消息 ID（可空）
    rating          VARCHAR(8)  NOT NULL,                -- UP / DOWN
    comment         TEXT,                                -- 补充说明（点踩原因）
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_feedback_conversation ON ai_feedback (conversation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_feedback_user ON ai_feedback (user_id, created_at DESC);

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

-- 简历优化记录表（一次优化请求一行：底稿 + 改稿 + 变更清单 + diff）
-- 底稿/改稿存全文：用户回看改动与 diff 重算都需要原文；按 user_id 做数据隔离
CREATE TABLE IF NOT EXISTS ai_resume_revision (
    id           BIGSERIAL PRIMARY KEY,
    revision_id  VARCHAR(64)  NOT NULL UNIQUE,           -- 业务记录 ID（同时作为图 threadId）
    user_id      VARCHAR(64)  NOT NULL,                  -- 所属用户
    resume_title VARCHAR(200),                           -- 简历标题（展示用）
    mode         VARCHAR(20)  NOT NULL,                  -- TARGETED / FREE
    target_json  TEXT,                                   -- 目标岗位/选定章节/提示词快照
    original_md  TEXT         NOT NULL,                  -- 简历原文
    revised_md   TEXT,                                   -- 修改后的整份简历
    changes_json TEXT,                                   -- 变更点清单
    diff_json    TEXT,                                   -- 行级 diff
    status       VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',-- RUNNING / DONE / ERROR / SAVED
    model        VARCHAR(100),                           -- 实际使用的模型名
    token_usage  INTEGER,                                -- 生成消耗 token 总数
    error        TEXT,                                   -- 失败原因（成功时为空）
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_resume_revision_user ON ai_resume_revision (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_resume_revision_status ON ai_resume_revision (status, created_at DESC);
-- 已存在表补列（幂等）：触发本次优化的会话 ID（对话内触发时回写完成通知；独立接口触发为空）
ALTER TABLE ai_resume_revision ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(64);

-- 用户简历档案表（每用户仅一份：对话内触发优化时取该档案作为简历原文，不把 2 万字原文塞进对话消息表）
CREATE TABLE IF NOT EXISTS ai_user_resume (
    id         BIGSERIAL PRIMARY KEY,
    user_id    VARCHAR(64) NOT NULL UNIQUE,           -- 所属用户（唯一：每用户仅一份，覆盖式 upsert）
    title      VARCHAR(200),                          -- 简历标题（展示用）
    content    TEXT        NOT NULL,                  -- 简历原文（Markdown/纯文本）
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
