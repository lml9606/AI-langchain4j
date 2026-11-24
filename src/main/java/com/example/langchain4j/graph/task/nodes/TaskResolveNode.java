package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.dto.request.LlmRequest;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.TaskOperation;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.service.PromptTemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务解析节点：一次模型调用完成“判断读写 + 抽取参数”。
 *
 * 当前读写工具只是流程骨架，节点内不校验真实业务权限、状态或工具策略。
 * 真实业务接入后，再把业务规则放到对应执行节点或领域服务中。
 */
@Slf4j
@Component
public class TaskResolveNode implements NodeAction<WorkflowState> {

    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;

    public TaskResolveNode(LlmGateway llmGateway, PromptTemplateService promptTemplateService) {
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
    }

    /**
     * 解析用户请求并写入操作类型、参数和缺失项。
     * 缺少必要参数时直接生成追问文案，不进入读写执行节点。
     */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        long start = System.currentTimeMillis();
        String systemPrompt = promptTemplateService.resolve(
                "TASK_RESOLVE_SYSTEM", PromptTemplates.TASK_RESOLVE_SYSTEM);
        String userPrompt = "用户请求：\n" + state.effectiveQuery();
        log.info("[task-resolve] start, queryLength={}, model={}", state.effectiveQuery().length(),
                state.model());

        try {
            JsonNode node = llmGateway.generateJson(
                    LlmRequest.of(state.model(), systemPrompt, userPrompt).scene("task-resolve"));
            if (node == null) {
                return error("任务解析失败：模型输出为空或非 JSON");
            }

            TaskOperation operation = TaskOperation.parse(node.path("operation").asText(""));
            if (operation == TaskOperation.REACT) {
                log.warn("[task-resolve] react reserved but disabled, queryLength={}",
                        state.effectiveQuery().length());
                return error("当前暂不支持自由工具调用，请明确说明要查询还是修改演示记录");
            }
            if (operation == null) {
                return error("无法判断是查询还是修改，请明确说明要做什么");
            }

            Map<String, Object> args = parseArgs(node.get("args"));
            List<String> missing = parseMissing(node.get("missing"));
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(WorkflowState.TASK_OPERATION, operation.name());
            updates.put(WorkflowState.TASK_RESOLVE_REASON, node.path("reason").asText(""));
            updates.put(WorkflowState.TASK_ARGS, args);
            updates.put(WorkflowState.TASK_MISSING, missing);
            updates.put(WorkflowState.TASK_PENDING_ACTION, Map.of());

            if (!missing.isEmpty()) {
                String labels = String.join("、", missing);
                String answer = promptTemplateService.resolveFormatted(
                        "TASK_CLARIFY_ANSWER", PromptTemplates.TASK_CLARIFY_ANSWER, labels);
                updates.put(WorkflowState.TASK_CLARIFY, true);
                updates.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, false);
                updates.put(WorkflowState.TASK_CONFIDENCE, 1.0);
                updates.put(WorkflowState.TASK_CONFIDENCE_REASON, "缺少必要参数，已生成追问");
                updates.put(WorkflowState.RESULT, answer);
                updates.put(WorkflowState.ERROR, "");
                log.info("[task-resolve] clarification required, operation={}, missing={}, costMs={}",
                        operation, missing, System.currentTimeMillis() - start);
                return updates;
            }

            updates.put(WorkflowState.TASK_CLARIFY, false);
            updates.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, operation == TaskOperation.WRITE);
            updates.put(WorkflowState.ERROR, "");
            log.info("[task-resolve] done, operation={}, argKeys={}, reason={}, costMs={}",
                    operation, args.keySet(), node.path("reason").asText(""),
                    System.currentTimeMillis() - start);
            return updates;
        } catch (BizException e) {
            log.warn("[task-resolve] llm failed: {}", e.getMessage());
            return error("任务解析失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("[task-resolve] unexpected error", e);
            return error("任务解析失败: " + e.getMessage());
        }
    }

    /** 参数只接受 JSON 对象，统一转换成字符串键 Map。 */
    private Map<String, Object> parseArgs(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, Object> args = JsonUtil.mapper().convertValue(
                node, new TypeReference<Map<String, Object>>() {
                });
        return args == null ? Map.of() : args;
    }

    /** 缺失项只接受字符串数组，过滤空白值。 */
    private List<String> parseMissing(JsonNode node) {
        List<String> missing = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                String value = item.asText("").trim();
                if (!value.isBlank()) {
                    missing.add(value);
                }
            });
        }
        return missing;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.ERROR, message);
        return updates;
    }
}