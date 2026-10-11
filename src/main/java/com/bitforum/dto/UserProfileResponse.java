package com.bitforum.dto;

import java.time.LocalDateTime;

import com.bitforum.entity.User;

import lombok.Data;

@Data
public class UserProfileResponse {
    private Long userId;
    private String username;
    private String avatar;
    private String nickname;
    private String bio;
    private LocalDateTime createTime;
    private Long publishedArticleCount;
    private Long favoriteCount;
    private boolean pendingTextReview;
    private Long pendingAvatarImageId;

    public static UserProfileResponse from(User user, Long publishedArticleCount, Long favoriteCount) {
        UserProfileResponse response = new UserProfileResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setAvatar(user.getAvatar());
        response.setPendingAvatarImageId(user.getPendingAvatarImageId());
        response.setNickname(user.getNickname());
        response.setBio(user.getBio());
        response.setCreateTime(user.getCreateTime());
        response.setPublishedArticleCount(publishedArticleCount);
        response.setFavoriteCount(favoriteCount);
        return response;
    }
}
