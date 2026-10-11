import request from './request'
export const getTopic = (id) => request.get(`/category/${id}`)
export const followTopic = (id) => request.post(`/category/${id}/follow`)
export const unfollowTopic = (id) => request.delete(`/category/${id}/follow`)
export const pageFollowedTopics = (params) => request.get('/user/followed-topics', { params })
export const pageTopicContents = (id, params) => request.get(`/category/${id}/contents`, { params })
export const getFollowFeed = (params) => request.get('/user/follow-feed', { params })
