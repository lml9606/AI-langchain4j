# 检索评测验收报告

## 结论

- 最终结果: FAIL
- 样本数: 25
- 通过: 12
- 通过率: 48.00%
- 平均覆盖率: 73.33%
- P95 耗时: 209ms
- 执行错误: 0

## 门禁

| 门禁 | 阈值 | 实际 | 结果 |
|---|---|---|---|
| 通过率 | 80.00% | 48.00% | FAIL |
| 平均覆盖率 | 70.00% | 73.33% | PASS |
| 执行错误 | 0 | 0 | PASS |

## 分类明细

| 分类 | 样本 | 通过 | 通过率 | 平均覆盖率 |
|---|---|---|---|---|
| java-basic | 6 | 3 | 50.00% | 77.78% |
| java-concurrent | 5 | 1 | 20.00% | 60.00% |
| java-collection | 1 | 1 | 100.00% | 100.00% |
| java-oop | 2 | 0 | 0.00% | 33.33% |
| java-exception | 1 | 0 | 0.00% | 66.67% |
| jvm | 3 | 2 | 66.67% | 66.67% |
| mysql | 2 | 2 | 100.00% | 100.00% |
| redis | 2 | 1 | 50.00% | 83.33% |
| spring | 2 | 2 | 100.00% | 100.00% |
| mq | 1 | 0 | 0.00% | 66.67% |

## 失败归因

| id | cause | reason | suggestion |
|---|---|---|---|
| concurrenthashmap-thread-safe | KEYWORD_TOO_STRICT | 只命中部分关键词 | 检查期望关键词是否过细，或答案被拆到多个块中 |
| string-builder-buffer | KEYWORD_TOO_STRICT | 只命中部分关键词 | 检查期望关键词是否过细，或答案被拆到多个块中 |
| hashcode-equals-contract | KEYWORD_TOO_STRICT | 只命中部分关键词 | 检查期望关键词是否过细，或答案被拆到多个块中 |
| abstract-interface | KEYWORD_TOO_STRICT | 只命中部分关键词 | 检查期望关键词是否过细，或答案被拆到多个块中 |
| overload-override | RETRIEVAL_MISS | 有召回结果，但期望关键词全部未命中 | 检查分块粒度、向量模型和 query 改写是否把问题带偏 |
| final-finally-finalize | KEYWORD_TOO_STRICT | 只命中部分关键词 | 检查期望关键词是否过细，或答案被拆到多个块中 |
| checked-unchecked-exception | KEYWORD_TOO_STRICT | 只命中部分关键词 | 检查期望关键词是否过细，或答案被拆到多个块中 |
| gc-reachability | RETRIEVAL_MISS | 有召回结果，但期望关键词全部未命中 | 检查分块粒度、向量模型和 query 改写是否把问题带偏 |
| thread-lifecycle | KEYWORD_TOO_STRICT | 只命中部分关键词 | 检查期望关键词是否过细，或答案被拆到多个块中 |
| synchronized-lock | KEYWORD_TOO_STRICT | 只命中部分关键词 | 检查期望关键词是否过细，或答案被拆到多个块中 |

## 建议

- 部分样本只命中部分关键词，检查关键词粒度或分块是否把答案拆散。
- 失败样本需要人工抽检，确认是题错、数据错还是检索错后再调整基线。
