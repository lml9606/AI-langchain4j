package com.example.langchain4j.conversation;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 会话隔离测评：验证水平越权会话被 403 拒绝，缺失会话按 404 处理。 */
@ExtendWith(MockitoExtension.class)
class ConversationIsolationTest {

    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private MessageMapper messageMapper;

    private ConversationService service;

    /** 使用真实属性并只替换持久层依赖。 */
    @BeforeEach
    void setUp() {
        service = new ConversationService(conversationMapper, messageMapper, new AgentProperties());
    }

    /** 会话属于当前用户时复用原会话 ID。 */
    @Test
    void ownerCanReuseConversation() {
        Conversation conversation = conversation("c1", "alice");
        when(conversationMapper.selectByConversationId("c1")).thenReturn(conversation);

        assertThat(service.ensureConversation("alice", "c1", "hello")).isEqualTo("c1");
    }

    /** 已存在但属于他人的会话必须拒绝，不能泄露消息内容。 */
    @Test
    void crossUserConversationIsForbidden() {
        when(conversationMapper.selectByConversationId("c1"))
                .thenReturn(conversation("c1", "bob"));

        assertThatThrownBy(() -> service.ensureConversation("alice", "c1", "hello"))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    /** 传入不存在 ID 时按同一 ID 补建，避免合法深链每次新建会话。 */
    @Test
    void missingConversationIdIsBootstrappedIdempotently() {
        when(conversationMapper.selectByConversationId("c-new"))
                .thenReturn(null, conversation("c-new", "alice"));

        assertThat(service.ensureConversation("alice", "c-new", "hello")).isEqualTo("c-new");
        verify(conversationMapper).insert(any(Conversation.class));
    }

    /** 直接查询历史或状态时，不存在会话返回 404。 */
    @Test
    void missingConversationForOwnershipCheckReturnsNotFound() {
        when(conversationMapper.selectByConversationId("missing")).thenReturn(null);

        assertThatThrownBy(() -> service.requireOwnership("missing", "alice"))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    /** 直接查询他人会话返回 403。 */
    @Test
    void otherUserConversationFailsOwnershipCheck() {
        when(conversationMapper.selectByConversationId("c1"))
                .thenReturn(conversation("c1", "bob"));

        assertThatThrownBy(() -> service.requireOwnership("c1", "alice"))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    /** 构造最小会话实体。 */
    private Conversation conversation(String id, String userId) {
        Conversation conversation = new Conversation();
        conversation.setConversationId(id);
        conversation.setUserId(userId);
        return conversation;
    }
}
