package com.bitforum.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("content_report")
public class ContentReport {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long reporterId;
    private String targetType;
    private Long targetId;
    private Long targetOwnerId;
    private String reason;
    private String status;
    private Long handlerId;
    private String handleResult;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime handleTime;
}

