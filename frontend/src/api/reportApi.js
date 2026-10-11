import request from './request'

export function reportArticle(payload) {
  return request.post('/user/reports/article', payload)
}

export function reportComment(payload) {
  return request.post('/user/reports/comment', payload)
}

export function pageMyReports(params = { pageNum: 1, pageSize: 10 }) {
  return request.get('/user/reports', { params })
}

