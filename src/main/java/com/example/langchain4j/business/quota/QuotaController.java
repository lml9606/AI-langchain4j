package com.example.langchain4j.business.quota;

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
 * 额度账户接口：当前用户只能操作自己的额度账户。
 */
@RestController
@RequestMapping("/api/quotas")
public class QuotaController {

    private final QuotaService quotaService;

    /** 注入额度领域服务；接口层只负责参数接收和统一响应。 */
    public QuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    /** 创建额度账户。 */
    @PostMapping
    public ApiResponse<Quota> create(@Valid @RequestBody QuotaRequest request) {
        return ApiResponse.success(quotaService.create(UserContext.require(), request));
    }

    /** 查询当前用户的额度账户列表。 */
    @GetMapping
    public ApiResponse<List<Quota>> list(
            @RequestParam(required = false) String enrollmentNo,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(quotaService.list(
                UserContext.require(), enrollmentNo, null, null, limit));
    }

    /** 查询当前用户的单条额度账户。 */
    @GetMapping("/{id}")
    public ApiResponse<Quota> get(@PathVariable long id) {
        return ApiResponse.success(quotaService.get(id, UserContext.require()));
    }

    /** 更新当前用户的额度账户。 */
    @PutMapping("/{id}")
    public ApiResponse<Quota> update(@PathVariable long id,
                                     @Valid @RequestBody QuotaRequest request) {
        return ApiResponse.success(quotaService.update(id, UserContext.require(), request));
    }

    /** 删除当前用户的额度账户。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        quotaService.delete(id, UserContext.require());
        return ApiResponse.success();
    }
}
