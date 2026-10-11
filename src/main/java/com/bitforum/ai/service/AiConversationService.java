package com.bitforum.ai.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.ai.agent.AgentType;
import com.bitforum.ai.dto.AiConversationResponse;
import com.bitforum.ai.dto.AiMessageResponse;
import com.bitforum.ai.entity.AiConversation;
import com.bitforum.ai.entity.AiKbDocument;
import com.bitforum.ai.entity.AiMessage;
import com.bitforum.ai.mapper.AiConversationMapper;
import com.bitforum.ai.mapper.AiKbDocumentMapper;
import com.bitforum.ai.mapper.AiMessageMapper;
import com.bitforum.ai.rag.Citation;

/**
 * AI 会话与消息的业务服务（M13）。
 *
 * 职责边界：
 * - 本类只负责会话与消息的读写、归属校验和标题生成；
 * - 调用大模型的逻辑放在 Agent 与 AgentOrchestrator 中，保持职责单一。
 */
@Service
public class AiConversationService {

    /** 会话标题最大长度，与 ai_conversation.title 的 VARCHAR(100) 保持一致 */
    private static final int MAX_TITLE_LENGTH = 100;

    private final AiConversationMapper aiConversationMapper;
    private final AiMessageMapper aiMessageMapper;
    private final AiKbDocumentMapper aiKbDocumentMapper;

    public AiConversationService(AiConversationMapper aiConversationMapper,
                                 AiMessageMapper aiMessageMapper,
                                 AiKbDocumentMapper aiKbDocumentMapper) {
        this.aiConversationMapper = aiConversationMapper;
        this.aiMessageMapper = aiMessageMapper;
        this.aiKbDocumentMapper = aiKbDocumentMapper;
    }

    /**
     * 新建会话。
     *
     * @param userId 会话归属用户
     * @param title  会话标题，为空时使用默认标题
     */
    @Transactional
    public AiConversationResponse createConversation(Long userId, String title) {
        AiConversation conversation = new AiConversation();
        conversation.setUserId(userId);
        conversation.setTitle(normalizeTitle(title));
        conversation.setAgentType(AgentType.QA.name());
        conversation.setMessageCount(0);
        conversation.setTotalTokens(0);
        aiConversationMapper.insert(conversation);
        return toResponse(conversation);
    }

    /** 查询当前用户的会话列表，按更新时间倒序。 */
    public List<AiConversationResponse> listConversations(Long userId) {
        List<AiConversation> conversations = aiConversationMapper.selectList(
                new LambdaQueryWrapper<AiConversation>()
                        .eq(AiConversation::getUserId, userId)
                        .orderByDesc(AiConversation::getUpdateTime)
                        .orderByDesc(AiConversation::getId));
        return conversations.stream().map(this::toResponse).toList();
    }

    /**
     * 查询会话详情并校验归属，防止越权读取他人会话。
     *
     * @throws ResponseStatusException 会话不存在（404）或不属于该用户（403）
     */
    public AiConversation getOwnedConversation(Long conversationId, Long userId) {
        AiConversation conversation = aiConversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        if (!conversation.getUserId().equals(userId)) {
            // 与项目既有权限约定一致：越权访问返回 403
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该会话");
        }
        return conversation;
    }

