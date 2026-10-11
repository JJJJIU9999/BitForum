import request from './request'

export function listCategories() {
  return request.get('/category/list')
}
