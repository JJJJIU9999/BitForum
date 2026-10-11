package com.bitforum.dto;

import lombok.Data;

@Data
public class AnswerLikeStats {
    private Long commentId;
    private long likeCount;
    private boolean likedByCurrentUser;
}
