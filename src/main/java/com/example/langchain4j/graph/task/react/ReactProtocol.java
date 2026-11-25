package com.example.langchain4j.graph.task.react;

import java.util.Locale;

/**
 * ReAct 规划、工具执行和路由之间的动作协议常量。
 * 只集中跨节点共享的动作值和动作 JSON 字段；观察记录、工具轨迹等字段沿用任务链路
 * 现有的字面量约定，避免和 TaskReadResults、TaskWriteNode 等节点出现两套键名来源。
 * 系统提示词中的 JSON 示例必须与本类保持一致。
 */
public final class ReactProtocol {

    /** 动作值：调用一个白名单工具。 */
    public static final String ACTION_CALL_TOOL = "call_tool";

    /** 动作值：结束循环，基于已有证据回答。 */
    public static final String ACTION_FINAL = "final";

    /** JSON 和状态字段：动作类型。 */
    public static final String FIELD_ACTION = "action";

    /** JSON 和状态字段：工具名。 */
    public static final String FIELD_TOOL = "tool";

    /** JSON 和状态字段：工具参数对象。 */
    public static final String FIELD_ARGS = "args";

    /** JSON 和状态字段：一句话执行依据或停止原因。 */
    public static final String FIELD_REASON = "reason";

    /** 协议类只提供字段名和解析规则，禁止实例化。 */
    private ReactProtocol() {
    }

    /** 归一化模型输出的动作值：去空白并转小写，空值返回空字符串。 */
    public static String normalizeAction(String action) {
        return action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
    }

    /** 判断动作是否为 call_tool；未知或空动作统一返回 false。 */
    public static boolean isCallTool(String action) {
        return ACTION_CALL_TOOL.equals(normalizeAction(action));
    }

    /** 判断动作是否为 final；未知或空动作统一返回 false。 */
    public static boolean isFinal(String action) {
        return ACTION_FINAL.equals(normalizeAction(action));
    }
}
