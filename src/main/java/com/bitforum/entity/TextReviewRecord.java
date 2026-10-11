package com.bitforum.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("text_review_record")
public class TextReviewRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String targetType;
    private Long targetId;
    private String contentHash;
    private Long ruleVersion;
    private String matchedRules;
    private String status;
    private String source;
    private String candidateNickname;
    private String candidateBio;
    private Long reviewerId;
    private String reason;
    private LocalDateTime createTime;
    private LocalDateTime reviewedTime;

    @TableField(exist = false)
    private String targetText;
}
