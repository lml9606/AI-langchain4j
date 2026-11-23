package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 工具参数校验器：以 ToolDefinition.inputSchema 为唯一契约，在执行前做通用防御。
 *
 * 覆盖生产上最常见的约束：对象、数组、标量类型、required、enum、数值边界、
 * 字符串长度/正则、数组长度、additionalProperties=false、anyOf/oneOf/allOf。
 * 工具自身仍可做业务校验，但模型输出不能绕过这一层。
 */
public final class ToolInputValidator {

    private static final String ROOT = "$";

    private ToolInputValidator() {
    }

    public static Result validate(ToolDefinition definition, JsonNode args) {
        if (definition == null) {
            return Result.invalid("工具定义不存在");
        }
        JsonNode schema;
        try {
            schema = JsonUtil.mapper().readTree(definition.inputSchema());
        } catch (Exception e) {
            return Result.invalid("工具参数 Schema 不是合法 JSON: " + definition.name());
        }
        JsonNode value = args == null || args.isNull()
                ? JsonNodeFactory.instance.objectNode() : args;
        return validateNode(schema, value, ROOT);
    }

    private static Result validateNode(JsonNode schema, JsonNode value, String path) {
        if (schema == null || schema.isNull() || schema.isEmpty()) {
            return Result.ok();
        }
        if (schema.isBoolean()) {
            return schema.asBoolean() ? Result.ok() : Result.invalid(path + " 不允许该值");
        }
        if (!schema.isObject()) {
            return Result.invalid("参数 Schema 节点必须是对象: " + path);
        }

        Result allOf = validateAllOf(schema.get("allOf"), value, path);
        if (!allOf.valid()) {
            return allOf;
        }
        Result anyOf = validateAnyOf(schema, value, path);
        if (!anyOf.valid()) {
            return anyOf;
        }

        Result type = validateType(schema.get("type"), value, path);
        if (!type.valid()) {
            return type;
        }
        if (!matchesEnum(schema.get("enum"), value)) {
            return Result.invalid(path + " 必须是枚举值之一");
        }
        JsonNode constNode = schema.get("const");
        if (constNode != null && !sameValue(constNode, value)) {
            return Result.invalid(path + " 必须等于固定值");
        }

        if (value.isObject()) {
            Result objectResult = validateObject(schema, value, path);
            if (!objectResult.valid()) {
                return objectResult;
            }
        } else if (value.isArray()) {
            Result arrayResult = validateArray(schema, value, path);
            if (!arrayResult.valid()) {
                return arrayResult;
            }
        } else if (value.isTextual()) {
            Result stringResult = validateString(schema, value.asText(), path);
            if (!stringResult.valid()) {
                return stringResult;
            }
        } else if (value.isNumber()) {
            Result numberResult = validateNumber(schema, value.decimalValue(), path);
            if (!numberResult.valid()) {
                return numberResult;
            }
        }
        return Result.ok();
    }

    private static Result validateType(JsonNode typeNode, JsonNode value, String path) {
        if (typeNode == null || typeNode.isNull()) {
            return Result.ok();
        }
        if (typeNode.isArray()) {
            for (JsonNode type : typeNode) {
                if (matchesType(type.asText(""), value)) {
                    return Result.ok();
                }
            }
            return Result.invalid(path + " 类型不匹配");
        }
        return matchesType(typeNode.asText(""), value)
                ? Result.ok() : Result.invalid(path + " 类型不匹配，期望 " + typeNode.asText());
    }

