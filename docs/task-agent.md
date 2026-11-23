# 通用任务 Agent（多步骤版）

在原有 RAG 知识问答之外，增加一条通用任务链路：**缺参追问 → 计划 → 调工具 → 观察结果 → 带证据回答**。
不绑定具体业务，通过注册工具即可扩展；当前内置知识检索工具，以及一对演示用的只读/写业务工具。

## 1. 能力边界

| 能力 | 实现 |
| --- | --- |
| 规划 | `TaskPlanNode` 先选固定 `TaskWorkflow`，再只在该工作流的工具契约内抽取实体、判断缺参并填参 |
| 澄清 | 缺参时不调用工具，直接生成追问并结束本轮 |
| 工具权限 | 三层校验：规划头节点整组预检 → 每个执行步骤前复核 → `ToolExecutor` 最终校验（角色/写开关/确认/Schema/超时/限流） |
| 写操作确认 | 规划阶段生成 `pendingAction` + 一次性 `confirmationId`，并写 `ai_tool_execution=PENDING`；用户确认后由独立执行图落库并流转台账 |
| 多步骤执行 | `ai_task_run` + `ai_task_step` 记录任务游标和每步结果；只读步骤先执行，写步骤批量确认后串行推进 |
| 步骤级幂等 | 写步骤使用 `runId:stepNo` 作为幂等键；重新确认不会重复执行已成功步骤 |
| 失败续跑 | 某步失败后后续步骤标记 `SKIPPED`，重试时恢复并从失败步骤继续；租约过期由恢复 worker 接管 |
| 工具扩展 | 实现 `AgentTool` 并注册为 Spring Bean；再声明一个 `TaskWorkflow` 固定该任务允许使用的工具集合与顺序 |
| 证据 | 工具返回结构化 `ToolEvidence`，响应透出 `citations` + `toolTrace` |
| 置信度 | 基于证据条数与工具成功/失败次数的确定性评分（可复现、不额外调 LLM） |
| 输出安全 | 复用 `OutputGuardService` 敏感词门禁 |

不做：SSE、MCP、长期记忆、多 Agent、任意 SQL。写工具由 `ToolDefinition.sideEffect` 标识，启用时仍需人工确认；全局只保留 `agent.tool.write-enabled` 应急开关。`DANGEROUS` 工具不能写入工作流，也不能通过 `ToolExecutor` 执行。

## 2. 链路

```text
START → entry → intent ──task──→ task_plan ──缺参──→ finalize → END
                                      └─→ task_execute（先跑只读步骤）
                                              ├──有未确认写步骤──→ task_confirm → finalize → END
                                              └──无写步骤────────→ task_answer → finalize → END

用户确认后独立执行图：
START → task_execute（approved=true，执行剩余写步骤）→ task_answer → finalize → END
intent ──knowledge_qa/code_generate──→ retrieval_probe ──证据不足──→ retrieval_full → generate → validate → finalize
```

任务链路为“规划 / 只读执行 / 批量确认 / 写步骤续跑 / 回答 / 收尾”，便于回放与排查。每一步都有独立的 `ai_task_step` 记录，不依赖图内存状态。

### 固定工具契约与权限

```text
用户请求
  → 模型从 TaskWorkflow 目录中选择一个任务类型（不接触工具名）
  → 头节点校验该工作流的完整工具集合：存在、启用、非 DANGEROUS、角色允许、写开关允许
  → 模型只按固定顺序为工具填参
  → 校验步骤数量、工具名、顺序完全一致；多一个/少一个/换一个/顺序错都拒绝
  → 每个执行步骤前再检查一次当前工具权限
  → ToolExecutor 最终校验 approved、幂等键、Schema、限流、超时
```

工具集合来自代码中的 `TaskWorkflow`，不使用固定 TopK 猜测，也不把工具白名单同时维护在配置文件和数据库。某个工具无权限时返回 `PERMISSION_REQUIRED` 语义，不会静默过滤后让模型从剩余工具中乱选。

例如“查询三张表”：拆成三个独立工具（各自权限、超时、限流、审计），业务上声明一个 `parallel=true` 的工作流阶段；阶段内部并行执行三个只读工具。写步骤再声明为后续阶段，统一生成一张批量确认卡片。

## 3. 内置工具

| 工具名 | 类型 | 参数 | 说明 |
| --- | --- | --- | --- |
| `search_knowledge` | 知识检索 | `query`, `topK` | 复用现有混合检索 |
| `query_demo_record` | 只读业务查询 | `status`, `limit` | 查询当前用户的演示记录 |
| `complete_demo_record` | 写业务操作 | `recordId` / `title` | 标记演示记录完成，必须人工确认 |

新增工具只需：

```java
@Component
public class MyTool implements AgentTool {
    public ToolDefinition definition() {
        return new ToolDefinition(
                "my_tool", "工具说明", schema, ToolSideEffect.READ_ONLY,
                3000, new ToolDefinition.ToolPolicy(true, 20, Set.of()));
    }
    public ToolResult execute(ToolContext ctx, JsonNode args) { ... }
}
```

