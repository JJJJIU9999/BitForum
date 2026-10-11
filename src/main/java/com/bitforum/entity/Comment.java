package com.bitforum.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;

import lombok.Data;

@Data
@TableName("comment")
public class Comment {
    @TableId(type = IdType.AUTO)        //自增主键是id    
    private Long id;
    private String content;
    private String status;
    private Long userId;
    private Long articleId;
    private Long parentCommentId;       //回复给谁
    private LocalDateTime createTime;

    @TableField(exist = false)
    private long likeCount;
    @TableField(exist = false)
    private boolean likedByCurrentUser;

    @TableField(exist = false)
    private com.bitforum.dto.PublicAuthorSummary author;
}
