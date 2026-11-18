package com.example.langchain4j.service;

import com.example.langchain4j.repository.entity.Message;
import com.example.langchain4j.repository.mapper.ConversationMapper;
import com.example.langchain4j.repository.mapper.MessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
        conversationService = new ConversationService(conversationMapper, messageMapper);
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

    private Message message(Long id, String role, String content, String context) {
        Message message = new Message();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        message.setContext(context);
        return message;
    }
}
