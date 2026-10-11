package com.bitforum.ai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.bitforum.ai.entity.AiConversation;
import com.bitforum.ai.mapper.AiConversationMapper;

/**
 * MysqlChatMemoryRepository 的持久化契约测试（M13）。
 *
 * 覆盖 M13 验收标准中的"多轮对话历史可持久化、重启后不丢"：
 * 写入后重新查询，验证消息内容、顺序与角色类型都能正确还原。
 *
 * 测试使用 @Transactional，数据在方法结束后回滚，不会污染本地库。
 */
@SpringBootTest
@Transactional
class MysqlChatMemoryRepositoryTest {

    @Autowired
    private MysqlChatMemoryRepository chatMemoryRepository;
    @Autowired
    private AiConversationMapper aiConversationMapper;

    @Test
    void shouldPersistAndRestoreConversationInOrder() {
        Long conversationId = createConversation();
        String conversationKey = String.valueOf(conversationId);

        chatMemoryRepository.saveAll(conversationKey, List.of(
                new UserMessage("BitForum 里怎么实现文章审核？"),
                new AssistantMessage("文章提交后会进入 PENDING 状态，由管理员审核通过后发布。"),
                new UserMessage("那审核失败会怎样？")));

        List<Message> restored = chatMemoryRepository.findByConversationId(conversationKey);

        assertEquals(3, restored.size(), "三条消息都应被还原");
        assertEquals("BitForum 里怎么实现文章审核？", restored.get(0).getText());
        assertEquals("文章提交后会进入 PENDING 状态，由管理员审核通过后发布。", restored.get(1).getText());
        assertEquals("那审核失败会怎样？", restored.get(2).getText());

        // 角色类型必须正确还原，否则模型无法区分对话双方
        assertInstanceOf(UserMessage.class, restored.get(0));
        assertInstanceOf(AssistantMessage.class, restored.get(1));
        assertInstanceOf(UserMessage.class, restored.get(2));
    }

    @Test
    void shouldRestoreToolCallsForAssistantMessage() {
        Long conversationId = createConversation();
        String conversationKey = String.valueOf(conversationId);

        AssistantMessage withToolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "searchArticles", "{\"keyword\":\"Redis\"}")))
                .build();
        chatMemoryRepository.saveAll(conversationKey, List.of(withToolCall));

        List<Message> restored = chatMemoryRepository.findByConversationId(conversationKey);

        assertEquals(1, restored.size());
        AssistantMessage assistant = assertInstanceOf(AssistantMessage.class, restored.get(0));
        assertEquals(1, assistant.getToolCalls().size(), "工具调用应被完整还原（M14 依赖此能力）");
        assertEquals("searchArticles", assistant.getToolCalls().get(0).name());
        assertEquals("call-1", assistant.getToolCalls().get(0).id());
    }

    @Test
    void shouldReturnEmptyForUnknownConversation() {
        assertTrue(chatMemoryRepository.findByConversationId("999999999").isEmpty(),
                "不存在的会话应返回空列表，而不是抛异常");
        assertTrue(chatMemoryRepository.findByConversationId("not-a-number").isEmpty(),
                "非法 conversationId 应安全返回空列表");
        assertTrue(chatMemoryRepository.findByConversationId(null).isEmpty(),
                "null conversationId 应安全返回空列表");
    }

    @Test
    void deleteShouldRemoveOnlyTargetConversation() {
        Long target = createConversation();
        Long other = createConversation();
        String targetKey = String.valueOf(target);
        String otherKey = String.valueOf(other);

        chatMemoryRepository.saveAll(targetKey, List.of(new UserMessage("要被删除的会话")));
        chatMemoryRepository.saveAll(otherKey, List.of(new UserMessage("应当保留的会话")));

        chatMemoryRepository.deleteByConversationId(targetKey);

        assertTrue(chatMemoryRepository.findByConversationId(targetKey).isEmpty(), "目标会话应被清空");
        assertEquals(1, chatMemoryRepository.findByConversationId(otherKey).size(),
                "其他会话不应受影响");
    }

    private Long createConversation() {
        AiConversation conversation = new AiConversation();
        conversation.setUserId(81101L);
        conversation.setTitle("M13 会话记忆测试");
        conversation.setAgentType("QA");
        conversation.setMessageCount(0);
        conversation.setTotalTokens(0);
        aiConversationMapper.insert(conversation);
        assertNotNull(conversation.getId(), "会话应获得自增主键");
        return conversation.getId();
    }
}
