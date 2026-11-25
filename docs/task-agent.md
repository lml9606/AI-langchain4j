# 固定读写 + 受控 ReAct Agent

任务链路以固定工作流为主：模型只判断“查询、修改还是多步诊断”并抽取参数，
工具名、权限、确认和幂等由代码控制。固定查询与写操作保持确定性；
只有明确需要多步只读诊断时才进入白名单 ReAct 循环。

## 1. 能力边界

| 能力 | 实现 |
| --- | --- |
| 任务解析 | `TaskResolveNode` 一次输出 `READ`/`WRITE`、参数和缺失项，不接触工具名或执行计划 |
| 参数抽取 | `TaskResolveNode` 抽取最小参数；缺参转追问 |
| 只读查询 | `TaskReadNode` 按目标表直接调用 `EnrollmentService` / `QuotaService` / `QuotaTransactionService`，不经过 AgentTool；完整业务行写入 `taskData` 并透出为 `businessData` |
| 报名关联额度 | 报名先按报名单号命中，再由 `TaskQuotaReadNode` 用 `quota.enrollment_no` 查询关联额度 |
| 关联交易读取 | 报名或额度先读命中的主键，再由 `TaskTransactionReadNode` 读取 `quota_transaction`；两段业务行追加到同一个 `businessData` |
| 写操作确认 | `TaskConfirmNode` 生成一次性 `confirmationId`，并写 `ai_tool_execution=PENDING` |
| 写操作执行 | `TaskWriteNode` 固定调用 `complete_demo_record`，必须 `approved=true` 且携带幂等键 |
| 幂等 | 单写操作的 `confirmationId` 同时作为台账主键和幂等键 |
| 证据回答 | 工具 `ToolEvidence` 透出 `citations`，回答节点生成 `toolTrace` 与置信度；完整查询明细由 `businessData` 透出 |
| 输出安全 | 复用 `OutputGuardService` 敏感词门禁 |
| 多步只读诊断 | `REACT` 进入受控 `react_plan → react_tool` 循环，只允许白名单只读工具，最多 5 步 |

不做：SSE、任意 SQL、写工具进入 ReAct、批量确认、任务租约恢复。

## 2. 链路

```text
START → entry → intent_plan ──task──→ task_resolve ──失败──→ fallback
                                           │
                                           ├─READ──→ task_read ──需要关联额度──→ task_read_quota
                                           │             ├──需要关联交易──→ task_read_transaction
                                           │             └──────────────→ task_answer → finalize → END
                                           ├─WRITE─→ task_confirm → finalize → END
                                           └─REACT─→ react_plan ⇄ react_tool
                                                         └─final/停止──→ task_answer → finalize → END

用户确认后直接执行（不重新分类、不重新抽参）：
task_write(approved=true, idempotencyKey=confirmationId) → task_answer → finalize
```

缺参时 `task_resolve` 返回 `clarify=true` 和追问文案，直接收尾，不调用工具。

## 3. 固定读取与写工具

| 操作 | 实现 | 参数 | 说明 |
| --- | --- | --- | --- |
| READ 报名 | `EnrollmentService` | `enrollmentNo`, `limit` | 查询当前用户的报名记录 |
| READ 额度 | `QuotaService` | `enrollmentNo`, `activityNo`, `month`, `limit` | 查询当前用户的额度记录；`enrollment_no` 为可空关联报名单号 |
| READ 交易 | `QuotaTransactionService` | `quotaId`, `enrollmentId`, `limit` | 查询当前用户的交易流水 |
| WRITE | `complete_demo_record` | `recordId` / `title` | 标记演示记录完成，必须人工确认 |
| KNOWLEDGE | `search_knowledge` | `query`, `topK` | 知识问答工具，不属于固定读写任务 |

### ReAct 只读诊断

`REACT` 只用于固定节点无法覆盖的多步只读诊断，例如“先查报名，再查关联额度，再核对交易，
解释额度为什么不对”。它不是通用自由工具循环，约束如下：

| 约束 | 实现 |
| --- | --- |
| 工具白名单 | `ReactToolCatalog` 只暴露 `query_enrollment`、`query_quota`、`query_transaction`、`search_knowledge` |
| 只读边界 | 写工具不进入目录；`ReactPlanNode` 和执行节点各校验一次副作用 |
| 执行门禁 | `ReactToolNode` 必须经过 `ToolExecutor`，复用权限、Schema、限流和超时 |
| 用户隔离 | `ToolContext.userId` 来自会话；领域 Service 的 SQL 仍强制带 `user_id` |
| 步数上限 | `agent.react.max-steps`，默认 5；达到上限停止并回答 |
| 重复调用 | 同工具同参数（对象字段排序后）再次出现时立即停止 |
| 停止条件 | `final`、无数据、致命工具错误、重复调用、步数耗尽 |
| 可观测 | `taskTrace`/`taskEvidence`/`businessData` 与普通任务共用；`REACT_HISTORY` 记录每步观察 |

规划输出只保存一句 `reason`，不保存完整思维链。模型输出非法 JSON、越权工具或非对象参数时
按停止处理，由 `task_answer` 基于已有证据回答，避免格式错误升级为整轮失败。

权限校验顺序：

```text
TaskResolveNode → 只做读写判断与参数抽取
只读查询       → 节点直接调用领域 Service，由 SQL 的 user_id 条件做数据隔离
ReAct 只读工具 → ReactToolCatalog 白名单 + ToolExecutor：注册表 + 角色 + JSON Schema + 限流 + 超时
写工具         → ToolExecutor：注册表 + 角色 + 写开关 + 确认 + 幂等键 + JSON Schema + 限流 + 超时
```

