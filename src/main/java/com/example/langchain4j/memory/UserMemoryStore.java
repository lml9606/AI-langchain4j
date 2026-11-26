package com.example.langchain4j.memory;

import com.example.langchain4j.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 记忆写入存储层：把“读旧值 → 写历史 → 写主表”放在同一事务内。
 *
 * 单独拆出来的原因：UserMemoryService 的抽取方法在异步线程里调用，类内自调用不会触发
 * Spring 事务代理；把事务边界放在独立 Bean，才能保证历史记录和主表更新一起成功或一起回滚。
 */
@Slf4j
@Service
public class UserMemoryStore {

    private final UserMemoryMapper memoryMapper;
    private final UserMemoryHistoryMapper historyMapper;
    private final AgentProperties properties;

    /** 注入主表、变更历史 Mapper 和记忆配置；本类无本地状态，可安全并发复用。 */
    public UserMemoryStore(UserMemoryMapper memoryMapper,
                           UserMemoryHistoryMapper historyMapper,
                           AgentProperties properties) {
        this.memoryMapper = memoryMapper;
        this.historyMapper = historyMapper;
        this.properties = properties;
    }

    /**
     * 保存一条抽取结果：同 key 覆盖、内容变化写历史、第二次出现自动晋升 ACTIVE。
     *
     * @param candidate 已完成类型/内容/重要度校验的候选记忆
     * @return 落库后的完整记录
     */
    @Transactional(rollbackFor = Exception.class)
    public UserMemory saveExtracted(UserMemory candidate) {
        UserMemory existing = memoryMapper.selectByKeyForUpdate(candidate.getUserId(),
                candidate.getMemoryType(), candidate.getMemoryKey());
        if (existing != null) {
            // 内容变化才写历史，避免同内容重复抽取把历史表打满
            if (!Objects.equals(existing.getContent(), candidate.getContent())) {
                historyMapper.insert(existing.getId(), existing.getUserId(),
                        existing.getMemoryType(), existing.getMemoryKey(),
                        existing.getContent(), candidate.getContent(), "EXTRACT");
            }
            // 同一 key 第二次出现即晋升：单次抽取可能误判，重复出现才视为稳定信号
            candidate.setStatus(UserMemory.STATUS_ACTIVE);
            if (existing.getImportance() != null && candidate.getImportance() != null) {
                candidate.setImportance(Math.max(existing.getImportance(),
                        candidate.getImportance()));
            }
            if (candidate.getValidFrom() == null) {
                candidate.setValidFrom(existing.getValidFrom());
            }
        } else {
            if (candidate.getStatus() == null) {
                candidate.setStatus(UserMemory.STATUS_CANDIDATE);
            }
            if (candidate.getValidFrom() == null) {
                candidate.setValidFrom(OffsetDateTime.now());
            }
        }
        // 有效期取决于最终状态：候选 30 天；晋升/生效后按类型给完整 TTL
        candidate.setExpiresAt(expiresAt(candidate.getMemoryType(), candidate.getStatus()));
        memoryMapper.upsert(candidate);
        UserMemory saved = memoryMapper.selectByKey(candidate.getUserId(),
                candidate.getMemoryType(), candidate.getMemoryKey());
        log.info("[memory-store] saved, id={}, type={}, key={}, status={}, seen={}, source={}",
                saved == null ? null : saved.getId(), candidate.getMemoryType(),
                candidate.getMemoryKey(), candidate.getStatus(),
                saved == null ? null : saved.getSeenCount(), candidate.getSourceMessageId());
        return saved;
    }

    /**
     * 手动修改内容与重要度：允许降低重要度，修改后状态提升为 ACTIVE。
     *
     * @param userId       当前用户（SQL 层再校验归属）
     * @param id           记忆 ID
     * @param content      新内容
     * @param importance   新重要度（0~1）
     * @param changeSource MANUAL
     * @return 修改后的记录；不存在或不属于当前用户返回 null
     */
    @Transactional(rollbackFor = Exception.class)
    public UserMemory updateContent(String userId, Long id, String content,
                                    double importance, String changeSource) {
        UserMemory existing = memoryMapper.selectByIdForUpdate(userId, id);
        if (existing == null) {
            return null;
        }
        if (!Objects.equals(existing.getContent(), content)) {
            historyMapper.insert(existing.getId(), existing.getUserId(),
                    existing.getMemoryType(), existing.getMemoryKey(),
                    existing.getContent(), content, changeSource);
        }
        memoryMapper.updateContent(userId, id, content, importance);
        return memoryMapper.selectById(userId, id);
    }

    /**
     * 按类型和状态计算有效期。
     *
     * 候选记忆用更短的 candidateTtlDays，避免误判内容长期占位；
     * 生效记忆按类型给 TTL，配置 <=0 表示永久。
     */
    private OffsetDateTime expiresAt(String type, String status) {
        AgentProperties.Memory config = properties.getMemory();
        int days;
        if (UserMemory.STATUS_CANDIDATE.equals(status)) {
            days = config.getCandidateTtlDays();
        } else if (UserMemory.TYPE_PREFERENCE.equals(type)) {
            days = config.getTtlDaysPreference();
        } else if (UserMemory.TYPE_TASK_CONSTRAINT.equals(type)) {
            days = config.getTtlDaysConstraint();
        } else if (UserMemory.TYPE_TEMPORARY.equals(type)) {
            days = config.getTtlDaysTemporary();
        } else if (UserMemory.TYPE_FACT.equals(type)) {
            days = config.getTtlDaysFact();
        } else {
            days = config.getTtlDays();
        }
        return days <= 0 ? null : OffsetDateTime.now().plusDays(days);
    }
}
