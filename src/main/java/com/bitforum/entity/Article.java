package com.bitforum.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@TableName("article")
public class Article {
    @TableId(type = IdType.AUTO)
    private Long id;

    @NotBlank(message = "标题不能为空")
    @Size(max = 50, message = "标题不能超过50个字符")
    private String title;

    @NotBlank(message = "内容不能为空")
    private String content;

    private String contentFormat;

    private String coverUrl;
    private Long coverImageId;
    private Long userId;
    private Long categoryId;
    private String status;
    private String contentType;
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private Long acceptedAnswerId;
    private int viewCount;
    private int likeCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private String categoryName;

    @TableField(exist = false)
    private boolean categoryAvailable;

    @TableField(exist = false)
    private com.bitforum.dto.PublicAuthorSummary author;

    @TableField(exist = false)
    private Long answerCount;

    @TableField(exist = false)
    private Long favoriteCount;

    // 下面两个字段只在详情接口里回填，用于让前端把点赞/收藏渲染成状态切换按钮。
    // 用原始 boolean 而不是 Boolean：匿名访问时也要稳定输出 false，不能让前端拿到 null 去猜。
    @TableField(exist = false)
    private boolean likedByCurrentUser;

    @TableField(exist = false)
    private boolean favoritedByCurrentUser;
}