模型输出只能作为参数候选；即使 prompt injection 让模型输出其他工具名，固定节点也不会使用。

“先查报名再查额度”要求 `readTarget=ENROLLMENT`、`withQuotas=true` 且提供 `enrollmentNo`；
第一段报名未命中时不会进入额度节点。额度表通过 `enrollment_no` 关联报名单号，查询仍带
`user_id` 条件，不能跨用户读取。

额度创建或更新时，`QuotaRequest.enrollmentNo` 可选；非空时服务层会校验该报名单号存在且
属于当前用户，避免写入跨用户的孤儿关联。

`businessData` 与 `citations` 的边界：前者是查询得到的业务明细行（敏感字段已脱敏），用于前端展示或后续业务处理；后者是截断后的可引用证据，用于回答溯源。没有数据时两者都为空，节点摘要会明确返回“没有查询到符合条件的记录”。

## 4. 新增固定任务

新增业务任务时按节点扩展，而不是给模型一份工具目录：

1. 只读任务：在领域 Service 增加查询方法，扩展 `TaskReadTarget` 和 `TaskReadNode`；
2. 只读任务：在 `AgentGraphBuilder` 增加目标路由或执行节点；
3. 写任务：实现 `AgentTool` 并注册为 Spring Bean；
4. 写任务：在 `ToolNames` 增加固定写工具名；
5. 写操作必须经过确认节点，并在执行节点校验 `approved` 与幂等键。

如果任务需要“先查询拿 ID，再修改”，在图上拆成只读节点 → 写节点，不把两个动作塞进一个模型计划。

## 5. 写操作确认链路

```text
生成卡片：task_confirm
          → 只生成 confirmationId 和待确认卡片
          → Redis 保存短期令牌（一个会话最多一个）
          → ai_tool_execution 写 PENDING

确认执行：POST /api/agent/confirm（conversationId + confirmationId + approved）
          → 会话归属校验 → 严格会话锁内读取令牌
          → approved=false：PENDING/FAILED → CANCELLED，删除令牌
          → approved=true：task_write 直接执行，不重新分类或抽参
          → ToolExecutor 校验工具、角色、写开关、approved、幂等键和 Schema
          → 台账成功删除令牌；失败保留令牌，可用同一 confirmationId 重试
```

`confirmationId` 既是 Redis 令牌 ID，也是 `ai_tool_execution.execution_id`；重复确认命中
`SUCCEEDED` 时直接返回上次结果，命中 `RUNNING` 返回执行中，命中 `CANCELLED` 拒绝执行。

新 `chat` 请求代表放弃旧确认：先在数据库把 PENDING 台账取消，再删除 Redis 令牌；
数据库取消失败时保留令牌，避免出现“令牌已删、台账仍 PENDING”的孤儿状态。

## 6. 管理接口

均要求 ADMIN 角色：

```text
GET  /api/tools                 查看已注册工具定义
POST /api/tools/{name}/invoke   手工调用工具（仍走权限/参数/超时/限流）
```

写工具手工调用默认返回 `CONFIRMATION_REQUIRED`，不能绕过确认链路。

## 7. 配置

```properties
agent.tool.write-enabled=false
agent.tool.confirmation.ttl-seconds=300
agent.tool.timeout-ms=3000
agent.tool.max-calls-per-minute=20
agent.react.enabled=true
agent.react.max-steps=5
agent.react.reason-max-length=160
agent.react.plan-max-output-tokens=512
```

工具策略以 `ToolDefinition` 为唯一事实源；配置文件只保留全局应急开关和默认预算。
工具限流使用 Redis 固定窗口，多实例共享计数。

## 8. 代码结构

```text
src/main/java/com/example/langchain4j
├── agent/                              聊天接入、Agent 编排服务与聊天 DTO
├── auth/                               认证、用户、JWT、角色与安全过滤器
├── conversation/                       会话、消息、摘要、话题段与查询改写
├── prompt/                             Prompt 管理接口、配置与默认模板
├── sensitive/                          敏感词管理
├── business/enrollment/                报名域：Controller、Service、Mapper、Entity、Request
├── business/quota/                     额度域：Controller、Service、Mapper、Entity、Request
├── business/transaction/               交易域：Controller、Service、Mapper、Entity、Request
├── business/ticket/                    工单域：Controller、Service、Mapper、Entity、Request
├── graph/builder/AgentGraphBuilder.java 主图构建入口
├── graph/constant/GraphNodes.java      主图节点 ID 的唯一来源
├── graph/task/model/TaskOperation.java READ/WRITE/REACT 操作类型
├── graph/task/nodes/                   路由、抽参、读、确认、写、回答节点
├── graph/task/react/                   ReAct 协议、动作解析与只读工具白名单
├── graph/task/support/                 只读结果、轨迹和证据转换
├── graph/qa/nodes/                     知识问答主图节点
├── graph/common/nodes/                 收尾节点
├── knowledge/                          知识库模块
│   ├── controller/ service/ entity/ mapper/ typehandler/
│   └── dto/ embedding/ model/ parser/ retrieval/
├── tool/api/                           工具对外契约
│   ├── contract/                       AgentTool 接口
│   ├── model/                          ToolContext / ToolDefinition / ToolResult / ToolEvidence / ToolSideEffect
│   └── constant/                       ToolNames / ToolErrorCode
├── tool/controller/                    工具管理接口
├── tool/demo/                          工具演示域：Controller、Service、Entity、Mapper、演示工具
├── tool/runtime/                       注册表、执行器、权限、写执行模板
├── tool/execution/                     工具执行台账、Mapper、Entity 与指标记录
├── tool/confirmation/                  确认令牌、取消和确认后执行编排
└── tool/provider/                      内置工具实现
```
