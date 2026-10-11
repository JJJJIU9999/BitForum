package com.bitforum.service;

import com.bitforum.exception.BusinessException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.dto.FollowStatsResponse;
import com.bitforum.dto.FollowUserResponse;
import com.bitforum.entity.User;
import com.bitforum.entity.UserFollow;
import com.bitforum.mapper.UserFollowMapper;
import com.bitforum.mapper.UserMapper;

@Service
public class UserFollowService {
    @Autowired
    private UserFollowMapper userFollowMapper;
    @Autowired
    private UserMapper userMapper;

    @Transactional
    public FollowStatsResponse follow(Long followerId, Long followingId) {
        validateTargetUser(followerId, followingId);
        if (isFollowing(followerId, followingId)) {
            throw new BusinessException("不能重复关注同一用户");
        }

        UserFollow follow = new UserFollow();
        follow.setFollowerId(followerId);
        follow.setFollowingId(followingId);
        try {
            userFollowMapper.insert(follow);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("不能重复关注同一用户");
        }
        return getStats(followingId, followerId);
    }

    @Transactional
    public FollowStatsResponse unfollow(Long followerId, Long followingId) {
        validateTargetUser(followerId, followingId);
        int deleted = userFollowMapper.delete(new QueryWrapper<UserFollow>()
                .eq("follower_id", followerId)
                .eq("following_id", followingId));
        if (deleted == 0) {
            throw new BusinessException("尚未关注该用户");
        }
        return getStats(followingId, followerId);
    }

    public Page<FollowUserResponse> pageFollowers(Long userId, long pageNum, long pageSize) {
        requireUser(userId);
        return userFollowMapper.selectFollowers(new Page<>(pageNum, pageSize), userId);
    }

    public Page<FollowUserResponse> pageFollowing(Long userId, long pageNum, long pageSize) {
        requireUser(userId);
        return userFollowMapper.selectFollowing(new Page<>(pageNum, pageSize), userId);
    }

    public FollowStatsResponse getStats(Long userId, Long currentUserId) {
        requireUser(userId);
        FollowStatsResponse response = new FollowStatsResponse();
        response.setUserId(userId);
        response.setFollowingCount(countFollowing(userId));
        response.setFollowerCount(countFollowers(userId));
        response.setFollowedByCurrentUser(isFollowing(currentUserId, userId));
        return response;
    }

    public Long countFollowing(Long userId) {
        return userFollowMapper.selectCount(new QueryWrapper<UserFollow>()
                .eq("follower_id", userId));
    }

    public Long countFollowers(Long userId) {
        return userFollowMapper.selectCount(new QueryWrapper<UserFollow>()
                .eq("following_id", userId));
    }

    public boolean isFollowing(Long followerId, Long followingId) {
        if (followerId == null || followingId == null || followerId.equals(followingId)) {
            return false;
        }
        Long count = userFollowMapper.selectCount(new QueryWrapper<UserFollow>()
                .eq("follower_id", followerId)
                .eq("following_id", followingId));
        return count > 0;
    }

    private void validateTargetUser(Long followerId, Long followingId) {
        if (followerId.equals(followingId)) {
            throw new BusinessException("不能关注自己");
        }
        requireUser(followingId);
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return user;
    }
}

