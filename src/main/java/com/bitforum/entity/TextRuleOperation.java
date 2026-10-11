package com.bitforum.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("text_rule_operation")
public class TextRuleOperation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ruleId;
    private Long actorId;
    private String action;
    private String oldPhrase;
    private String newPhrase;
    private Integer oldEnabled;
    private Integer newEnabled;
    private Long ruleVersion;
    private LocalDateTime createTime;
}
