package com.bitforum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

@Data
@Schema(description = "板块创建请求")
public class CategoryCreateRequest {
    @NotBlank(message = "板块名称不能为空")
    @Size(max = 50, message = "板块名称不能超过50个字符")
    @Schema(description = "板块名称，最多 50 个字符", example = "后端开发")
    private String name;

    @Size(max = 255, message = "板块描述不能超过255个字符")
    @Schema(description = "板块描述，最多 255 个字符", example = "讨论 Java、Spring Boot 和数据库")
    private String description;

    @Schema(description = "排序值，数值越小越靠前", example = "10")
    private Integer sortOrder;
}
