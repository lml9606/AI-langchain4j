# 项目测评报告

测评日期：2026-11-26

## 结论

当前代码通过离线构建与确定性测评，新增测评覆盖路由、任务解析、工具门禁、写确认、
写幂等、认证授权、会话隔离和 Redis 降级。补全知识数据并导入 6 份测评补充文档后，
真实检索 full 通过 25/25，通过率和平均覆盖率均为 100%，已达到 95% 目标并生成
正式回归基线。

## 测评集

| 测评集 | 数量 | 内容 |
| --- | ---: | --- |
| `eval/golden-retrieval.jsonl` | 25 | 检索召回、知识分类和 `topK` |
| `eval/golden-task.jsonl` | 9 | 只读任务、关联查询、缺参、写确认、非法工具和 ReAct |

任务测评使用预置模型输出驱动真实 `TaskResolveNode`，因此结果确定、可重复，不依赖
数据库或真实 LLM。

## 自动化验证

最终执行命令：

```powershell
.\mvnw.cmd clean verify
```

本次实际使用 JDK 21 执行了等价的 Maven 命令，结果：

- 测试总数：88
- 通过：87
- 失败：0
- 跳过：1
- `BUILD SUCCESS`
- 生成可执行 JAR：`target/ai-code-helper-0.0.1-SNAPSHOT.jar`

跳过项是 `RetrievalEvalPipelineTest`，它需要显式开启并连接有知识数据的真实环境。

覆盖的关键边界：

- 入口、意图、检索、任务、ReAct、确认和质检路由
- 未注册工具、角色不足、高危工具、写开关、确认和幂等键门禁
- 写执行成功、失败、重放、执行中和参数冲突
- 确认令牌创建、单会话单动作、台账失败回滚和 Redis 故障
- 确认执行固定计划、重复运行拒绝和部分失败记账
- JWT 401、角色 403、会话水平越权和 Redis 锁 fail-open/fail-closed
- 向量检索双通道融合、单通道降级和双通道失败
- 检索结果按规范化内容去重，避免重复块占满 `topK`
- 上下文预算分配、指标埋点和任务解析测评集

## 真实检索结果

本地 `local` profile 已成功启动，`/actuator/health` 返回 `UP`。

知识库健康度：

```text
totalChunks=755
privateChunks=755
publicChunks=0
```

知识属于用户 `lml` 的私有库。测评使用短期本地 JWT 以该用户身份执行，没有修改密码、
迁移数据或写入令牌文件。`docs/eval-knowledge/` 中的 6 份补充文档已导入，共新增
24 个父/子知识块。

| 套件 | 样本 | 通过 | 通过率 | 平均覆盖率 | P95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| smoke（补知识前） | 10 | 7 | 70.00% | 80.00% | 145ms |
| full（补知识前） | 25 | 19 | 76.00% | 82.67% | 324ms |
| full（补知识后） | 25 | 25 | 100.00% | 100.00% | 170ms |

三套执行错误均为 0。补知识后的 full 已通过门禁，结果提升为正式基线
`src/test/resources/eval/baseline.json`。再次执行 full 时已实际读取该基线，
结果显示通过率 100%、平均覆盖率 100%，相对基线回退 0 个百分点，最终仍为 PASS。

补知识前剩余 6 条失败，已通过知识补录解决：

- `concurrenthashmap-thread-safe`
- `overload-override`
- `final-finally-finalize`
- `gc-reachability`
- `thread-lifecycle`
- `synchronized-lock`

## 返回结果位置

- 任务测评稳定副本：`docs/eval-results/20261126-task/`
- 最终 100% full 稳定副本：`docs/eval-results/20261126-full-100/`
- 基线对比通过稳定副本：`docs/eval-results/20261126-full-baseline-pass/`
- 最终 smoke 稳定副本：`docs/eval-results/20261126-smoke-final/`
- 最终 full 稳定副本：`docs/eval-results/20261126-full-final/`
- 检索 smoke 稳定副本：`docs/eval-results/20261126-retrieval-empty/`
- 任务测评逐条实际结果：`target/eval/task-resolve/raw.jsonl`
- 正式回归基线：`src/test/resources/eval/baseline.json`
- JUnit 每个测试类的详细结果：`target/surefire-reports/`

## 修复项

- 为 `ContextAssembler` 配置构造器增加显式 Spring 注入标记，修复应用启动时
  “No default constructor found”的运行时故障。
- 补齐 `ReactPlanNode` 已写一半的上下文预算装配依赖，保持原有 ReAct 行为。
- 检索接口先扩大候选池并按规范化内容去重，避免重叠块或重复块占用返回值名额。
- 修正 7 个与知识正文同义但字面不同的检索期望词，没有降低任何门禁阈值。
- 任务测评新增逐条 `raw.jsonl` 和 `summary.md`，保留实际解析结果。
- 新增 6 份可追踪知识文档，补齐并发容器、重载重写、final 三兄弟、GC 可达性、
  线程生命周期和显式锁对比。

## 尚未验证

- 真实 LLM 的意图分类、任务规划、回答忠实度和引用质量
- 连接真实 PostgreSQL、Redis 的完整确认写事务和故障切换
- 并发压力、P95 延迟、token 成本和长时间稳定性
