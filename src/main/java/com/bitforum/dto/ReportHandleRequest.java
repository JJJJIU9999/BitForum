package com.bitforum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

@Data
@Schema(description = "举报处理请求")
public class ReportHandleRequest {
    @NotNull(message = "举报ID不能为空")
    @Schema(description = "举报记录 ID", example = "3001")
    private Long reportId;

    @NotBlank(message = "处理说明不能为空")
    @Size(max = 500, message = "处理说明不能超过500个字符")
    @Schema(description = "处理说明，最多 500 个字符", example = "已核实并记录处理结果")
    private String handleResult;
}
