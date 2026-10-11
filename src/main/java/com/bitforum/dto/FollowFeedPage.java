package com.bitforum.dto;

import java.util.List;
import com.bitforum.entity.Article;

public record FollowFeedPage(List<Article> records, String nextCursor, boolean hasMore) {}