    /** 查询会话内的全部消息，按创建顺序正序。 */
    public List<AiMessageResponse> listMessages(Long conversationId, Long userId) {
        getOwnedConversation(conversationId, userId);
        List<AiMessage> messages = aiMessageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, conversationId)
                .orderByAsc(AiMessage::getId));
        // 引用标题一次性批量查出，避免按消息逐条查询造成 N+1
        Map<Long, String> titlesByArticleId = loadCitationTitles(messages);
        return messages.stream().map(message -> toResponse(message, titlesByArticleId)).toList();
    }

    /** 保存一条消息，并同步维护会话的消息数与更新时间。 */
    @Transactional
    public AiMessage saveMessage(Long conversationId, String role, String content,
                                 Integer promptTokens, Integer completionTokens,
                                 Integer totalTokens, Integer latencyMs) {
        return saveMessage(conversationId, role, content, promptTokens, completionTokens,
                totalTokens, latencyMs, null);
    }

    /**
     * 保存一条消息，并记录该轮回答的引用来源（M15）。
     *
     * @param retrievedDocIds 引用的文章 id，逗号分隔；无引用时传 null
     */
    @Transactional
    public AiMessage saveMessage(Long conversationId, String role, String content,
                                 Integer promptTokens, Integer completionTokens,
                                 Integer totalTokens, Integer latencyMs, String retrievedDocIds) {
        AiMessage message = new AiMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setRetrievedDocIds(retrievedDocIds);
        message.setPromptTokens(promptTokens);
        message.setCompletionTokens(completionTokens);
        message.setTotalTokens(totalTokens);
        message.setLatencyMs(latencyMs);
        aiMessageMapper.insert(message);
        return message;
    }

    /**
     * 会话产生新消息后更新统计字段（S5 起改为数据库原子自增）。
     *
     * <p>旧实现是"selectById → 在 Java 里相加 → updateById"：两个并发请求会读到同一个旧值，
     * 后写的那次把先写的增量覆盖掉（丢失更新）。现在把加法下推到 SQL
     * （{@code message_count = message_count + ?}），由数据库保证原子性，
     * 不引入分布式锁或新的重试框架。
     *
     * <p>会话不存在时 UPDATE 影响 0 行，与旧实现"查不到就直接返回"的语义一致，
     * 因此也省掉了一次存在性查询。
     */
    @Transactional
    public void updateConversationStats(Long conversationId, int addedMessages, int addedTokens) {
        aiConversationMapper.increaseStats(conversationId, addedMessages, addedTokens);
    }

    /**
     * 用首条用户消息生成会话标题。
     * 只在会话标题仍为默认值时调用，避免覆盖用户自定义标题。
     */
    @Transactional
    public void renameIfDefault(Long conversationId, String firstUserMessage) {
        AiConversation conversation = aiConversationMapper.selectById(conversationId);
        if (conversation == null || firstUserMessage == null || firstUserMessage.isBlank()) {
            return;
        }
        if (!"新对话".equals(conversation.getTitle())) {
            return;
        }
        conversation.setTitle(truncate(firstUserMessage.trim()));
        aiConversationMapper.updateById(conversation);
    }

    // ==================== 内部方法 ====================

    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "新对话";
        }
        return truncate(title.trim());
    }

    private String truncate(String text) {
        if (text.length() <= MAX_TITLE_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TITLE_LENGTH);
    }

    private AiConversationResponse toResponse(AiConversation conversation) {
        AiConversationResponse response = new AiConversationResponse();
        response.setId(conversation.getId());
        response.setTitle(conversation.getTitle());
        response.setAgentType(conversation.getAgentType());
        response.setMessageCount(conversation.getMessageCount());
        response.setTotalTokens(conversation.getTotalTokens());
        response.setCreateTime(conversation.getCreateTime());
        response.setUpdateTime(conversation.getUpdateTime());
        return response;
    }

    private AiMessageResponse toResponse(AiMessage message, Map<Long, String> titlesByArticleId) {
        AiMessageResponse response = new AiMessageResponse();
        response.setId(message.getId());
        response.setRole(message.getRole());
        response.setContent(message.getContent());
        response.setTotalTokens(message.getTotalTokens());
        response.setLatencyMs(message.getLatencyMs());
        response.setCreateTime(message.getCreateTime());
        response.setCitations(parseCitations(message.getRetrievedDocIds(), titlesByArticleId));
        return response;
    }

    /** 收集本批消息引用到的全部文章 id，一次性查出标题。 */
    private Map<Long, String> loadCitationTitles(List<AiMessage> messages) {
        Set<Long> articleIds = messages.stream()
                .map(AiMessage::getRetrievedDocIds)
                .filter(StringUtils::hasText)
                .flatMap(ids -> Arrays.stream(ids.split(",")))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(this::parseArticleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (articleIds.isEmpty()) {
            return Map.of();
        }
        return aiKbDocumentMapper
                .selectList(new LambdaQueryWrapper<AiKbDocument>().in(AiKbDocument::getArticleId, articleIds))
                .stream()
                .collect(Collectors.toMap(AiKbDocument::getArticleId, AiKbDocument::getTitle, (left, right) -> left));
    }

    /**
     * 把消息里保存的引用 id 还原成引用列表。
     *
     * <p>标题查不到时（文章已被下架并移出知识库）退化为「文章 &lt;id&gt;」，
     * 仍保留链接，便于用户自行确认。
     */
    private List<Citation> parseCitations(String retrievedDocIds, Map<Long, String> titlesByArticleId) {
        if (!StringUtils.hasText(retrievedDocIds)) {
            return List.of();
        }
        List<Citation> citations = new ArrayList<>();
        for (String part : retrievedDocIds.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Long articleId = parseArticleId(trimmed);
            if (articleId == null) {
                continue;
            }
            citations.add(new Citation(articleId, titlesByArticleId.getOrDefault(articleId, "文章 " + articleId)));
        }
        return citations;
    }

    private Long parseArticleId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
