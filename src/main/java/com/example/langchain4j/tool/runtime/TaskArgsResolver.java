package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.repository.entity.TaskStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;

/**
 * 步骤参数解析器：把写步骤里的引用替换成前序只读步骤的真实结果。
 *
 * 支持的引用写法包括 {"$ref":"1.0.id"}（第 1 步结果数组第 0 项的 id）、
 * {"$ref":"1#/0/id"}（同样的 JSON Pointer 写法）和 "prefix-$ref:1.0.id"
 * （字符串内整串引用）。
 *
 * 引用而不是让模型直接填 ID，是因为模型规划时还没看到查询结果，直接填只能靠猜；
 * 确认前先执行只读步骤，引用可以解析成真实值，用户看到的是准确参数；解析失败会明确
 * 报错，不会把占位符当成业务参数发给写工具。
 */
@Component
public class TaskArgsResolver {

    private static final String REF_KEY = "$ref";
    private static final String REF_PREFIX = "$ref:";

    /**
     * 解析一份参数快照。
     *
     * @param args  原始参数（可包含引用）
     * @param steps 已执行步骤，按 stepNo 索引
     * @return 全部引用替换后的参数；没有引用时原样返回
     * @throws TaskRefException 引用目标未成功、路径不存在或结果为空
     */
    public JsonNode resolve(JsonNode args, Map<Integer, TaskStep> steps) {
        if (args == null || args.isNull()) {
            return JsonUtil.mapper().createObjectNode();
        }
        return resolveNode(args, steps);
    }

    private JsonNode resolveNode(JsonNode node, Map<Integer, TaskStep> steps) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            if (object.size() == 1 && object.has(REF_KEY)) {
                return resolveRef(object.get(REF_KEY).asText(""), steps);
            }
            ObjectNode result = JsonUtil.mapper().createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.set(field.getKey(), resolveNode(field.getValue(), steps));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonUtil.mapper().createArrayNode();
            for (JsonNode item : node) {
                result.add(resolveNode(item, steps));
            }
            return result;
        }
        if (node.isTextual()) {
            String text = node.asText();
            if (text.startsWith(REF_PREFIX)) {
                return resolveRef(text.substring(REF_PREFIX.length()), steps);
            }
            if (text.contains(REF_PREFIX)) {
                throw new TaskRefException("字符串内引用只支持整串写法: " + text);
            }
        }
        return node;
    }

    private JsonNode resolveRef(String rawRef, Map<Integer, TaskStep> steps) {
        String ref = rawRef == null ? "" : rawRef.trim();
        if (ref.startsWith("step:")) {
            ref = ref.substring("step:".length());
        }
        if (ref.isBlank()) {
            throw new TaskRefException("引用不能为空");
        }

        int separator = firstSeparator(ref);
        String stepPart = separator < 0 ? ref : ref.substring(0, separator);
        String pathPart = separator < 0 ? "" : ref.substring(separator);
        int stepNo;
        try {
            stepNo = Integer.parseInt(stepPart.trim());
        } catch (NumberFormatException e) {
            throw new TaskRefException("引用步骤号非法: " + rawRef);
        }

        TaskStep step = steps == null ? null : steps.get(stepNo);
        if (step == null) {
            throw new TaskRefException("引用步骤不存在: " + stepNo);
        }
        if (step.getStatus() != TaskStep.Status.SUCCEEDED) {
            throw new TaskRefException("引用步骤尚未成功: " + stepNo);
        }
        if (step.getResultJson() == null || step.getResultJson().isBlank()) {
            throw new TaskRefException("引用步骤没有结构化结果: " + stepNo);
        }

        JsonNode result = JsonUtil.fromJson(step.getResultJson(), JsonNode.class);
        JsonNode target = navigate(result, pathPart);
        if (target == null || target.isMissingNode() || target.isNull()) {
            throw new TaskRefException("引用路径不存在: " + rawRef);
        }
        return target;
    }

    private int firstSeparator(String ref) {
        int dot = ref.indexOf('.');
        int hash = ref.indexOf('#');
        if (dot < 0) {
            return hash;
        }
        if (hash < 0) {
            return dot;
        }
        return Math.min(dot, hash);
    }

    /** 同时支持 `1.0.id` 和 `1#/0/id` 两种写法。 */
    private JsonNode navigate(JsonNode root, String pathPart) {
        if (pathPart == null || pathPart.isBlank()) {
            return root;
        }
        if (pathPart.startsWith("#")) {
            return root.at(pathPart.substring(1));
        }
        JsonNode current = root;
        String path = pathPart.startsWith(".") ? pathPart.substring(1) : pathPart;
        if (path.isBlank()) {
            return current;
        }
        for (String token : path.split("\\.")) {
            if (token.isBlank()) {
                continue;
            }
            if (current.isArray()) {
                int index;
                try {
                    index = Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    throw new TaskRefException("数组下标非法: " + token);
                }
                current = current.get(index);
            } else if (current.isObject()) {
                current = current.get(token);
            } else {
                throw new TaskRefException("引用路径无法继续: " + token);
            }
            if (current == null || current.isMissingNode()) {
                return null;
            }
        }
        return current;
    }

    /** 引用解析失败：作为步骤失败处理，不把占位符发给业务工具。 */
    public static class TaskRefException extends RuntimeException {
        public TaskRefException(String message) {
            super(message);
        }
    }
}