注册后不需要再改配置文件或数据库：工具是否可执行、写风险、超时、限流和角色权限都由 `ToolDefinition` 声明。参数契约只来自 `inputSchema`，不另外维护 `requiredArgs`；`required`、类型、`enum`、数值边界、`additionalProperties=false`、`anyOf/oneOf/allOf` 都由执行器统一校验。

然后声明任务契约：

```java
@Bean
TaskWorkflow myWorkflow() {
    return new TaskWorkflow() {
        public String id() { return "my_task"; }
        public String description() { return "完成某类业务任务"; }
        public List<Stage> stages() {
            return List.of(new Stage("main", "主阶段", List.of("my_tool")));
        }
    };
}
```

## 4. 本地验证

注册并登录后，发送需要调用已注册工具的业务请求即可触发任务链路；缺少必填参数时应返回 `clarify=true` 和追问文案。响应字段包括 `clarify`、`confidence`、`confidenceReason`、`citations`、`toolTrace`。

## 5. 写操作确认链路

```text
规划阶段：LLM 生成固定工作流计划 → 执行只读步骤（可并行）
          → 遇到写步骤停下 → TaskConfirmNode 生成一张批量确认卡片
          → Redis 保存短期授权；ai_task_run → WAITING_CONFIRM
          → ai_tool_execution 为每个写步骤写 PENDING（runId:stepNo）

确认阶段：POST /api/agent/confirm（conversationId + confirmationId + approved）
          → 校验会话归属 → 严格会话锁内只读一次当前动作
          → 任务存在时以 ai_task_run 为事实源，approved=true 进入确认图
          → TaskRunExecutor 获取执行租约，恢复 SKIPPED 步骤，从当前游标继续；每步续租
          → ToolExecutor 二次校验注册表 + approved + JSON Schema 参数
          → 写步骤按 runId:stepNo 幂等；失败即停，后续步骤 SKIPPED
          → TaskAnswerNode 基于工具结果回答并保存 assistant 消息
          → 任务 SUCCEEDED 删除会话 key；FAILED 保留 key 供重试
          → 用户取消：ai_task_run → CANCELLED，未完成步骤同步取消
```

关键约束：Redis 保存待确认计划与短期授权，`ai_task_run` / `ai_task_step` 保存执行事实，`ai_tool_execution` 保存写操作台账；真正防止重复落库的是写步骤的 `runId:stepNo` 幂等键。

## 6. 管理接口

均要求 ADMIN 角色：

```text
GET  /api/tools                      查看已注册工具定义
GET  /api/tools/workflows             查看固定任务工作流及其工具集合
POST /api/tools/{name}/invoke        手工调用工具（仍走只读/参数/超时/限流/审计）
```

写工具手工调用默认返回 `CONFIRMATION_REQUIRED`，不能绕过确认链路。

## 7. 配置

```properties
agent.tool.write-enabled=false
agent.tool.confirmation.ttl-seconds=300
agent.tool.timeout-ms=3000
agent.tool.max-calls-per-minute=20
agent.tool.max-steps-per-task=8
agent.task.max-writes-per-task=5
agent.task.lease-seconds=120
agent.task.parallel-reads=true
agent.task.recovery.enabled=true
agent.task.recovery.interval-ms=30000
agent.task.recovery.batch-size=10
```

工具自身策略以 `ToolDefinition` 为唯一事实源，任务工具集合以 `TaskWorkflow` 为唯一事实源；配置文件不再维护工具白名单、写工具名单或工具级超时。全局写开关用于应急停机；工具限流使用 Redis 固定窗口，多实例共享计数。

## 8. 代码结构

任务 Agent 的代码按职责收拢，避免散落在通用节点和正式业务接口中：

```text
src/main/java/com/example/langchain4j
├── graph/task/nodes/        任务规划、确认、执行、回答四个图节点
├── graph/qa/nodes/          知识问答主图节点（入口、意图、检索、生成、校验等）
├── graph/common/nodes/      主图与任务确认图共用的收尾节点
├── task/                    任务租约过期恢复 worker
├── tool/
│   ├── api/                 工具契约：AgentTool、ToolDefinition、ToolContext、ToolResult 等
│   ├── runtime/             注册表、执行器、确认令牌（权限/超时/限流/审计）
│   ├── workflow/            固定任务契约：TaskWorkflow、TaskWorkflowRegistry
│   └── provider/            内置工具实现：知识检索、演示读/写工具
└── controller/ToolController 工具管理接口（ADMIN）

src/test/java/com/example/langchain4j
├── graph/task/nodes/        任务节点单测
├── graph/qa/nodes/          知识问答节点单测
├── tool/runtime/            执行器、限流与确认令牌测试
└── tool/provider/           内置工具单测
```

新增任务节点放在 `graph/task/nodes`；新增知识问答节点放在 `graph/qa/nodes`；新增业务工具实现放在 `tool/provider`；新增任务契约放在 `tool/workflow`。
