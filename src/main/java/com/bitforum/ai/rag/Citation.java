package com.bitforum.ai.rag;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 引用来源（M15）。
 *
 * <p>指向一段站内已发布文章：{@code articleId} 用于生成可点击链接，
 * {@code title} 用于展示。引用是**文章级**的，同一篇文章的多个片段只产生一个引用。
 */
@Schema(description = "RAG 引用来源")
public record Citation(
        @Schema(description = "文章 ID") Long articleId,
        @Schema(description = "文章标题") String title) {
}
