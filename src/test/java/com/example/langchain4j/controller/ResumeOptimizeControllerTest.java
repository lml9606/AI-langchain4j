package com.example.langchain4j.controller;

import com.example.langchain4j.common.auth.AuthUser;
import com.example.langchain4j.common.auth.JwtUtil;
import com.example.langchain4j.common.auth.UserRole;
import com.example.langchain4j.dto.ResumeOptimizeRequest;
import com.example.langchain4j.dto.ResumeOptimizeResponse;
import com.example.langchain4j.graph.resume.model.ResumePromptPresets;
import com.example.langchain4j.service.ResumeOptimizeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 简历优化接口契约测试：认证、参数校验、成功响应结构。
 */
@WebMvcTest(ResumeOptimizeController.class)
class ResumeOptimizeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResumeOptimizeService resumeOptimizeService;

    @MockitoBean
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        when(jwtUtil.parse("user-token")).thenReturn(
                Optional.of(new AuthUser("alice", UserRole.USER.name())));
    }

    @Test
    @DisplayName("优化成功 → code=0，返回 revisionId/revisedMarkdown")
    void optimizeSuccessContract() throws Exception {
        when(resumeOptimizeService.optimize(any(ResumeOptimizeRequest.class)))
                .thenReturn(response());

        mockMvc.perform(post("/api/resume/optimize")
                        .header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "resumeText": "## 工作经历\\nJava 开发",
                                  "resumeTitle": "Java 后端-张三",
                                  "mode": "TARGETED",
                                  "targets": [{"jobName": "Java 后端", "jd": "负责订单系统"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.revisionId").value("rv_1"))
                .andExpect(jsonPath("$.data.revisedMarkdown").value("## 工作经历\nJava 开发，负责订单系统"));
    }

    @Test
    @DisplayName("缺少 resumeText → HTTP 400 + code=40000")
    void missingResumeTextRejected() throws Exception {
        mockMvc.perform(post("/api/resume/optimize")
                        .header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode": "TARGETED", "targets": [{"jobName": "Java 后端"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("无 Token → HTTP 401")
    void unauthenticatedRejected() throws Exception {
        mockMvc.perform(get("/api/resume/revisions/rv_1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("预设提示词接口 → code=0，返回 5 个分类")
    void presetsContract() throws Exception {
        when(resumeOptimizeService.presets()).thenReturn(Map.of(
                "通用优化", List.of(new ResumePromptPresets.Preset("全面润色", "优化表达"))));

        mockMvc.perform(get("/api/resume/presets")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.通用优化[0].name").value("全面润色"));
    }

    private ResumeOptimizeResponse response() {
        return new ResumeOptimizeResponse(
                "rv_1", "Java 后端-张三", "TARGETED", "deepseek-flash", "DONE",
                null, "## 工作经历\nJava 开发，负责订单系统",
                List.of(), List.of(), 120, false, "", OffsetDateTime.now());
    }
}
