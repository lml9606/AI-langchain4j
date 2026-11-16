# 通用任务 Agent（最小可用版）

在原有 RAG 知识问答之外，增加一条通用任务链路：**缺参追问 → 计划 → 调工具 → 观察结果 → 带证据回答**。
不绑定具体业务，通过注册工具即可扩展；当前内置知识检索、只读 SQL、HTTP 查询、取消订单四类工具。

## 1. 能力边界

| 能力 | 实现 |
| --- | --- |
| 规划 | `TaskPlanNode` 一次 LLM 调用输出 JSON 计划（实体 + 缺失槽位 + 工具步骤） |
| 澄清 | 缺参时不调用工具，直接生成追问并结束本轮 |
| 工具权限 | `ToolExecutor` 统一校验：只读开关、写工具白名单、用户确认、必填参数、未知工具、超时、单用户每分钟限流 |
| 写操作确认 | 规划阶段只生成 `pendingAction` + 一次性 `confirmationId`；用户确认后由独立执行图落库 |
| 工具扩展 | 实现 `AgentTool` 并注册为 Spring Bean，启动期自动进入 `ToolRegistry` |
| 证据 | 工具返回结构化 `ToolEvidence`，响应透出 `citations` + `toolTrace` |
| 置信度 | 基于证据条数与工具成功/失败次数的确定性评分（可复现、不额外调 LLM） |
| 输出安全 | 复用 `OutputGuardService` 敏感词门禁 |
| 评测 | 现有评测表新增任务场景、工具选择准确率、参数匹配率、任务成功率 |

不做：SSE、MCP、长期记忆、多 Agent、任意 SQL。写工具当前只开放 `cancel_order`，且必须人工确认。

## 2. 链路

```text
START → entry → intent ──task──→ task_plan ──缺参──→ finalize → END
                                      ├──只读计划──→ task_execute → task_answer → finalize → END
                                      └──含写工具──→ task_confirm → finalize → END
                                                          │
                                                          └─ 返回 pendingAction + confirmationId

用户确认后独立执行图：
START → task_execute（approved=true）→ task_answer → finalize → END
intent ──knowledge_qa──→ decompose → retrieval ⇄ hop_check → generate → validate → finalize
```

任务链路为线性四段（规划 / 确认或执行 / 回答 / 收尾），便于回放与评测；`recursionLimit` 已包含任务 4 节点余量。

## 3. 内置工具

| 工具名 | 类型 | 参数 | 说明 |
| --- | --- | --- | --- |
| `search_knowledge` | 知识检索 | `query`, `topK` | 复用现有混合检索 |
| `query_business_data` | 只读 SQL | `queryName`(`order_status`/`refund_record`), `orderId` | 白名单命名查询，强制 `owner_user_id` 隔离 |
| `query_order_api` | HTTP GET | `orderId` | 域名白名单防 SSRF，本地指向 `/mock/orders/{orderId}` |
| `cancel_order` | 写 SQL（需确认） | `orderId`, `reason` | 仅取消本人 PAID 订单；幂等键防重复落库 |

新增工具只需：

```java
@Component
public class MyTool implements AgentTool {
    public ToolDefinition definition() { ... }
    public ToolResult execute(ToolContext ctx, JsonNode args) { ... }
}
```

## 4. 演示步骤

1. 用 `local` profile 启动（默认端口 8082，HTTP 工具已指向本机 mock 接口）。
2. 注册并登录用户名 `demo`（演示订单/退款数据归属 `demo`）。
3. 发送任务请求：
   - `帮我查订单 A1001 的状态`
   - `订单 A1002 退款为什么失败`
   - `帮我查一下订单状态`（应触发缺参追问）
4. 响应新增字段：`clarify`、`confidence`、`confidenceReason`、`citations`、`toolTrace`。
5. 写操作演示：
   - 发送 `取消订单 A1001`；
   - 响应 `pendingAction` 包含 `confirmationId`，此时订单状态不变；
   - 调用 `POST /api/agent/confirm`，body 为
     `{"confirmationId":"cfm_xxx","approved":true}`；
   - 再次确认同一 ID 返回 404（令牌一次性消费）；
   - `approved=false` 只记录取消消息，不修改订单。

