package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.dto.FollowStatsResponse;
import com.bitforum.dto.FollowUserResponse;
import com.bitforum.entity.User;
import com.bitforum.entity.UserFollow;
import com.bitforum.mapper.UserFollowMapper;
import com.bitforum.mapper.UserMapper;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class UserFollowServiceTest {
    @Mock
    private UserFollowMapper userFollowMapper;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private UserFollowService userFollowService;

    @Test
    void followShouldRejectSelf() {
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            userFollowService.follow(1L, 1L);
        });

        assertEquals("不能关注自己", exception.getMessage());
        verify(userFollowMapper, never()).insert(any(UserFollow.class));
    }

    @Test
    void followShouldRejectDuplicate() {
        when(userMapper.selectById(2L)).thenReturn(new User());
        when(userFollowMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            userFollowService.follow(1L, 2L);
        });

        assertEquals("不能重复关注同一用户", exception.getMessage());
        verify(userFollowMapper, never()).insert(any(UserFollow.class));
    }

    @Test
    void followShouldCreateRelationAndReturnStats() {
        when(userMapper.selectById(2L)).thenReturn(new User(), new User());
        when(userFollowMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L, 3L, 4L, 1L);

        FollowStatsResponse response = userFollowService.follow(1L, 2L);

        assertEquals(2L, response.getUserId());
        assertEquals(3L, response.getFollowingCount());
        assertEquals(4L, response.getFollowerCount());
        assertTrue(response.getFollowedByCurrentUser());
        verify(userFollowMapper).insert(any(UserFollow.class));
    }

    @Test
    void unfollowShouldDeleteOwnRelation() {
        when(userMapper.selectById(2L)).thenReturn(new User(), new User());
        when(userFollowMapper.delete(any(QueryWrapper.class))).thenReturn(1);
        when(userFollowMapper.selectCount(any(QueryWrapper.class))).thenReturn(3L, 4L, 0L);

        FollowStatsResponse response = userFollowService.unfollow(1L, 2L);

        assertEquals(2L, response.getUserId());
        assertEquals(3L, response.getFollowingCount());
        assertEquals(4L, response.getFollowerCount());
        assertFalse(response.getFollowedByCurrentUser());
    }

    @Test
    void unfollowShouldFailWhenRelationMissing() {
        when(userMapper.selectById(2L)).thenReturn(new User());
        when(userFollowMapper.delete(any(QueryWrapper.class))).thenReturn(0);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            userFollowService.unfollow(1L, 2L);
        });

        assertEquals("尚未关注该用户", exception.getMessage());
    }

    @Test
    void getStatsShouldCountFollowingFollowersAndCurrentState() {
        when(userMapper.selectById(2L)).thenReturn(new User());
        when(userFollowMapper.selectCount(any(QueryWrapper.class))).thenReturn(5L, 6L, 1L);

        FollowStatsResponse response = userFollowService.getStats(2L, 1L);

        assertEquals(5L, response.getFollowingCount());
        assertEquals(6L, response.getFollowerCount());
        assertTrue(response.getFollowedByCurrentUser());
    }

    @Test
    void pageFollowersShouldRequireExistingUser() {
        when(userMapper.selectById(2L)).thenReturn(new User());
        Page<FollowUserResponse> page = new Page<>(1, 10, 1);
        when(userFollowMapper.selectFollowers(any(Page.class), any(Long.class))).thenReturn(page);

        Page<FollowUserResponse> result = userFollowService.pageFollowers(2L, 1, 10);

        assertEquals(page, result);
    }

    @Test
    void pageFollowingShouldRequireExistingUser() {
        when(userMapper.selectById(2L)).thenReturn(new User());
        Page<FollowUserResponse> page = new Page<>(1, 10, 1);
        when(userFollowMapper.selectFollowing(any(Page.class), any(Long.class))).thenReturn(page);

        Page<FollowUserResponse> result = userFollowService.pageFollowing(2L, 1, 10);

        assertEquals(page, result);
    }
}

