package com.bitforum.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("text_rule")
public class TextRule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String phrase;
    private String normalizedPhrase;
    private Integer enabled;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