## 5. 写操作确认链路

```text
规划阶段：LLM 生成计划 → 检测到 WRITE 工具 → 不执行
          → TaskConfirmNode 生成 Redis 令牌（TTL 5 分钟）
          → 响应 pendingAction，保存 assistant 确认话术

确认阶段：POST /api/agent/confirm
          → Redis getAndDelete（一次性）
          → 校验 userId / conversationId / 过期时间
          → 会话锁内执行已确认计划（approved=true + confirmationId）
          → ToolExecutor 二次校验白名单 + approved
          → 写工具按 owner_user_id 隔离、状态机更新、幂等键防重
          → TaskAnswerNode 基于工具结果回答并保存 assistant 消息
```

关键约束：确认令牌只证明“用户同意了这个计划”，真正防止重复落库的是写工具里的幂等键；
执行超时或失败不会自动重试写工具，用户需要重新发起规划。

## 6. 管理接口

均要求 ADMIN 角色：

```text
GET  /api/tools                      查看已注册工具定义
POST /api/tools/{name}/invoke        手工调用工具（仍走只读/参数/超时/限流/审计）
```

写工具手工调用默认返回 `CONFIRMATION_REQUIRED`，不能绕过确认链路。

## 7. 任务评测

用例新增字段（`POST /api/eval/cases`）：

```json
{
  "scenario": "task",
  "question": "查一下订单 A1001 的状态",
  "expectedTools": ["query_business_data"],
  "expectedArgs": {
    "query_business_data": {"queryName": "order_status", "orderId": "A1001"}
  },
  "expectClarify": false
}
```

`POST /api/eval/run` 对任务用例只跑规划节点，计算：

- `avgToolSelection`：期望工具被计划覆盖的比例；
- `avgArgsScore`：期望参数键值在计划步骤中的匹配比例；
- `taskSuccessRate`：工具、参数、追问行为全部符合预期。

不执行真实工具，评测不依赖业务数据，适合 CI 回归。

## 8. 配置

```properties
agent.tool.read-only=true
agent.tool.allowed-write-tools=cancel_order
agent.tool.confirmation.ttl-seconds=300
agent.tool.timeout-ms=3000
agent.tool.max-calls-per-minute=20
agent.tool.max-steps-per-task=3
agent.tool.http.base-url=http://localhost:8082
agent.tool.http.allowed-hosts=localhost,127.0.0.1
```

生产部署需替换 mock 地址并收紧域名白名单；工具限流已使用 Redis 固定窗口，多实例共享计数。

本地演示 profile 将 `agent.tool.read-only` 设为 `false`，仅允许白名单内的 `cancel_order`；
生产环境保持只读或严格限制白名单。

## 9. 代码结构

任务 Agent 的代码按职责收拢，避免散落在通用节点和正式业务接口中：

```text
src/main/java/com/example/langchain4j
├── graph/task/nodes/        任务规划、确认、执行、回答四个图节点
├── graph/qa/nodes/          知识问答主图节点（入口、意图、检索、生成、校验等）
├── graph/common/nodes/      主图与任务确认图共用的收尾节点
├── graph/resume/            简历优化子图（含 resume_task 触发节点）
├── tool/
│   ├── api/                 工具契约：AgentTool、ToolDefinition、ToolContext、ToolResult 等
│   ├── runtime/             注册表、执行器、确认令牌（权限/超时/限流/审计）
│   └── provider/            内置工具实现：知识检索、只读 SQL、HTTP 查询、取消订单
├── mock/                    本地演示订单接口（非正式业务接口）
└── controller/ToolController 工具管理接口（ADMIN）

src/test/java/com/example/langchain4j
├── graph/task/nodes/        任务节点单测
├── graph/qa/nodes/          知识问答节点单测
├── tool/runtime/            执行器、限流与确认令牌测试
└── tool/provider/           内置工具单测
```

新增任务节点放在 `graph/task/nodes`；新增知识问答节点放在 `graph/qa/nodes`；简历相关节点和工具放在 `graph/resume`；新增工具实现放在 `tool/provider`；演示用外部接口放在 `mock`，不要混入正式 `controller` 包。
