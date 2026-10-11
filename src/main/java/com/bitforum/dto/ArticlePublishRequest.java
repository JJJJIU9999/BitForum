package com.bitforum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

@Data
@Schema(description = "文章提交审核请求")
public class ArticlePublishRequest {
    @NotBlank(message = "标题不能为空")
    @Size(max = 50, message = "标题不能超过50个字符")
    @Schema(description = "文章标题，最多 50 个字符", example = "毕业设计论坛系统说明")
    private String title;

    @NotBlank(message = "内容不能为空")
    @Schema(description = "文章正文", example = "这里是文章正文内容")
    private String content;

    @Size(max = 255, message = "封面URL不能超过255个字符")
    @Schema(description = "旧版封面 URL 字段；新请求不可使用")
    private String coverUrl;

    @Schema(description = "站内封面资源 ID；待审封面会阻止文章审核通过")
    private Long coverImageId;

    @NotNull(message = "板块不能为空")
    @Schema(description = "所属板块 ID", example = "1")
    private Long categoryId;

    @Pattern(regexp = "ARTICLE|QUESTION", message = "内容类型只能为 ARTICLE 或 QUESTION")
    @Schema(description = "内容类型，省略时为 ARTICLE；创建后不变更")
    private String contentType;
}
