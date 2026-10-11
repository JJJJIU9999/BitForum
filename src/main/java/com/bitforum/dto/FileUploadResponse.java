package com.bitforum.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "文件上传响应")
public class FileUploadResponse {
    @Schema(description = "图片资源 ID，待审期间不可公开访问")
    private Long id;
    @Schema(description = "图片状态，上传后为 PENDING")
    private String status;
    @Schema(description = "图片用途：AVATAR 或 COVER")
    private String kind;

    @Schema(description = "原始文件名", example = "avatar.png")
    private String originalFilename;

    @Schema(description = "文件大小，单位字节", example = "102400")
    private long size;

    @Schema(description = "文件 MIME 类型", example = "image/png")
    private String contentType;
}
