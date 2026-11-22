package com.example.langchain4j.common.util;

/**
 * 异常工具：统一提取异常链最深处的 message。
 *
 * <p>业务异常通常被框架层包装多次，最外层往往只有“调用失败”这类泛化信息；
 * 真正可定位的原因在 cause 链末端。工具执行、模型调用、图执行和知识导入共用这一份实现，
 * 避免各处对异常链的理解不一致。
 */
public final class Throwables {

    private Throwables() {
    }

    /**
     * 取异常链最深处的 message，保留原有行为：没有 message 时返回字符串 "null"。
     *
     * @param throwable 任意异常，允许为 null
     * @return 最深 cause 的 message
     */
    public static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }
}
