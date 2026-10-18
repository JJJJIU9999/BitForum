package com.bitforum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

@Data
@Schema(description = "文章草稿保存请求")
public class ArticleDraftRequest {
    @NotBlank(message = "标题不能为空")
    @Size(max = 50, message = "标题不能超过50个字符")
    @Schema(description = "草稿标题，最多 50 个字符", example = "待完善的文章")
    private String title;

    @NotBlank(message = "内容不能为空")
    @Schema(description = "草稿正文", example = "先保存为草稿的正文内容")
    private String content;

    @Pattern(regexp = "PLAIN_TEXT|MARKDOWN", message = "正文格式只能为 PLAIN_TEXT 或 MARKDOWN")
    @Schema(description = "正文格式；创建时省略为PLAIN_TEXT，更新时省略保持原格式")
    private String contentFormat;

    @Size(max = 255, message = "封面URL不能超过255个字符")
    @Schema(description = "旧版封面 URL 字段；新请求不可使用")
    private String coverUrl;

    @Schema(description = "站内封面资源 ID")
    private Long coverImageId;

    @NotNull(message = "板块不能为空")
    @Schema(description = "所属板块 ID", example = "1")
    private Long categoryId;

    @Pattern(regexp = "ARTICLE|QUESTION", message = "内容类型只能为 ARTICLE 或 QUESTION")
    @Schema(description = "内容类型，省略时为 ARTICLE；创建后不变更")
    private String contentType;
}
