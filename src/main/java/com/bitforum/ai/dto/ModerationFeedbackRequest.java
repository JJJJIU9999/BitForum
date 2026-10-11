package com.bitforum.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * AI 审核记录的人工反馈请求（M16）。
 *
 * <p>管理员在审核台标记 AI 这次判断是否正确；该数据用于评估线上表现，
 * 也是后续调整提示词与阈值的依据。
 */
@Data
@Schema(description = "AI 审核人工反馈请求")
public class ModerationFeedbackRequest {

    @NotBlank(message = "反馈取值不能为空")
    @Schema(description = "CORRECT（AI 判断正确）/ WRONG（AI 判断错误）", example = "CORRECT")
    private String feedback;
}
