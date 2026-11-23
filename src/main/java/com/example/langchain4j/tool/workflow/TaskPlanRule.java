package com.example.langchain4j.tool.workflow;

/**
 * 任务计划扩展规则。
 *
 * 内置规则（工具契约、参数 Schema、写操作唯一性、pending 一致性）永远先执行；这里只允许
 * 追加业务拒绝条件，例如租户配额、金额审批阈值、数据范围或行业合规要求。扩展规则不能把
 * 内置规则已经拒绝的计划改回合法，也不能修改计划内容。
 *
 * 实现类注册为 Spring Bean 后会被自动收集；可用 {@code @Order} 控制执行顺序。没有扩展 Bean
 * 时校验器会得到空列表，不影响基础安全链路。
 */
@FunctionalInterface
public interface TaskPlanRule {

    ValidationResult validate(TaskPlanContext context);
}
