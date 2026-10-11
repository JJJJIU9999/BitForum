package com.bitforum.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("image_resource")
public class ImageResource {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ownerId;
    private String kind;
    private String status;
    private String storageKey;
    private String legacyUrl;
    private Long targetId;
    private String originalFilename;
    private String contentType;
    private Long size;
    private Long reviewerId;
    private String reviewReason;
    private LocalDateTime createTime;
    private LocalDateTime reviewedTime;
}
