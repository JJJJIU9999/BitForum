package com.bitforum.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("user_follow")
public class UserFollow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long followerId;
    private Long followingId;
    @TableField("created_at")
    private LocalDateTime createdAt;
}

