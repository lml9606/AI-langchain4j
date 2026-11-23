package com.example.langchain4j.audit;

import java.util.Map;

/**
 * 结构化审计载荷提供者：返回值实现该接口时，注解切面优先使用
 * {@link #auditPayload()}，而不是对返回值做通用 toString。
 */
public interface AuditPayloadProvider {

    Map<String, Object> auditPayload();
}
