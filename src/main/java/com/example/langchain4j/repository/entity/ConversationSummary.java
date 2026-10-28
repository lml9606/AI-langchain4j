package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 会话段摘要：会话内一个话题段（segment）的增量摘要。
 * <p>
 * 生命周期：
 * <ol>
 *   <li>话题切换时创建段记录（start_message_id = 当前消息 id，content 为空）；</li>
 *   <li>段内滑出窗口的消息达到阈值后，异步压缩并增量追加到 content，
 *       covered_message_id 记录已覆盖到哪条消息（增量锚点）；</li>
 *   <li>用户切回旧话题时，按段注入对应摘要 + 该段窗口，其他段不注入。</li>
 * </ol>
 */
@Data
public class ConversationSummary {

    private Long id;
    /** 所属会话 ID */
    private String conversationId;
    /** 会话内话题段序号（从 1 递增） */
    private Integer segmentId;
    /** 话题标签（改写器输出，可空） */
    private String topic;
    /** 段摘要内容（增量追加） */
    private String content;
    /** 段起点消息 id（含） */
    private Long startMessageId;
    /** 摘要已覆盖到的消息 id（可空 = 尚未生成摘要） */
    private Long coveredMessageId;
    /** 创建时间 */
    private OffsetDateTime createdAt;
    /** 更新时间 */
    private OffsetDateTime updatedAt;
}
