package com.example.langchain4j.service;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.repository.entity.Conversation;
import com.example.langchain4j.repository.entity.Message;
import com.example.langchain4j.repository.mapper.ConversationMapper;
import com.example.langchain4j.repository.mapper.MessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/** 会话视图单测：改写视图必须读取真实回答，不能被检索快照替代。 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private MessageMapper messageMapper;

    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(conversationMapper, messageMapper, new AgentProperties());
    }

    @Test
    @DisplayName("改写视图 → assistant 使用真实回答，不使用检索上下文快照")
    void rewriteHistoryViewUsesAssistantContent() {
        Message previousUser = message(1L, Message.ROLE_USER, "equals 和 == 有什么区别", null);
        Message previousAssistant = message(2L, Message.ROLE_ASSISTANT,
                "== 比较地址，equals 重写后比较内容", "Java 值传递的检索资料");
        Message currentUser = message(3L, Message.ROLE_USER, "详细说说", null);
        when(messageMapper.selectRecentByConversationId("c1", 3))
                .thenReturn(List.of(previousUser, previousAssistant, currentUser));

        List<Map<String, String>> history = conversationService.rewriteHistoryView("c1", 2, 800);

        assertThat(history).containsExactly(
                Map.of("role", Message.ROLE_USER, "content", "equals 和 == 有什么区别"),
                Map.of("role", Message.ROLE_ASSISTANT, "content", "== 比较地址，equals 重写后比较内容"));
    }

    @Test
    @DisplayName("传入不存在的会话 ID -> 用同一个 ID 幂等补建，不丢弃历史锚点")
    void bootstrapMissingConversationWithSameId() {
        Conversation persisted = new Conversation();
        persisted.setConversationId("c-existing");
        persisted.setUserId("demo");
        when(conversationMapper.selectByConversationId("c-existing"))
                .thenReturn(null, persisted);

        String conversationId = conversationService.ensureConversation(
                "demo", "c-existing", "第一次提问");

        ArgumentCaptor<Conversation> inserted = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getConversationId()).isEqualTo("c-existing");
        assertThat(conversationId).isEqualTo("c-existing");
    }

    @Test
    @DisplayName("带幂等键的消息写入冲突 -> 返回数据库已有消息，不抛异常")
    void idempotentMessageInsertReturnsExisting() {
        Message existing = message(99L, Message.ROLE_USER, "任务完成", null);
        existing.setDedupeKey("task-reply:r1");
        when(messageMapper.selectByDedupeKey("task-reply:r1")).thenReturn(existing);

        Message saved = conversationService.saveMessage("c1", Message.ROLE_USER, "任务完成",
                "model-x", null, null, "task-reply:r1");

        assertThat(saved).isSameAs(existing);
        verify(messageMapper).insert(any(Message.class));
    }

    private Message message(Long id, String role, String content, String context) {
        Message message = new Message();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        message.setContext(context);
        return message;
    }
}