    private static boolean matchesType(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private static Result validateObject(JsonNode schema, JsonNode value, String path) {
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode field : required) {
                String name = field.asText("");
                JsonNode fieldValue = value.get(name);
                if (missing(fieldValue)) {
                    return Result.invalid(path + " 缺少必填参数: " + name);
                }
            }
        }

        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode propertySchema = properties.get(entry.getKey());
                if (propertySchema != null && !missing(entry.getValue())) {
                    Result result = validateNode(propertySchema, entry.getValue(),
                            path + "." + entry.getKey());
                    if (!result.valid()) {
                        return result;
                    }
                }
            }
        }

        JsonNode additional = schema.get("additionalProperties");
        if (additional != null && additional.isBoolean() && !additional.asBoolean()) {
            Iterator<String> names = value.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (properties == null || !properties.has(name)) {
                    return Result.invalid(path + " 包含未知参数: " + name);
                }
            }
        } else if (additional != null && additional.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (properties == null || !properties.has(entry.getKey())) {
                    Result result = validateNode(additional, entry.getValue(),
                            path + "." + entry.getKey());
                    if (!result.valid()) {
                        return result;
                    }
                }
            }
        }
        return Result.ok();
    }

    private static Result validateArray(JsonNode schema, JsonNode value, String path) {
        Result size = validateSize(schema, value.size(), path);
        if (!size.valid()) {
            return size;
        }
        JsonNode items = schema.get("items");
        if (items == null || items.isNull()) {
            return Result.ok();
        }
        for (int i = 0; i < value.size(); i++) {
            Result result = validateNode(items, value.get(i), path + "[" + i + "]");
            if (!result.valid()) {
                return result;
            }
        }
        return Result.ok();
    }

    private static Result validateString(JsonNode schema, String value, String path) {
        int length = value.length();
        int minLength = schema.path("minLength").asInt(-1);
        if (minLength >= 0 && length < minLength) {
            return Result.invalid(path + " 长度不能小于 " + minLength);
        }
        int maxLength = schema.path("maxLength").asInt(-1);
        if (maxLength >= 0 && length > maxLength) {
            return Result.invalid(path + " 长度不能超过 " + maxLength);
        }
        String pattern = schema.path("pattern").asText("");
        if (!pattern.isBlank()) {
            try {
                if (!Pattern.compile(pattern).matcher(value).find()) {
                    return Result.invalid(path + " 格式不合法");
                }
            } catch (Exception e) {
                return Result.invalid("参数 Schema 正则非法: " + path);
            }
        }
        return Result.ok();
    }

    private static Result validateNumber(JsonNode schema, BigDecimal value, String path) {
        JsonNode minimum = schema.get("minimum");
        if (minimum != null && minimum.isNumber() && value.compareTo(minimum.decimalValue()) < 0) {
            return Result.invalid(path + " 不能小于 " + minimum.asText());
        }
        JsonNode maximum = schema.get("maximum");
        if (maximum != null && maximum.isNumber() && value.compareTo(maximum.decimalValue()) > 0) {
            return Result.invalid(path + " 不能大于 " + maximum.asText());
        }
        return Result.ok();
    }

    private static Result validateSize(JsonNode schema, int size, String path) {
        int minItems = schema.path("minItems").asInt(-1);
        if (minItems >= 0 && size < minItems) {
            return Result.invalid(path + " 元素数量不能少于 " + minItems);
        }
        int maxItems = schema.path("maxItems").asInt(-1);
        if (maxItems >= 0 && size > maxItems) {
            return Result.invalid(path + " 元素数量不能超过 " + maxItems);
        }
        return Result.ok();
    }

    private static Result validateAllOf(JsonNode allOf, JsonNode value, String path) {
        if (allOf == null || !allOf.isArray()) {
            return Result.ok();
        }
        for (JsonNode branch : allOf) {
            Result result = validateNode(branch, value, path);
            if (!result.valid()) {
                return result;
            }
        }
        return Result.ok();
    }

    private static Result validateAnyOf(JsonNode schema, JsonNode value, String path) {
        JsonNode anyOf = schema.get("anyOf");
        JsonNode oneOf = schema.get("oneOf");
        JsonNode branches = anyOf != null && anyOf.isArray() ? anyOf
                : oneOf != null && oneOf.isArray() ? oneOf : null;
        if (branches == null || branches.isEmpty()) {
            return Result.ok();
        }
        int matched = 0;
        for (JsonNode branch : branches) {
            if (validateNode(branch, value, path).valid()) {
                matched++;
            }
        }
        if (anyOf != null && matched > 0) {
            return Result.ok();
        }
        if (oneOf != null && matched == 1) {
            return Result.ok();
        }
        return Result.invalid(path + " 不满足参数组合约束");
    }

    private static boolean matchesEnum(JsonNode enumNode, JsonNode value) {
        if (enumNode == null || !enumNode.isArray()) {
            return true;
        }
        for (JsonNode candidate : enumNode) {
            if (sameValue(candidate, value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameValue(JsonNode left, JsonNode right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        return left.equals(right);
    }

    private static boolean missing(JsonNode value) {
        return value == null || value.isNull()
                || (value.isTextual() && value.asText().isBlank());
    }

    public record Result(boolean valid, String message) {

        public static Result ok() {
            return new Result(true, "");
        }

        public static Result invalid(String message) {
            return new Result(false, message);
        }
    }
}
