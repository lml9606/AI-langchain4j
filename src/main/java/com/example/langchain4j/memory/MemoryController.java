package com.example.langchain4j.memory;

import com.example.langchain4j.auth.security.UserContext;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.memory.dto.MemoryCreateRequest;
import com.example.langchain4j.memory.dto.MemoryDto;
import com.example.langchain4j.memory.dto.MemoryUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 长期记忆管理接口：用户查看、手动修正和删除自己的记忆。
 *
 * 身份一律以 JWT 为准，不接收请求体中的 userId；所有操作在 SQL 层再按 user_id 过滤，
 * 防止水平越权。
 */
@RestController
@RequestMapping("/api/agent/memories")
public class MemoryController {

    private final UserMemoryService userMemoryService;

    public MemoryController(UserMemoryService userMemoryService) {
        this.userMemoryService = userMemoryService;
    }

    /**
     * 查看自己的长期记忆（按更新时间倒序）。
     *
     * @param limit 返回条数（默认 50，服务层收敛到 1~200）
     * @return 记忆列表
     */
    @GetMapping
    public ApiResponse<List<MemoryDto>> list(@RequestParam(defaultValue = "50") int limit) {
        String userId = UserContext.require();
        List<MemoryDto> memories = userMemoryService.list(userId, limit).stream()
                .map(MemoryDto::of)
                .toList();
        return ApiResponse.success(memories);
    }

    /**
     * 手动新增或覆盖一条记忆（用于纠正错误抽取）。
     *
     * 同一类型 + key 已存在时覆盖内容，重要度取历史最大值。
     *
     * @param request 记忆内容；memoryType 只能是 PREFERENCE/FACT/TASK_CONSTRAINT
     * @return 写入后的完整记录
     */
    @PostMapping
    public ApiResponse<MemoryDto> save(@Valid @RequestBody MemoryCreateRequest request) {
        String userId = UserContext.require();
        double importance = request.importance() == null ? 0.8 : request.importance();
        UserMemory memory = userMemoryService.remember(userId, request.memoryType(),
                request.memoryKey(), request.content(), importance, null);
        return ApiResponse.success(MemoryDto.of(memory));
    }

    /**
     * 手动修改自己的一条记忆。
     *
     * 与 POST 的区别：这里可以调整重要度（允许调低），也可以把候选记忆直接提升为生效。
     *
     * @param id      记忆 ID
     * @param request 新内容与重要度（importance 为空时按 0.8 处理）
     * @return 修改后的记录；不存在或不属于当前用户返回 404
     */
    @PatchMapping("/{id}")
    public ApiResponse<MemoryDto> update(@PathVariable Long id,
                                         @Valid @RequestBody MemoryUpdateRequest request) {
        String userId = UserContext.require();
        UserMemory memory = userMemoryService.update(userId, id, request.content(),
                request.importance());
        if (memory == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "记忆不存在或无权操作");
        }
        return ApiResponse.success(MemoryDto.of(memory));
    }

    /**
     * 删除自己的一条记忆。
     *
     * @param id 记忆 ID；不存在或不属于当前用户返回 404（不泄露存在性）
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        String userId = UserContext.require();
        if (!userMemoryService.delete(userId, id)) {
            throw new BizException(ErrorCode.NOT_FOUND, "记忆不存在或无权操作");
        }
        return ApiResponse.success(true);
    }
}
