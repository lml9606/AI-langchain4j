package com.example.langchain4j.controller;

import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.dto.request.EnrollmentRequest;
import com.example.langchain4j.repository.entity.Enrollment;
import com.example.langchain4j.service.EnrollmentService;
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
 * 报名记录接口：当前用户只能操作自己的报名数据。
 */
@RestController
@RequestMapping("/api/enrollments")
public class EnrollmentController {

    private final EnrollmentService enrollmentService;

    public EnrollmentController(EnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    /** 创建报名记录。 */
    @PostMapping
    public ApiResponse<Enrollment> create(@Valid @RequestBody EnrollmentRequest request) {
        return ApiResponse.success(enrollmentService.create(UserContext.require(), request));
    }

    /** 查询当前用户的报名列表。 */
    @GetMapping
    public ApiResponse<List<Enrollment>> list(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(enrollmentService.list(UserContext.require(), limit));
    }

    /** 查询当前用户的单条报名记录。 */
    @GetMapping("/{id}")
    public ApiResponse<Enrollment> get(@PathVariable long id) {
        return ApiResponse.success(enrollmentService.get(id, UserContext.require()));
    }

    /** 更新当前用户的报名记录。 */
    @PutMapping("/{id}")
    public ApiResponse<Enrollment> update(@PathVariable long id,
                                          @Valid @RequestBody EnrollmentRequest request) {
        return ApiResponse.success(enrollmentService.update(id, UserContext.require(), request));
    }

    /** 删除当前用户的报名记录。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        enrollmentService.delete(id, UserContext.require());
        return ApiResponse.success();
    }
}
