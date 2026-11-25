package com.example.langchain4j.business.transaction;

import com.example.langchain4j.auth.security.UserContext;
import com.example.langchain4j.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 额度交易流水接口：当前用户只能操作自己的交易记录。
 */
@RestController
@RequestMapping("/api/transactions")
public class QuotaTransactionController {

    private final QuotaTransactionService transactionService;

    /** 注入交易流水领域服务；接口层只负责参数接收和统一响应。 */
    public QuotaTransactionController(QuotaTransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /** 创建交易流水。 */
    @PostMapping
    public ApiResponse<QuotaTransaction> create(
            @Valid @RequestBody QuotaTransactionRequest request) {
        return ApiResponse.success(transactionService.create(UserContext.require(), request));
    }

    /** 查询当前用户的交易流水。 */
    @GetMapping
    public ApiResponse<List<QuotaTransaction>> list(
            @RequestParam(required = false) Long quotaId,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(transactionService.list(UserContext.require(), quotaId, limit));
    }

    /** 查询当前用户的单条交易流水。 */
    @GetMapping("/{id}")
    public ApiResponse<QuotaTransaction> get(@PathVariable long id) {
        return ApiResponse.success(transactionService.get(id, UserContext.require()));
    }

    /** 更新当前用户的交易流水。 */
    @PutMapping("/{id}")
    public ApiResponse<QuotaTransaction> update(
            @PathVariable long id,
            @Valid @RequestBody QuotaTransactionRequest request) {
        return ApiResponse.success(transactionService.update(id, UserContext.require(), request));
    }

    /** 删除当前用户的交易流水。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        transactionService.delete(id, UserContext.require());
        return ApiResponse.success();
    }
}
