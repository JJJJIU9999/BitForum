import request from './request'

// 注册：body 里只传 username/password，role/status 由后端默认处理。
export function registerUser(payload) {
  return request.post('/user/register', payload)
}

// 登录：成功后后端会在 data.token 中返回 JWT。
export function loginUser(payload) {
  return request.post('/user/login', payload)
}

export function getCurrentUserProfile() {
  return request.get('/user/profile')
}

export function updateCurrentUserProfile(payload) {
  return request.put('/user/profile', payload)
}

export function getPublicUserProfile(userId) {
  return request.get(`/users/${userId}/profile`)
}

export function pagePublicUserArticles(userId, params = { pageNum: 1, pageSize: 10 }) {
  return request.get(`/users/${userId}/articles`, { params })
}

export function followUser(userId) {
  return request.post(`/users/${userId}/follow`)
}

export function unfollowUser(userId) {
  return request.delete(`/users/${userId}/follow`)
}

export function pageUserFollowers(userId, params = { pageNum: 1, pageSize: 10 }) {
  return request.get(`/users/${userId}/followers`, { params })
}

export function pageUserFollowing(userId, params = { pageNum: 1, pageSize: 10 }) {
  return request.get(`/users/${userId}/following`, { params })
}
