# 测评知识文档核验记录

核验日期：2026-11-26

## 结论

原始章节知识文档中相关章节的核心技术结论与 Java 21 官方规范、API 文档或
OpenJDK 21 源码一致。临时补充文档已并入原始章节并退出知识库，避免同一知识出现多个来源。

## 核验依据

| 原始文档/章节 | 核验依据 | 结论 |
| --- | --- | --- |
| `01-Java基础.md` 的 GC 章节 | [HotSpot GC 指南](https://docs.oracle.com/en/java/javase/21/gctuning/other-considerations.html)、[Java 21 java.lang.ref API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ref/package-summary.html) | GC Root 可达性、引用链和引用强度层级结论正确；根集合是 JVM 实现相关 |
| `01-Java基础.md` 的 16.1 节 | [Java 21 语言规范第 8 章](https://docs.oracle.com/javase/specs/jls/se21/html/jls-8.html) | 重载、重写、编译时绑定、运行时分派、协变返回和访问权限结论正确 |
| `01-Java基础.md` 的 16.2 节 | [Java 21 Object API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Object.html)、[JLS 14](https://docs.oracle.com/javase/specs/jls/se21/html/jls-14.html) | `finally` 正常语义及 `finalize` 已弃用、执行不确定的结论正确 |
| `02-并发.md` 的 17 节 | [Java 21 ReentrantLock API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html)、[Java 21 Object API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Object.html) | 监视器锁、可重入、可中断、超时、公平锁、Condition 和 finally 解锁建议均正确 |
| `02-并发.md` 的 23 节 | [Java 21 Thread.State API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.State.html) | NEW、RUNNABLE、BLOCKED、WAITING、TIMED_WAITING、TERMINATED 六种状态定义正确 |
| `02-并发.md` 的 70 节 | [Java 21 ConcurrentHashMap API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html)、[OpenJDK 21 源码](https://github.com/openjdk/jdk/blob/jdk-21-ga/src/java.base/share/classes/java/util/concurrent/ConcurrentHashMap.java) | API 的并发语义正确；CAS 和桶首节点 `synchronized` 是 OpenJDK 实现细节 |

## 说明

这些章节是本项目 RAG 知识库的唯一来源材料，不是生产代码单元测试的断言来源。
测评期望词与原始文档中的标准技术术语对齐，但没有降低通过率或覆盖率门禁。
