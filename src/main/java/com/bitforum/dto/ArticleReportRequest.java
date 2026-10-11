package com.bitforum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

@Data
@Schema(description = "文章举报请求")
public class ArticleReportRequest {
    @NotNull(message = "文章ID不能为空")
    @Schema(description = "被举报文章 ID", example = "1001")
    private Long articleId;

    @NotBlank(message = "举报原因不能为空")
    @Size(max = 500, message = "举报原因不能超过500个字符")
    @Schema(description = "举报原因，最多 500 个字符", example = "内容含有违规信息")
    private String reason;
}
