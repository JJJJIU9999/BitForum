import request from './request'

const AVATAR_MAX_SIZE = 2 * 1024 * 1024
const ARTICLE_COVER_MAX_SIZE = 5 * 1024 * 1024

function formatSize(size) {
  return `${(size / 1024 / 1024).toFixed(2)}MB`
}

function validateFileSize(file, maxSize, label) {
  if (file && file.size > maxSize) {
    throw new Error(`${label}不能超过 ${formatSize(maxSize)}，当前文件约 ${formatSize(file.size)}。`)
  }
}

function uploadFile(path, file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post(path, formData)
}

export function uploadAvatar(file) {
  validateFileSize(file, AVATAR_MAX_SIZE, '头像图片')
  return uploadFile('/upload/avatar', file)
}

export function uploadArticleCover(file) {
  validateFileSize(file, ARTICLE_COVER_MAX_SIZE, '文章封面')
  return uploadFile('/upload/article-cover', file)
}

export function getPrivateImagePreview(imageId) {
  return request.get(`/images/${imageId}/preview`, { responseType: 'blob' })
}
