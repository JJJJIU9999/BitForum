package com.bitforum.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("article_audit_record")
public class ArticleAuditRecord {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long articleId;
    private Long auditorId;
    private String auditStatus;
    private String reason;
    private LocalDateTime createTime;
}
