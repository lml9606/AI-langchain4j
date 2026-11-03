package com.example.langchain4j.service;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.repository.entity.PromptConfig;
import com.example.langchain4j.repository.mapper.PromptConfigMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Prompt 配置服务：DB 覆盖代码默认提示词，保存后热生效（内存缓存，零查询开销）。
 * <p>
 * 三层语义：
 * <ul>
 *   <li>默认值兑底：key 无 DB 配置时返回代码内 PromptTemplates 常量（系统永远可用）；</li>
 *   <li>热加载：启动全量加载；保存/删除后仅更新对应 key 的缓存；</li>
 *   <li>白名单约束：key 必须是 PromptTemplates 已声明的常量名（反射枚举），
 *       拒绝任意 key 注入（防止提示词表被塞入无关内容混入缓存）。</li>
 * </ul>
 * 版本化：upsert 每次保存 version+1（审计可追溯）；多版本回滚需扩展历史表。
 */
@Slf4j
@Service
public class PromptTemplateService {

    /** 合法 key 白名单（PromptTemplates 中声明的全部字符串常量名，启动时反射枚举） */
    private final Set<String> knownKeys;
    /** 内存缓存：promptKey → DB 配置（无 DB 覆盖时不含该 key，resolve 走默认值兑底） */
    private final Map<String, PromptConfig> cache = new ConcurrentHashMap<>();
    private final PromptConfigMapper promptConfigMapper;

    public PromptTemplateService(PromptConfigMapper promptConfigMapper) {
        this.promptConfigMapper = promptConfigMapper;
        this.knownKeys = Arrays.stream(PromptTemplates.class.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                .map(Field::getName)
                .collect(Collectors.toUnmodifiableSet());
    }

    @PostConstruct
    void load() {
        try {
            List<PromptConfig> all = promptConfigMapper.selectAll();
            cache.clear();
            if (all != null) {
                for (PromptConfig config : all) {
                    if (knownKeys.contains(config.getPromptKey())) {
                        cache.put(config.getPromptKey(), config);
                    }
                }
            }
            log.info("[prompt] configs loaded, count={}, knownKeys={}", cache.size(), knownKeys.size());
        } catch (Exception e) {
            log.warn("[prompt] load configs failed, all keys fallback to code defaults: {}", e.getMessage());
        }
    }

    /**
     * 取生效提示词：DB 有覆盖返回 DB 版，否则返回代码默认值（兑底，永不返回空）。
     *
     * @param key         提示词 key（PromptTemplates 常量名）
     * @param defaultText 代码默认值（调用方传 PromptTemplates.XXX）
     */
    public String resolve(String key, String defaultText) {
        PromptConfig config = cache.get(key);
        if (config != null && config.getPromptText() != null && !config.getPromptText().isBlank()) {
            return config.getPromptText();
        }
        return defaultText;
    }

    /**
     * 取生效提示词并做参数格式化（对 {@link #resolve} 的模板用法封装）。
     * <p>
     * DB 覆盖文本是“格式串”身份：一旦含字面百分号（如“覆盖率达100%”）或占位符与
     * 代码默认模板不一致，{@code String.format} 会抛 IllegalFormatException；
     * 若不加防护，管理员一次误配置就能让生成链路整体报错（配置事故）。
     * 此处失败时兑底代码默认模板重试 —— 默认模板是代码常量，占位符与参数严格匹配，必成。
     *
     * @param key         提示词 key
     * @param defaultText 代码默认模板（含与 args 匹配的 %d/%s 占位符）
     * @param args        格式化参数
     */
    public String resolveFormatted(String key, String defaultText, Object... args) {
        String template = resolve(key, defaultText);
        try {
            return String.format(template, args);
        } catch (Exception e) {
            // DB 模板格式非法（字面 % / 占位符漂移）：兑底默认模板，防配置把链路改崩
            log.warn("[prompt] format failed, fallback to default template, key={}: {}", key, e.getMessage());
            return String.format(defaultText, args);
        }
    }

    /** 保存/覆盖提示词（白名单校验 + upsert 版本递增 + 热更新缓存） */
    public PromptConfig save(String key, String text, String updatedBy) {
        requireKnownKey(key);
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "提示词文本不能为空");
        }
        PromptConfig config = new PromptConfig();
        config.setPromptKey(key);
        config.setPromptText(normalized);
        config.setUpdatedBy(updatedBy);
        promptConfigMapper.upsert(config);
        // upsert 后版本号由 DB 递增，重查一次拿准确 version（热更新缓存）
        PromptConfig latest = promptConfigMapper.selectByKey(key);
        cache.put(key, latest);
        log.info("[prompt] saved, key={}, version={}, by={}", key,
                latest == null ? "?" : latest.getVersion(), updatedBy);
        return latest;
    }

    /** 删除 DB 覆盖 → 回退代码默认值（热更新） */
    public void delete(String key) {
        requireKnownKey(key);
        promptConfigMapper.deleteByKey(key);
        cache.remove(key);
        log.info("[prompt] deleted, key={}, fallback to code default", key);
    }

    /** 全部 key 的配置概览（生效来源 DB/默认 + 版本号），管理端展示 */
    public List<PromptConfig> listAll() {
        List<PromptConfig> dbConfigs = promptConfigMapper.selectAll();
        Map<String, PromptConfig> dbMap = dbConfigs == null ? Map.of()
                : dbConfigs.stream().collect(Collectors.toMap(PromptConfig::getPromptKey, c -> c));
        return knownKeys.stream().sorted().map(key -> {
            PromptConfig db = dbMap.get(key);
            if (db != null) {
                return db;
            }
            PromptConfig fallback = new PromptConfig();
            fallback.setPromptKey(key);
            fallback.setPromptText(defaultTextOf(key));
            fallback.setVersion(0); // version=0 表示代码默认值，未做 DB 覆盖
            return fallback;
        }).toList();
    }

    /** 取代码默认文本（管理端预览用） */
    public String defaultTextOf(String key) {
        try {
            Field field = PromptTemplates.class.getDeclaredField(key);
            return (String) field.get(null);
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    private void requireKnownKey(String key) {
        if (key == null || !knownKeys.contains(key)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "未知的提示词 key: " + key + "，可选: " + knownKeys);
        }
    }
}
