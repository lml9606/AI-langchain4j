# API 文档

> 统一约定：`/api/**` 除白名单（`/api/auth/**`、`/actuator/**`）外均需携带
> `Authorization: Bearer <token>`（JWT，登录/注册接口签发）。
> 统一响应体：`{"code": 0, "message": "成功", "data": ...}`；`code != 0` 表示接口调用失败；业务动作是否成功看 `data.business`。
> 角色体系：`USER`（注册默认）/ `ADMIN`（运维 SQL 指定）；标注 **需 ADMIN** 的接口，
> 普通用户访问返回 HTTP 403 + `code=40300`。
>
> 管理员设置（无注册提权通道）：
> `UPDATE ai_user SET role = 'ADMIN' WHERE username = '<登录名>';`（角色变更重登后生效）

## 1. 认证 `/api/auth`（免 Token）

| 方法 | 路径 | 说明 | 入参 | 出参 data |
|---|---|---|---|---|
| POST | `/api/auth/register` | 注册（注册即登录，签发 Token；角色固定 USER） | `{username, password}` | `{token, username, expireHours, role}` |
| POST | `/api/auth/login` | 登录，校验密码签发 Token | `{username, password}` | `{token, username, expireHours, role}` |

错误码：`40101` 用户名或密码错误；`40902` 用户名已存在。

## 2. Agent 对话 `/api/agent`

| 方法 | 路径 | 说明 | 关键入参 |
|---|---|---|---|
| POST | `/api/agent/chat` | 同步对话（invoke 全图） | `{conversationId?, query, model?, history?}` |
| POST | `/api/agent/chat/stream` | 流式对话（SSE） | 同 chat |
| POST | `/api/agent/confirm` | 确认/取消写操作（同一会话只允许一个待确认动作） | `{conversationId, confirmationId, approved}` |
| POST | `/api/agent/resume` | 断点恢复（从最近 checkpoint 继续） | `{conversationId, query?}` |
| GET | `/api/agent/state/{threadId}` | 查询 checkpoint 执行状态 | 路径参数 threadId |
| GET | `/api/agent/conversation/{id}/messages` | 会话历史（默认 50 条，上限 200） | `?limit=50` |

安全：会话归属校验（403 防水平越权）；身份以 Token 为准（服务端从 Authorization 头解析，请求体不携带用户 ID）。
`confirm` 的 `approved=true` 表示执行、`false` 表示取消；响应 `data.business.success/errorCode/errorMessage` 表示业务动作结果。确认卡片生成时 `ai_task_run` 与每步 `ai_tool_execution` 均为 `PENDING`，确认后为 `RUNNING`，最终为 `SUCCEEDED/FAILED/PARTIAL_FAILED/CANCELLED`；Redis 仅保存带 TTL 的确认令牌，数据库台账保存写操作执行事实。`confirmationId` 同时作为运行 ID，步骤幂等键为 `confirmationId#stepNo`，重复确认不会重复修改业务数据。

多步写：`data.pendingAction.steps` 展示完整计划；执行按步骤顺序进行，任一步失败即停止。响应 `business.errorCode=PARTIAL_FAILED` 表示前面步骤已提交、从失败步骤开始重试；用户再次确认同一 `confirmationId` 时，已完成步骤幂等重放，只重试失败步骤。

### 2.1 流式对话事件（`POST /api/agent/chat/stream`）

响应为 `text/event-stream`，事件 `data` 均为 JSON 字符串：

| 事件名 | 说明 | 主要字段 |
|---|---|---|
| `meta` | 本轮元信息（最先发送） | `conversationId/model/answerMode/visibility` |
| `node` | 图节点进度（每完成一个节点发送一次） | `node/intent/taskOperation/retryCount/reactStep/fallback/handoff/done/error` |
| `done` | 最终结果（与同步 chat 的 `data` 结构一致） | `conversationId/result/fallback/citations/confidence/pendingAction/business` |
| `error` | 执行失败（流开始后无法再返回统一响应体） | `code/message` |

另有 SSE 注释帧（`: ping`）每 15 秒保活，不触发前端业务事件。客户端断开后服务端停止继续消费节点事件并跳过半成品助手消息落库；底层图任务由整体超时与线程池收敛。

### 2.2 长期记忆 `/api/agent/memories`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/agent/memories?limit=50` | 查看自己的长期记忆（按更新时间倒序，limit 上限 200） |
| POST | `/api/agent/memories` | 手动新增/覆盖记忆 `{memoryType, memoryKey, content, importance?}` |
| PATCH | `/api/agent/memories/{id}` | 修改内容和重要度 `{content, importance?}`；允许调低重要度，并把候选记忆提升为生效 |
| DELETE | `/api/agent/memories/{id}` | 删除自己的记忆；不存在或无权返回 404 |

`memoryType` 只能是 `PREFERENCE`（偏好）/ `FACT`（稳定事实）/ `TASK_CONSTRAINT`（持续约束）/ `TEMPORARY`（临时事实）。同一用户 + 类型 + key 唯一：重复抽取覆盖内容，不无限堆积。响应中的 `status` 为 `CANDIDATE`（候选，不召回）或 `ACTIVE`（生效，可召回），`seenCount` 表示同一 key 被抽取到的次数。

