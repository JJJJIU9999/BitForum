package com.bitforum.service;

import com.bitforum.exception.BusinessException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bitforum.entity.Article;
import com.bitforum.entity.Comment;
import com.bitforum.entity.User;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CommentMapper;
import com.bitforum.mapper.UserMapper;

@Service
public class TextRescanService {
    private static final int BATCH_SIZE = 100;
    private static final List<String> TYPES = List.of("ARTICLE", "COMMENT", "PROFILE");
    private final Map<Long, Long> previewedVersions = new ConcurrentHashMap<>();

    @Autowired private ArticleMapper articleMapper;
    @Autowired private CommentMapper commentMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private TextRuleService ruleService;
    @Autowired private TextReviewService reviewService;
    @Autowired private ArticleService articleService;
    @Autowired private CommentService commentService;

    public record Sample(String targetType, long targetId, String matchedRules, String excerpt) {}
    public record Preview(long ruleVersion, long scanned, long matched, List<Sample> samples) {}
    public record Batch(long ruleVersion, String nextCursor, int scanned, int held, boolean complete) {}

    public Preview preview(Long actorId) {
        long version = ruleService.currentVersion();
        long scanned = 0;
        long matched = 0;
        List<Sample> samples = new ArrayList<>();
        for (String type : TYPES) {
            long cursor = 0;
            while (true) {
                List<?> rows = load(type, cursor);
                if (rows.isEmpty()) break;
                for (Object row : rows) {
                    long id = id(row);
                    scanned++;
                    if (reviewService.eligibleForRescan(type, id, text(row), version)) {
                        matched++;
                        if (samples.size() < 20) {
                            String content = text(row);
                            String excerpt = content.length() > 120 ? content.substring(0, 120) + "…" : content;
                            samples.add(new Sample(type, id,
                                    String.join("、", ruleService.match(content).phrases()), excerpt));
                        }
                    }
                    cursor = id;
                }
                if (ruleService.currentVersion() != version) {
                    throw new BusinessException(409, "复扫期间规则已变化，请重新预览");
                }
            }
        }
        previewedVersions.put(actorId, version);
        return new Preview(version, scanned, matched, samples);
    }

    @Transactional
    public Batch execute(long expectedVersion, String cursorValue, Long actorId) {
        if (!Objects.equals(previewedVersions.get(actorId), expectedVersion)) {
            throw new BusinessException(409, "请先生成当前规则版本的复扫预览");
        }
        checkVersion(expectedVersion);
        String[] parts = cursorValue == null ? new String[] { TYPES.get(0), "0" } : cursorValue.split(":", -1);
        if (parts.length != 2 || !TYPES.contains(parts[0])) {
            throw new BusinessException("复扫游标无效");
        }
        long cursor;
        try {
            cursor = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new BusinessException("复扫游标无效", e);
        }
        if (cursor < 0) throw new BusinessException("复扫游标无效");
        String type = parts[0];
        List<?> rows = load(type, cursor);
        int held = 0;
        for (Object row : rows) {
            checkVersion(expectedVersion);
            long id = id(row);
            String content = text(row);
            if ("COMMENT".equals(type)) {
                Comment comment = (Comment) row;
                articleMapper.lockById(comment.getArticleId());
                Comment current = commentMapper.lockById(id);
                if (current == null || !CommentService.STATUS_PUBLISHED.equals(current.getStatus())) {
                    cursor = id;
                    continue;
                }
            }
            if (reviewService.eligibleForRescan(type, id, content, expectedVersion)
                    && reviewService.recordIfMatched(type, id, content, "RESCAN", null, null)) {
                hold(type, id, actorId);
                held++;
            }
            cursor = id;
        }
        checkVersion(expectedVersion);
        if (rows.size() == BATCH_SIZE) {
            return new Batch(expectedVersion, type + ":" + cursor, rows.size(), held, false);
        }
        int next = TYPES.indexOf(type) + 1;
        if (next == TYPES.size()) {
            previewedVersions.remove(actorId, expectedVersion);
        }
        return new Batch(expectedVersion, next == TYPES.size() ? null : TYPES.get(next) + ":0",
                rows.size(), held, next == TYPES.size());
    }

    private void hold(String type, long id, Long actorId) {
        switch (type) {
            case "ARTICLE" -> articleService.holdForTextReview(id, actorId);
            case "COMMENT" -> commentService.holdForTextReview(id);
            case "PROFILE" -> {
                User user = userMapper.selectById(id);
                if (user != null && "PUBLISHED".equals(user.getPublicProfileStatus())) {
                    user.setPublicProfileStatus("PENDING");
                    userMapper.updateById(user);
                }
            }
            default -> throw new BusinessException("未知复扫目标");
        }
    }

    private List<?> load(String type, long cursor) {
        return switch (type) {
            case "ARTICLE" -> articleMapper.selectList(new QueryWrapper<Article>()
                    .eq("status", ArticleService.STATUS_PUBLISHED).gt("id", cursor)
                    .orderByAsc("id").last("LIMIT " + BATCH_SIZE));
            case "COMMENT" -> commentMapper.selectList(new QueryWrapper<Comment>()
                    .eq("status", CommentService.STATUS_PUBLISHED).gt("id", cursor)
                    .orderByAsc("id").last("LIMIT " + BATCH_SIZE));
            case "PROFILE" -> userMapper.selectList(new QueryWrapper<User>()
                    .eq("public_profile_status", "PUBLISHED").gt("id", cursor)
                    .orderByAsc("id").last("LIMIT " + BATCH_SIZE));
            default -> throw new BusinessException("未知复扫目标");
        };
    }

    private long id(Object row) {
        if (row instanceof Article a) return a.getId();
        if (row instanceof Comment c) return c.getId();
        return ((User) row).getId();
    }

    private String text(Object row) {
        if (row instanceof Article a) return Objects.toString(a.getTitle(), "") + "\n" + Objects.toString(a.getContent(), "");
        if (row instanceof Comment c) return c.getContent();
        User user = (User) row;
        return Objects.toString(user.getUsername(), "") + "\n"
                + Objects.toString(user.getNickname(), "") + "\n"
                + Objects.toString(user.getBio(), "");
    }

    private void checkVersion(long expectedVersion) {
        if (ruleService.currentVersion() != expectedVersion) {
            throw new BusinessException(409, "规则版本已变化，请重新预览");
        }
    }
}
