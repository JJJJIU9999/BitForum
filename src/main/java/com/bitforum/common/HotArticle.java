package com.bitforum.common;

import com.bitforum.entity.Article;

public class HotArticle {
    private Article article;
    private Long hotScore;

    public HotArticle(Article article, Long hotScore) {
        this.article = article;
        this.hotScore = hotScore;
    }

    public Article getArticle() {
        return this.article;
    }

    public Long getHotScore() {
        return this.hotScore;
    }
}