写入规则：偏好和任务约束只能使用受控 key 字典；事实和临时信息可以自拟 key，但会先做同义合并。用户明确说“以后/一直/记住”时直接生效，其余先进入候选，第二次出现自动晋升。内容变化写入 `ai_user_memory_history`，主表始终保留最新版本。不同状态和类型使用不同 TTL：候选 30 天，偏好/约束 365 天，普通事实 90 天，临时事实 7 天。

召回规则：每轮只取 `ACTIVE`、未过期、非 `TEMPORARY` 且重要度达标的记忆，按“重要度 + 关键词轻量重排”取 Top N 注入生成提示词；抽取为异步任务，同一 `messageId` 只抽取一次，失败不影响对话。记忆与当前问题或工具结果冲突时，一律以当前信息为准。

只读任务响应中的 `data.businessData` 是业务明细行列表（手机号等敏感字段由读节点脱敏）；`data.citations` 是可供回答引用的证据摘要，`data.toolTrace` 是节点执行轨迹。两者用途不同：前端渲染明细使用 `businessData`，回答溯源使用 `citations`。

## 3. 知识库 `/api/knowledge`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/knowledge/upload` | 文件导入：Tika 解析 txt/md/pdf/docx，父子分块后嵌入落库；支持多文件 |
| PUT | `/api/knowledge/{id}` | 更新知识块（重嵌向量；仅本人私有块，公共库 403） |
| DELETE | `/api/knowledge/{id}` | 删除知识块（仅本人私有块，公共库 403） |
| GET | `/api/knowledge/search?query=&topK=` | 语义检索（topK 有界防御 1~10） |
| GET | `/api/knowledge/health` | 健康度：总块/私有/公共/空内容/无向量/平均长度/最近导入 |

文件导入示例（单文件更新已有逻辑文档版本）：

```bash
curl -X POST "http://localhost:8082/api/knowledge/upload?documentId=refund-policy&version=2" \
  -H "Authorization: Bearer <token>" \
  -F "files=@refund-policy.pdf"
```

`documentId`/`version` 可空：`documentId` 为空时生成新 UUID；`version` 为空时自动取当前最大版本 + 1。多文件上传不能共用同一组 `documentId`/`version`。

分块策略：父子分块。父块按 Markdown/中文章节标题切分，保留整章上下文且不嵌入；子块按段落、标题、围栏代码块等结构语义单元合并，并同时受 `agent.knowledge.chunk-size` 字符上限与 `agent.knowledge.chunk-token-limit` token 估算上限约束。检索只召回每个 `documentId` 的最新 `version`。

## 4. 运营闭环

### 4.1 工单 `/api/tickets`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/tickets` | 我的工单 |
| GET | `/api/tickets/list?status=` | 运营视角：按状态列工单（OPEN/RESOLVED/CLOSED）**需 ADMIN** |
| POST | `/api/tickets` | 手动建单（流式路径或前端触发） |
| POST | `/api/tickets/{id}/reply` | 运营回复：写入回复与处理人并置为 RESOLVED **需 ADMIN** |

自动建单：同步对话命中转人工（handoff）时服务层自动建单（同会话 OPEN 单去重，失败降级不阻断）。

### 4.2 敏感词（输出侧审核）`/api/sensitive-words`（全模块需 ADMIN）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/sensitive-words` | 全量词表 |
| POST | `/api/sensitive-words` | 新增敏感词 `{word, enabled}` |
| POST | `/api/sensitive-words/{id}/enabled` | 启用/停用 |
| DELETE | `/api/sensitive-words/{id}` | 删除敏感词 |

命中即拦截：生成答案替换为固定拒答文案（不透出命中词），词表内存热更新、DB 故障 fail-open 放行。

## 5. Prompt 配置 `/api/prompts`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/prompts` | 全部 key 配置概览（version=0 表示未覆盖、用代码默认值） |
| POST | `/api/prompts` | 保存/覆盖 `{key, text}`（白名单校验 + 版本递增 + 热生效）**需 ADMIN** |
| DELETE | `/api/prompts/{key}` | 删除覆盖 → 回退代码默认值 **需 ADMIN** |

key 必须为 `PromptTemplates` 已声明常量名（如 `INTENT_PLAN_SYSTEM`），防止任意 key 注入。

## 6. 工具管理 `/api/tools`（需 ADMIN）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/tools` | 查看启动期注册的 `ToolDefinition` |
| POST | `/api/tools/{name}/invoke` | 手工调用工具，仍经过角色、参数 Schema、超时与限流；写工具默认返回 `CONFIRMATION_REQUIRED` |

工具元数据、参数 Schema、副作用、超时、限流与角色策略以 `ToolDefinition` 为唯一事实源，不写入 `application.properties` 或数据库。数据库只保存执行事实：`ai_tool_execution`（写操作确认/执行台账）；节点与工具的可观测性由结构化日志和 Micrometer 指标承担。

## 通用错误码

| code | 含义 |
|---|---|
| 40000 | 请求参数错误 |
| 40100 / 40101 / 40102 | 未认证 / 用户名或密码错误 / Token 失效 |
| 40300 | 无权限（资源归属不符 / 角色不足，管理接口返回 HTTP 403） |
| 40400 | 资源不存在 |
| 40901 / 40902 | 会话处理中 / 用户名已存在 |
| 42900 / 42901 | 请求过于频繁 / 触发限流 |
| 50010 / 50000 / 503xx | Agent 执行失败 / 系统内部错误 / 依赖服务不可用 |
