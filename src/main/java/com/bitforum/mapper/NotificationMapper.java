package com.bitforum.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bitforum.entity.Notification;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}

