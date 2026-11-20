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
`confirm` 的 `approved=true` 表示执行、`false` 表示取消；响应 `data.business.success/errorCode/errorMessage` 表示业务动作结果。

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
