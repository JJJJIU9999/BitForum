import request from './request'

export function pageNotifications(params = { pageNum: 1, pageSize: 10 }) {
  return request.get('/user/notifications', { params })
}

export function getUnreadNotificationCount() {
  return request.get('/user/notifications/unread-count')
}

export function markNotificationRead(notificationId) {
  return request.put('/user/notifications/read', null, {
    params: { notificationId },
  })
}

export function markAllNotificationsRead() {
  return request.put('/user/notifications/read-all')
}

