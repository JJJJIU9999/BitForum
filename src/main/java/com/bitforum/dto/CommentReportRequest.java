package com.bitforum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

@Data
@Schema(description = "评论举报请求")
public class CommentReportRequest {
    @NotNull(message = "评论ID不能为空")
    @Schema(description = "被举报评论 ID", example = "2001")
    private Long commentId;

    @NotBlank(message = "举报原因不能为空")
    @Size(max = 500, message = "举报原因不能超过500个字符")
    @Schema(description = "举报原因，最多 500 个字符", example = "评论含有攻击性内容")
    private String reason;
}
