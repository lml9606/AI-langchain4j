package com.example.langchain4j.tool.demo;

import com.example.langchain4j.auth.security.UserContext;
import com.example.langchain4j.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工具演示数据接口：用于播种与核对工具读/写结果。
 * 仅供本地演示与联调；生产环境可删除或加管理员权限。
 */
@RestController
@RequestMapping("/api/tool-demo")
public class ToolDemoController {

    private static final int MAX_LIMIT = 50;

    private final DemoRecordService demoRecordService;

    public ToolDemoController(DemoRecordService demoRecordService) {
        this.demoRecordService = demoRecordService;
    }

    /** 为当前用户播种 3 条演示记录；已有数据时不重复写入 */
    @Operation(summary = "播种工具演示数据")
    @PostMapping("/seed")
    public ApiResponse<Integer> seed() {
        return ApiResponse.success(demoRecordService.seed(UserContext.require()));
    }

    /** 查询当前用户的演示记录（直接查库，用于核对工具执行结果） */
    @Operation(summary = "查询工具演示数据")
    @GetMapping("/records")
    public ApiResponse<List<ToolDemoRecord>> records(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit) {
        int bounded = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return ApiResponse.success(demoRecordService.list(UserContext.require(), status, bounded));
    }
}
