# 检索评测验收报告

## 结论

- 最终结果: FAIL
- 样本数: 10
- 通过: 0
- 通过率: 0.00%
- 平均覆盖率: 0.00%
- P95 耗时: 486ms
- 执行错误: 0

## 门禁

| 门禁 | 阈值 | 实际 | 结果 |
|---|---|---|---|
| 通过率 | 80.00% | 0.00% | FAIL |
| 平均覆盖率 | 70.00% | 0.00% | FAIL |
| 执行错误 | 0 | 0 | PASS |

## 分类明细

| 分类 | 样本 | 通过 | 通过率 | 平均覆盖率 |
|---|---|---|---|---|
| java-basic | 6 | 0 | 0.00% | 0.00% |
| java-concurrent | 1 | 0 | 0.00% | 0.00% |
| java-collection | 1 | 0 | 0.00% | 0.00% |
| java-oop | 2 | 0 | 0.00% | 0.00% |

## 失败归因

| id | cause | reason | suggestion |
|---|---|---|---|
| hashmap-structure | DATA_MISSING | 检索返回为空 | 确认该分类的文档已导入，且文档未过期/未被删除 |
| concurrenthashmap-thread-safe | DATA_MISSING | 检索返回为空 | 确认该分类的文档已导入，且文档未过期/未被删除 |
| arraylist-linkedlist | DATA_MISSING | 检索返回为空 | 确认该分类的文档已导入，且文档未过期/未被删除 |
| string-builder-buffer | DATA_MISSING | 检索返回为空 | 确认该分类的文档已导入，且文档未过期/未被删除 |
| equals-reference | DATA_MISSING | 检索返回为空 | 确认该分类的文档已导入，且文档未过期/未被删除 |
| hashcode-equals-contract | DATA_MISSING | 检索返回为空 | 确认该分类的文档已导入，且文档未过期/未被删除 |
| abstract-interface | DATA_MISSING | 检索返回为空 | 确认该分类的文档已导入，且文档未过期/未被删除 |
| overload-override | DATA_MISSING | 检索返回为空 | 确认该分类的文档已导入，且文档未过期/未被删除 |
| static-instance | DATA_MISSING | 检索返回为空 | 确认该分类的文档已导入，且文档未过期/未被删除 |
| final-finally-finalize | DATA_MISSING | 检索返回为空 | 确认该分类的文档已导入，且文档未过期/未被删除 |

## 建议

- 有样本检索为空，优先确认知识库导入和文档版本。
- 失败样本需要人工抽检，确认是题错、数据错还是检索错后再调整基线。
