# 通用任务 Agent（最小可用版）

在原有 RAG 知识问答之外，增加一条通用任务链路：**缺参追问 → 计划 → 调工具 → 观察结果 → 带证据回答**。
不绑定具体业务，通过注册工具即可扩展；当前内置知识检索工具，业务查询与写操作工具由接入方按需注册。

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

不做：SSE、MCP、长期记忆、多 Agent、任意 SQL。写工具默认关闭；启用时必须在 `agent.tool.allowed-write-tools` 中显式登记并人工确认。

## 2. 链路

```text
START → entry → intent ──task──→ task_plan ──缺参──→ finalize → END
                                      ├──只读计划──→ task_execute → task_answer → finalize → END
                                      └──含写工具──→ task_confirm → finalize → END
                                                          │
                                                          └─ 返回 pendingAction + confirmationId

用户确认后独立执行图：
START → task_execute（approved=true）→ task_answer → finalize → END
intent ──knowledge_qa/code_generate──→ retrieval_probe ──证据不足──→ retrieval_full → generate → validate → finalize
```

任务链路为线性四段（规划 / 确认或执行 / 回答 / 收尾），便于回放与排查；`recursionLimit` 已包含任务 4 节点余量。

## 3. 内置工具

| 工具名 | 类型 | 参数 | 说明 |
| --- | --- | --- | --- |
| `search_knowledge` | 知识检索 | `query`, `topK` | 复用现有混合检索 |

新增工具只需：

```java
@Component
public class MyTool implements AgentTool {
    public ToolDefinition definition() { ... }
    public ToolResult execute(ToolContext ctx, JsonNode args) { ... }
}
```

## 4. 本地验证

注册并登录后，发送需要调用已注册工具的业务请求即可触发任务链路；缺少必填参数时应返回 `clarify=true` 和追问文案。响应字段包括 `clarify`、`confidence`、`confidenceReason`、`citations`、`toolTrace`。

## 5. 写操作确认链路

```text
规划阶段：LLM 生成计划 → 检测到 WRITE 工具 → 不执行
          → TaskConfirmNode 写入会话当前待确认动作（Redis 单 key，TTL 5 分钟）
          → 响应 pendingAction，保存 assistant 确认话术

确认阶段：POST /api/agent/confirm（conversationId + confirmationId + approved）
          → 校验会话归属
          → 严格会话锁内只读一次当前动作
          → 校验 confirmationId / userId；key 由 Redis TTL 判定过期
          → 执行已确认计划（approved=true + confirmationId，同步在锁内完成）
          → ToolExecutor 二次校验白名单 + approved
          → 业务数据隔离、状态更新与幂等防重由具体写工具实现
          → TaskAnswerNode 基于工具结果回答并保存 assistant 消息
          → 台账 SUCCEEDED 删除会话 key；失败保留 key 供同 ID 重试
```

关键约束：Redis 待确认动作只证明“用户同意了这个计划”，真正防止重复落库的是写工具里的幂等键；
一个会话同一时刻只允许一个待确认动作；执行失败不会自动重试写工具，用户可用同一个 `confirmationId` 重试。

## 6. 管理接口

均要求 ADMIN 角色：

```text
GET  /api/tools                      查看已注册工具定义
POST /api/tools/{name}/invoke        手工调用工具（仍走只读/参数/超时/限流/审计）
```

写工具手工调用默认返回 `CONFIRMATION_REQUIRED`，不能绕过确认链路。

## 7. 配置

```properties
agent.tool.read-only=true
agent.tool.confirmation.ttl-seconds=300
agent.tool.timeout-ms=3000
agent.tool.max-calls-per-minute=20
agent.tool.max-steps-per-task=3
```

写工具白名单、超时与限流均应按业务风险收紧；工具限流使用 Redis 固定窗口，多实例共享计数。

## 8. 代码结构

任务 Agent 的代码按职责收拢，避免散落在通用节点和正式业务接口中：

```text
src/main/java/com/example/langchain4j
├── graph/task/nodes/        任务规划、确认、执行、回答四个图节点
├── graph/qa/nodes/          知识问答主图节点（入口、意图、检索、生成、校验等）
├── graph/common/nodes/      主图与任务确认图共用的收尾节点
├── tool/
│   ├── api/                 工具契约：AgentTool、ToolDefinition、ToolContext、ToolResult 等
│   ├── runtime/             注册表、执行器、确认令牌（权限/超时/限流/审计）
│   └── provider/            内置工具实现：知识检索
└── controller/ToolController 工具管理接口（ADMIN）

src/test/java/com/example/langchain4j
├── graph/task/nodes/        任务节点单测
├── graph/qa/nodes/          知识问答节点单测
├── tool/runtime/            执行器、限流与确认令牌测试
└── tool/provider/           内置工具单测
```

新增任务节点放在 `graph/task/nodes`；新增知识问答节点放在 `graph/qa/nodes`；新增业务工具实现放在 `tool/provider`。
