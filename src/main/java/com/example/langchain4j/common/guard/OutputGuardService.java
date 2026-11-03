package com.example.langchain4j.common.guard;

import com.example.langchain4j.repository.entity.SensitiveWord;
import com.example.langchain4j.repository.mapper.SensitiveWordMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 输出侧审核：生成答案命中敏感词即拦截，替换为统一拒答文案。
 * <p>
 * 词表加载与刷新：启动时从 DB 加载启用词（DB 不可达降级为空词表 = 审核放行，可用性优先），
 * 管理端增删/启停后调用 {@link #refresh()} 热更新（内存缓存，零查询开销）。
 * <p>
 * 匹配策略为字面包含（最小可用版本）；企业级应接入内容安全 API（如阿里云内容安全），
 * 覆盖语义级违规（涉政/色情/暴力等），本组件仅做第一层兜底。
 */
@Slf4j
@Service
public class OutputGuardService {

    /** 命中敏感词的统一拒答文案（不透出命中词，防用户绕过测试） */
    public static final String BLOCKED_ANSWER = "抱歉，该问题触发了内容安全策略，无法回答。如有疑问请联系管理员。";

    private final SensitiveWordMapper sensitiveWordMapper;
    /** 启用词内存缓存（AtomicReference 保证无锁读 + 原子热更新） */
    private final AtomicReference<List<String>> words = new AtomicReference<>(List.of());

    public OutputGuardService(SensitiveWordMapper sensitiveWordMapper) {
        this.sensitiveWordMapper = sensitiveWordMapper;
    }

    @PostConstruct
    void load() {
        refresh();
    }

    /**
     * 扫描文本是否命中任一启用词。
     *
     * @return 命中词；未命中返回 empty
     */
    public java.util.Optional<String> scan(String text) {
        if (text == null || text.isBlank()) {
            return java.util.Optional.empty();
        }
        for (String word : words.get()) {
            if (text.contains(word)) {
                return java.util.Optional.of(word);
            }
        }
        return java.util.Optional.empty();
    }

    /** 是否应拦截（命中任一启用词） */
    public boolean blocked(String text) {
        return scan(text).isPresent();
    }

    /** 新增敏感词（重名抛业务异常；成功后热更新缓存） */
    public long addWord(String word) {
        String normalized = word == null ? "" : word.trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException("敏感词不能为空且长度不能超过 128");
        }
        SensitiveWord entity = new SensitiveWord();
        entity.setWord(normalized);
        entity.setEnabled(true);
        try {
            sensitiveWordMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("敏感词已存在: " + normalized);
        }
        refresh();
        log.info("[guard] sensitive word added: {}", normalized);
        return entity.getId();
    }

    /** 启用/停用词（成功后热更新） */
    public void setEnabled(long id, boolean enabled) {
        if (sensitiveWordMapper.updateEnabled(id, enabled) == 0) {
            throw new IllegalArgumentException("敏感词不存在: " + id);
        }
        refresh();
    }

    /** 删除词（成功后热更新） */
    public void delete(long id) {
        sensitiveWordMapper.deleteById(id);
        refresh();
    }

    /** 全量词表（管理端展示） */
    public List<SensitiveWord> listAll() {
        List<SensitiveWord> all = sensitiveWordMapper.selectAll();
        return all == null ? List.of() : all;
    }

    /** 从 DB 重载启用词（管理操作后热更新；DB 故障保留旧缓存，fail-open 审核放行） */
    public void refresh() {
        try {
            List<SensitiveWord> enabled = sensitiveWordMapper.selectEnabled();
            List<String> loaded = enabled == null ? List.of()
                    : enabled.stream().map(SensitiveWord::getWord).filter(w -> w != null && !w.isBlank()).toList();
            words.set(loaded);
            log.info("[guard] sensitive words loaded, count={}", loaded.size());
        } catch (Exception e) {
            log.warn("[guard] load sensitive words failed, keep cached ({}): {}",
                    words.get().size(), e.getMessage());
        }
    }
}
