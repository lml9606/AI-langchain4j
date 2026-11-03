package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 用户实体：JWT 认证主体，userId（username）同时作为数据隔离维度。
 */
@Data
public class User {

    private Long id;
    /** 登录名（全局唯一，同时作为 JWT subject 与各业务表的 user_id） */
    private String username;
    /** BCrypt 密码哈希（含盐，明文不落库） */
    private String passwordHash;
    /** 角色（USER / ADMIN；注册默认 USER，管理员由运维 SQL 指定） */
    private String role;
    /** 注册时间 */
    private OffsetDateTime createdAt;
}
