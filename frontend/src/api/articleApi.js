import request from './request'

// 分页查询文章，不需要登录。params 会被 axios 转成 URL 查询参数。
export function pageArticles(params = { pageNum: 1, pageSize: 10 }) {
  return request.get('/article/page', { params })
}

// 基础搜索只查已发布文章，后端使用 MySQL LIKE 查询标题和正文。
export function searchArticles(params = { pageNum: 1, pageSize: 10 }) {
  return request.get('/article/search', { params })
}

// 查询文章详情。后端会把 Redis 中的浏览量、点赞数装进返回数据。
export function getArticleDetail(articleId) {
  return request.get('/article/detail', {
    params: { articleId },
  })
}

// 浏览文章：这个接口本身会让 Redis 浏览量 +1。
export function viewArticle(articleId) {
  return request.get('/article/view', {
    params: { articleId },
  })
}

// 热门文章来自 Redis ZSet。
export function getHotArticles() {
  return request.get('/article/hot')
}

// 文章的相关推荐（M17）。公开接口，**未登录也能看到**：
// 服务端用三路召回（内容相似 / 热度 / 关注）+ RRF 融合排序，
// 匿名访客没有个人行为数据，会自动退化为"内容相似 + 热度"两路。
// 推荐理由由 AI 生成，AI 不可用时该字段为空，列表照常返回。
export function getRecommendations(articleId, limit = 5) {
  return request.get('/article/recommendations', {
    params: { articleId, limit },
  })
}

// 发布文章需要登录，Authorization 会由 request.js 自动添加。
export function publishArticle(payload) {
  return request.post('/article/publish', payload)
}

// 保存草稿，草稿不会进入公开文章列表。
export function saveDraft(payload) {
  return request.post('/article/draft', payload)
}

// 草稿或被驳回文章再次提交审核。
export function submitArticle(articleId) {
  return request.post('/article/submit', null, {
    params: { articleId },
  })
}

// 作者查看自己的全部状态文章。
export function pageMyArticles(params = { pageNum: 1, pageSize: 10 }) {
  return request.get('/user/articles', { params })
}

// 我的收藏默认只返回当前用户收藏且仍处于已发布状态的文章。
export function pageMyFavorites(params = { pageNum: 1, pageSize: 10 }) {
  return request.get('/user/favorites', { params })
}

// 修改文章需要登录，且后端会校验只能改自己的文章。
export function updateArticle(payload) {
  return request.put('/article/update', payload)
}

// 删除文章需要登录，普通用户只能删除自己的文章。
export function deleteArticle(articleId) {
  return request.delete('/article/delete', {
    params: { articleId },
  })
}

// 点赞接口使用 query 参数 articleId；body 不需要传内容，所以第二个参数传 null。
export function likeArticle(articleId) {
  return request.post('/article/like', null, {
    params: { articleId },
  })
}

// 取消点赞和点赞的参数结构保持一致。
export function unlikeArticle(articleId) {
  return request.post('/article/unlike', null, {
    params: { articleId },
  })
}

export function favoriteArticle(articleId) {
  return request.post('/article/favorite', null, {
    params: { articleId },
  })
}

export function unfavoriteArticle(articleId) {
  return request.delete('/article/favorite', {
    params: { articleId },
  })
}

// 问题作者采纳、更换或取消已公开的顶层回答。
export function acceptAnswer(articleId, answerId) {
  return request.put('/article/accepted-answer', { articleId: Number(articleId), answerId })
}

export function cancelAcceptedAnswer(articleId) {
  return request.delete('/article/accepted-answer', { params: { articleId } })
}
