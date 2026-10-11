package com.bitforum.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("notification")
public class Notification {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long receiverId;
    private Long senderId;
    private String type;
    private String title;
    private String content;
    private Long articleId;
    private String sourceMessageId;
    private Integer readStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

