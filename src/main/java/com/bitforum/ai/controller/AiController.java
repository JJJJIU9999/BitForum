package com.bitforum.ai.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.bitforum.ai.dto.AiChatRequest;
import com.bitforum.ai.dto.AiConversationCreateRequest;
import com.bitforum.ai.dto.AiConversationResponse;
import com.bitforum.ai.dto.AiMessageResponse;
import com.bitforum.ai.orchestrator.AgentOrchestrator;
import com.bitforum.ai.recommend.RecommendService;
import com.bitforum.ai.recommend.RecommendService.RecommendRequest;
import com.bitforum.ai.recommend.RecommendService.RecommendResult;
import com.bitforum.ai.recommend.RecommendService.RecommendedArticle;
import com.bitforum.ai.service.AiConversationService;
import com.bitforum.common.Result;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * AI 助手接口（M13）。
 *
 * 全部接口都要求登录：由 WebMvcConfig 中的 LoginInterceptor 拦截 /api/ai/**，
 * 因此这里可以直接通过 @RequestAttribute("userId") 拿到当前登录用户。
 */
@RestController
@Tag(name = "AI 助手", description = "AI 会话管理与多轮对话")
public class AiController {

    /** 相关帖一次最多返回多少条，防止被当成列表接口使用。 */
    private static final int MAX_RECOMMEND_LIMIT = 10;

    private final AiConversationService conversationService;
    private final AgentOrchestrator orchestrator;
    private final RecommendService recommendService;

    public AiController(AiConversationService conversationService, AgentOrchestrator orchestrator,
                        RecommendService recommendService) {
        this.conversationService = conversationService;
        this.orchestrator = orchestrator;
        this.recommendService = recommendService;
    }

    /**
     * 根据用户刚问的问题推荐站内相关帖子（M17）。
     *
     * <p>与 M15 的"参考来源"互补：参考来源是**回答用到了**哪些文章，
     * 这里推荐的是"你可能还想看"的相关帖子 —— 所以它**不排除**回答里引用过的文章，
     * 两者回答的是不同问题。
     */
    @GetMapping("/api/ai/recommendations")
    @Operation(summary = "根据提问推荐相关帖子",
            description = "用提问文本做内容相似召回，叠加热度与关注信号后融合排序；AI 不可用时列表照常返回")
    public Result<List<RecommendedArticle>> recommendations(
            @RequestAttribute("userId") Long userId,
            @Parameter(description = "用户刚问的问题") @org.springframework.web.bind.annotation.RequestParam String query,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "5") Integer limit) {
        int topN = limit == null || limit < 1 ? 5 : Math.min(limit, MAX_RECOMMEND_LIMIT);
        try {
            RecommendResult result = recommendService.recommend(
                    RecommendRequest.forQuery(query, userId, topN));
            return Result.ok("相关帖子推荐成功", result.articles());
        } catch (RuntimeException e) {
            // 推荐是增强能力：失败时前端只是不展示这一块，不该影响对话本身
            return Result.ok("暂无相关推荐", List.of());
        }
    }

    @PostMapping("/api/ai/conversations")
    @Operation(summary = "新建 AI 会话", description = "为当前登录用户创建一个新的 AI 对话会话",
            security = @SecurityRequirement(name = "bearerAuth"))
    public Result<AiConversationResponse> createConversation(
            @Valid @RequestBody(required = false) AiConversationCreateRequest request,
            @RequestAttribute("userId") Long userId) {
        String title = request == null ? null : request.getTitle();
        return Result.ok("会话创建成功", conversationService.createConversation(userId, title));
    }

    @GetMapping("/api/ai/conversations")
    @Operation(summary = "查询我的 AI 会话列表", description = "按最后更新时间倒序返回当前登录用户的全部会话",
            security = @SecurityRequirement(name = "bearerAuth"))
    public Result<List<AiConversationResponse>> listConversations(
            @RequestAttribute("userId") Long userId) {
        return Result.ok("会话列表查询成功", conversationService.listConversations(userId));
    }

    @PostMapping("/api/ai/conversations/{conversationId}/messages")
    @Operation(summary = "发送消息给 AI 助手",
            description = "保存用户提问、调用大模型生成回答并返回。大模型不可用时返回降级回答，不影响论坛其他功能",
            security = @SecurityRequirement(name = "bearerAuth"))
    public Result<AiMessageResponse> sendMessage(
            @Parameter(description = "会话 ID") @PathVariable Long conversationId,
            @Valid @RequestBody AiChatRequest request,
            @RequestAttribute("userId") Long userId) {
        // M4：这里**不再**捕获一般 RuntimeException 并返回 Result.fail(e.getMessage())。
        // 旧写法有两处问题：把未知服务异常降级成 HTTP 400（Result.fail 默认 code=400，
        // 会被 ResultHttpStatusAdvice 映射成 400），并把内部异常消息原样回显给客户端。
        // 现在未知异常交给 GlobalExceptionHandler → HTTP 500 + 固定安全文案；
        // ResponseStatusException（会话不存在 404 / 越权 403）由同一个处理器按原语义映射，
        // 因此这里连 catch 都不需要保留。
        return Result.ok("AI 回答生成成功",
                orchestrator.chat(conversationId, userId, request.getContent()));
    }

    @GetMapping("/api/ai/conversations/{conversationId}/messages")
    @Operation(summary = "查询会话消息历史", description = "按时间正序返回指定会话的全部消息",
            security = @SecurityRequirement(name = "bearerAuth"))
    public Result<List<AiMessageResponse>> listMessages(
            @Parameter(description = "会话 ID") @PathVariable Long conversationId,
            @RequestAttribute("userId") Long userId) {
        return Result.ok("消息历史查询成功",
                conversationService.listMessages(conversationId, userId));
    }
}
