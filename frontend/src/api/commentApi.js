import request from './request'

// 按文章 ID 查询评论列表，不需要登录。
export function listComments(articleId, sort) {
  return request.get('/comment/listAll', {
    params: { articleId, ...(sort ? { sort } : {}) },
  })
}

// 发布评论需要登录，当前用户 ID 由后端从 JWT 中读取。
export function publishComment(payload) {
  return request.post('/comment/publish', payload)
}

export function listMyComments(articleId) {
  return request.get('/comment/mine', { params: { articleId } })
}

export function likeAnswer(commentId) {
  return request.post('/comment/like', { commentId })
}

export function unlikeAnswer(commentId) {
  return request.delete('/comment/like', { params: { commentId } })
}
