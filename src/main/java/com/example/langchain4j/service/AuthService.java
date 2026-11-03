package com.example.langchain4j.service;

import com.example.langchain4j.common.auth.JwtUtil;
import com.example.langchain4j.common.auth.UserRole;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.config.AuthProperties;
import com.example.langchain4j.dto.LoginRequest;
import com.example.langchain4j.dto.LoginResponse;
import com.example.langchain4j.dto.RegisterRequest;
import com.example.langchain4j.repository.entity.User;
import com.example.langchain4j.repository.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证服务：注册（BCrypt 哈希 + 落库）与登录（哈希比对 + 签发 JWT）。
 * <p>
 * 安全约定：
 * <ul>
 *   <li>密码只存 BCrypt 哈希（自带盐），明文不出 service；</li>
 *   <li>登录失败不区分“用户不存在/密码错误”（防用户名枚举）；</li>
 *   <li>并发注册同名靠唯一索引兜底（预检查 + DuplicateKeyException 双保险）。</li>
 * </ul>
 */
@Slf4j
@Service
public class AuthService {

    /** 防用户名枚举的哑哈希：用户不存在时照常执行一次 BCrypt 比对，抹平响应时间差 */
    private static final String DUMMY_PASSWORD_HASH = new BCryptPasswordEncoder().encode("dummy-password-placeholder");

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final AuthProperties properties;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(UserMapper userMapper, JwtUtil jwtUtil, AuthProperties properties) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.properties = properties;
    }

    /** 注册：用户名唯一性校验 + BCrypt 哈希落库 + 直接签发 Token（注册即登录，角色固定 USER） */
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse register(RegisterRequest request) {
        String username = request.username().trim();
        if (userMapper.selectByUsername(username) != null) {
            throw new BizException(ErrorCode.USERNAME_TAKEN, "用户名已存在: " + username);
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        // 注册默认普通用户，管理员由运维 SQL 指定（不提供注册提权通道）
        user.setRole(UserRole.USER.name());
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册兜底：预检查与插入之间另一请求已占用该用户名
            log.warn("[auth] register race, username already taken: {}", username);
            throw new BizException(ErrorCode.USERNAME_TAKEN, "用户名已存在: " + username);
        }
        log.info("[auth] user registered, username={}", username);
        return buildResponse(username, user.getRole());
    }

    /** 登录：BCrypt 比对（恒定时间，防时序侧信道），成功签发 JWT */
    public LoginResponse login(LoginRequest request) {
        String username = request.username().trim();
        User user = userMapper.selectByUsername(username);
        // 用户不存在时同样执行一次哈希比对（哑哈希），抹平响应时间差，防用户名枚举
        boolean matched = user != null
                ? passwordEncoder.matches(request.password(), user.getPasswordHash())
                : passwordEncoder.matches(request.password(), DUMMY_PASSWORD_HASH);
        if (!matched) {
            log.warn("[auth] login failed, username={}", username);
            throw new BizException(ErrorCode.INVALID_CREDENTIALS);
        }
        log.info("[auth] user logged in, username={}", username);
        return buildResponse(username, user.getRole());
    }

    private LoginResponse buildResponse(String username, String role) {
        return new LoginResponse(jwtUtil.issue(username, role), username, properties.getExpireHours(), role);
    }
}
