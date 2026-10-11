package com.bitforum.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data                               // ← 替代所有 getter/setter/toString
@TableName("user_info")             // ← 告诉 MyBatis-Plus 对应哪张表
public class User {
    @TableId(type = IdType.AUTO)    // ← 告诉 MyBatis-Plus id 是自增主键
    private Long id;
    private String username;
    private String password;
    private String avatar;
    private Long pendingAvatarImageId;
    private String nickname;
    private String bio;
    private String publicProfileStatus;
    private String role;
    private Integer status;
    private LocalDateTime createTime;
}
