package com.bitforum.dto;

import java.time.LocalDateTime;

import com.bitforum.entity.User;

import lombok.Data;

@Data
public class PublicUserProfileResponse {
    private Long userId;
    private String username;
    private String avatar;
    private String nickname;
    private String bio;
    private LocalDateTime createTime;
    private Integer status;
    private Long publishedArticleCount;
    private Long favoriteCount;
    private Long followingCount;
    private Long followerCount;
    private Boolean followedByCurrentUser;

    public static PublicUserProfileResponse from(User user, Long publishedArticleCount, Long favoriteCount) {
        return from(user, publishedArticleCount, favoriteCount, 0L, 0L, false);
    }

    public static PublicUserProfileResponse from(
            User user,
            Long publishedArticleCount,
            Long favoriteCount,
            Long followingCount,
            Long followerCount,
            Boolean followedByCurrentUser) {
        PublicUserProfileResponse response = new PublicUserProfileResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setAvatar(user.getAvatar());
        response.setNickname(user.getNickname());
        response.setBio(user.getBio());
        response.setCreateTime(user.getCreateTime());
        response.setStatus(user.getStatus());
        response.setPublishedArticleCount(publishedArticleCount);
        response.setFavoriteCount(favoriteCount);
        response.setFollowingCount(followingCount);
        response.setFollowerCount(followerCount);
        response.setFollowedByCurrentUser(Boolean.TRUE.equals(followedByCurrentUser));
        return response;
    }
}
