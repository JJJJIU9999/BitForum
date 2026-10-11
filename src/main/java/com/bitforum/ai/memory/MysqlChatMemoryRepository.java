package com.bitforum.ai.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.ai.entity.AiMessage;
import com.bitforum.ai.mapper.AiMessageMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 基于 MySQL 的会话记忆仓储（M13）。
 *
 * 为什么不使用 Spring AI 默认实现：
 * 默认的 InMemoryChatMemoryRepository 在应用重启后会话记忆全部丢失，也无法审计。
 * 实现 ChatMemoryRepository 接口后，Spring AI 的自动配置会优先使用本实现。
 *
 * 消息映射策略（不依赖 Jackson 多态反序列化，因此对 Message 实现类的结构变化不敏感）：
 * - 序列化：role 存类型标识，content 存文本，tool_calls 单独存 JSON；
 * - 反序列化：按 role 用对应 Builder 重建对象，工具调用用 TypeReference 还原。
 */
@Repository
public class MysqlChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(MysqlChatMemoryRepository.class);

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_SYSTEM = "system";

    /** 单次查询历史上限，防止超长会话把全部消息读进内存 */
    private static final int MAX_HISTORY_SIZE = 200;

    private final AiMessageMapper aiMessageMapper;
    private final ObjectMapper objectMapper;

    public MysqlChatMemoryRepository(AiMessageMapper aiMessageMapper, ObjectMapper objectMapper) {
        this.aiMessageMapper = aiMessageMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> findConversationIds() {
        // 查询全部会话 id 成本高且无业务用途，这里只返回最近有消息的会话。
        List<AiMessage> recent = aiMessageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
                .select(AiMessage::getConversationId)
                .orderByDesc(AiMessage::getId)
                .last("LIMIT 100"));
        return recent.stream()
                .map(AiMessage::getConversationId)
                .distinct()
                .map(String::valueOf)
                .collect(Collectors.toList());
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        Long id = parseConversationId(conversationId);
        if (id == null) {
            return new ArrayList<>();
        }

        List<AiMessage> records = aiMessageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, id)
                .orderByAsc(AiMessage::getId));

        List<Message> messages = new ArrayList<>(records.size());
        for (AiMessage record : records) {
            Message message = toMessage(record);
            if (message != null) {
                messages.add(message);
            }
        }

        // 超出窗口时只保留最近的消息，避免请求体随会话增长无限膨胀
        if (messages.size() > MAX_HISTORY_SIZE) {
            return new ArrayList<>(messages.subList(messages.size() - MAX_HISTORY_SIZE, messages.size()));
        }
        return messages;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Long id = parseConversationId(conversationId);
        if (id == null || messages == null || messages.isEmpty()) {
            return;
        }

        for (Message message : messages) {
            AiMessage record = toRecord(id, message);
            if (record != null) {
                aiMessageMapper.insert(record);
            }
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        Long id = parseConversationId(conversationId);
        if (id == null) {
            return;
        }
        aiMessageMapper.delete(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, id));
    }

    // ==================== 转换逻辑 ====================

    private AiMessage toRecord(Long conversationId, Message message) {
        if (message == null || message.getMessageType() == null) {
            return null;
        }

        AiMessage record = new AiMessage();
        record.setConversationId(conversationId);
        record.setContent(message.getText() == null ? "" : message.getText());

        switch (message.getMessageType()) {
            case USER:
                record.setRole(ROLE_USER);
                break;
            case ASSISTANT:
                record.setRole(ROLE_ASSISTANT);
                if (message instanceof AssistantMessage assistantMessage
                        && assistantMessage.getToolCalls() != null
                        && !assistantMessage.getToolCalls().isEmpty()) {
                    record.setToolCalls(writeJson(assistantMessage.getToolCalls()));
                }
                break;
            case SYSTEM:
                record.setRole(ROLE_SYSTEM);
                break;
            default:
                // M13 不产生 TOOL 类型消息；未知类型跳过，避免写入脏数据
                log.debug("跳过不支持持久化的消息类型：{}", message.getMessageType());
                return null;
        }
        return record;
    }

    private Message toMessage(AiMessage record) {
        String role = record.getRole();
        String content = record.getContent() == null ? "" : record.getContent();
        if (role == null) {
            return null;
        }

        return switch (role) {
            case ROLE_USER -> new UserMessage(content);
            case ROLE_SYSTEM -> new SystemMessage(content);
            case ROLE_ASSISTANT -> AssistantMessage.builder()
                    .content(content)
                    .toolCalls(readToolCalls(record.getToolCalls()))
                    .build();
            default -> {
                log.debug("跳过无法还原角色的消息：role={}", role);
                yield null;
            }
        };
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("工具调用序列化失败，将不保存该字段", e);
            return null;
        }
    }

    private List<AssistantMessage.ToolCall> readToolCalls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AssistantMessage.ToolCall>>() {
            });
        } catch (Exception e) {
            log.warn("工具调用反序列化失败，按无工具调用处理", e);
            return List.of();
        }
    }

    private Long parseConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(conversationId.trim());
        } catch (NumberFormatException e) {
            log.warn("非法的 conversationId：{}", conversationId);
            return null;
        }
    }
}
