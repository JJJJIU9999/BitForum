package com.bitforum.dto;

import com.bitforum.entity.User;

/** 仅公开身份摘要，不包含账户权限、候选资料或私有头像信息。 */
public record PublicAuthorSummary(Long userId, String displayName, String avatar, boolean profileAvailable) {
    public static PublicAuthorSummary unavailable(Long userId) {
        return new PublicAuthorSummary(userId, "用户不可用", null, false);
    }

    public static PublicAuthorSummary from(User user) {
        if (user == null) return unavailable(null);
        String name = "待审核用户";
        if ("PUBLISHED".equals(user.getPublicProfileStatus())) {
            name = nonBlank(user.getNickname(), nonBlank(user.getUsername(), "社区用户"));
        }
        String avatar = user.getAvatar();
        // 现有头像审核链公开的是站内图片入口，异常/历史外链降级为占位。
        if (avatar == null || !avatar.matches("/api/images/[1-9][0-9]*/content")) avatar = null;
        return new PublicAuthorSummary(user.getId(), name, avatar, true);
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
