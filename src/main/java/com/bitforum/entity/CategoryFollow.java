package com.bitforum.entity;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("category_follow")
public class CategoryFollow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long categoryId;
    private LocalDateTime createdAt;
}
