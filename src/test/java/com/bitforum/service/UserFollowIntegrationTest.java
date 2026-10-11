package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.dto.FollowStatsResponse;
import com.bitforum.dto.FollowUserResponse;
import com.bitforum.entity.User;
import com.bitforum.mapper.UserMapper;

@SpringBootTest
@Transactional
class UserFollowIntegrationTest {
    @Autowired
    private UserFollowService userFollowService;
    @Autowired
    private UserMapper userMapper;

    @Test
    void shouldPageFollowersAndFollowingWithRealMapperSql() {
        User target = createUser("target");
        User followerA = createUser("follower-a");
        User followerB = createUser("follower-b");
        User following = createUser("following");

        userFollowService.follow(followerA.getId(), target.getId());
        userFollowService.follow(followerB.getId(), target.getId());
        userFollowService.follow(target.getId(), following.getId());

        FollowStatsResponse stats = userFollowService.getStats(target.getId(), followerA.getId());
        assertEquals(1L, stats.getFollowingCount());
        assertEquals(2L, stats.getFollowerCount());
        assertEquals(true, stats.getFollowedByCurrentUser());

        Page<FollowUserResponse> followers = userFollowService.pageFollowers(target.getId(), 1, 10);
        assertEquals(2L, followers.getTotal());
        Set<String> followerNames = followers.getRecords().stream()
                .map(FollowUserResponse::getUsername)
                .collect(Collectors.toSet());
        assertTrue(followerNames.contains(followerA.getUsername()));
        assertTrue(followerNames.contains(followerB.getUsername()));

        Page<FollowUserResponse> followingPage = userFollowService.pageFollowing(target.getId(), 1, 10);
        assertEquals(1L, followingPage.getTotal());
        assertEquals(following.getUsername(), followingPage.getRecords().get(0).getUsername());
    }

    private User createUser(String prefix) {
        User user = new User();
        user.setUsername(prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        user.setPassword("password-hash");
        user.setRole(UserService.ROLE_ADMIN.equals(prefix) ? UserService.ROLE_ADMIN : "USER");
        user.setStatus(UserService.STATUS_ENABLED);
        userMapper.insert(user);
        return user;
    }
}

