import request from './request'

// 管理员健康检查，用来验证当前 token 是否具备 ADMIN 权限。
export function checkAdminHealth() {
  return request.get('/admin/health')
}

export function getAdminDashboardSummary() {
  return request.get('/admin/dashboard/summary')
}

// 后台文章分页：管理员可以查看所有文章。
export function pageAdminArticles(params = { pageNum: 1, pageSize: 10 }) {
  return request.get('/admin/article/page', { params })
}

export function pageAuditArticles(params = { status: 'PENDING', pageNum: 1, pageSize: 10 }) {
  return request.get('/admin/article/audit/page', { params })
}

export function approveArticle(articleId) {
  return request.put('/admin/article/audit/approve', null, {
    params: { articleId },
  })
}

export function rejectArticle(payload) {
  return request.put('/admin/article/audit/reject', payload)
}

export function offlineArticle(payload) {
  return request.put('/admin/article/offline', payload)
}

// 管理员删除文章不校验作者归属，权限由后端 AdminInterceptor 控制。
export function deleteAdminArticle(articleId) {
  return request.delete('/admin/article/delete', {
    params: { articleId },
  })
}

// 后台评论分页：管理员可以查看所有评论。
export function pageAdminComments(params = { pageNum: 1, pageSize: 10 }) {
  return request.get('/admin/comment/page', { params })
}

// 管理员删除评论。
export function deleteAdminComment(commentId) {
  return request.delete('/admin/comment/delete', {
    params: { commentId },
  })
}

// 后台用户分页：后端返回的是脱敏 DTO，不包含 password。
export function pageAdminUsers(params = { pageNum: 1, pageSize: 10 }) {
  return request.get('/admin/user/page', { params })
}

// 禁用用户；后端会阻止管理员禁用自己。
export function disableUser(userId) {
  return request.put('/admin/user/disable', null, {
    params: { userId },
  })
}

// 启用用户。
export function enableUser(userId) {
  return request.put('/admin/user/enable', null, {
    params: { userId },
  })
}

export function pageAdminCategories(params = { pageNum: 1, pageSize: 10 }) {
  return request.get('/admin/category/page', { params })
}

export function createCategory(payload) {
  return request.post('/admin/category/create', payload)
}

export function updateCategory(payload) {
  return request.put('/admin/category/update', payload)
}

export function enableCategory(categoryId) {
  return request.put('/admin/category/enable', null, {
    params: { categoryId },
  })
}

export function disableCategory(categoryId) {
  return request.put('/admin/category/disable', null, {
    params: { categoryId },
  })
}

export function deleteCategory(categoryId) {
  return request.delete('/admin/category/delete', {
    params: { categoryId },
  })
}

export function pageAdminReports(params = { status: 'PENDING', pageNum: 1, pageSize: 10 }) {
  return request.get('/admin/reports', { params })
}

export function resolveReport(payload) {
  return request.put('/admin/reports/resolve', payload)
}

export function rejectReport(payload) {
  return request.put('/admin/reports/reject', payload)
}

// M15：AI 知识库统计与全量重建（文章分块、向量化后的入库状态）。
export function getKbStats() {
  return request.get('/admin/ai/kb/stats')
}

export function rebuildKnowledgeBase() {
  return request.post('/admin/ai/kb/rebuild')
}

// M16：AI 审核记录查询、人工反馈与处理标记。
export function pageModerationRecords(params = { pageNum: 1, pageSize: 10, pendingOnly: true }) {
  return request.get('/admin/ai/moderation/records', { params })
}

export function submitModerationFeedback(recordId, feedback) {
  return request.post(`/admin/ai/moderation/records/${recordId}/feedback`, { feedback })
}

export function markModerationHandled(recordId) {
  return request.post(`/admin/ai/moderation/records/${recordId}/handle`)
}

// M17：AI 运营洞察。生成是异步的 —— generate 只负责受理并立刻返回，
// 之后用 getInsightStatus 轮询，完成后用 getLatestInsight 取报告。
export function generateInsight() {
  return request.post('/admin/ai/insight/generate')
}

export function getLatestInsight() {
  return request.get('/admin/ai/insight/latest')
}

export function getInsightStatus() {
  return request.get('/admin/ai/insight/status')
}

export function getInsightHistory(limit = 10) {
  return request.get('/admin/ai/insight/history', { params: { limit } })
}

// M18：AI 执行轨迹。一次 AI 调用一条记录，含路由、工具调用链、每步耗时与 token。
// 列表接口刻意不返回步骤明细（轨迹表会随使用量增长），详情接口才返回 steps。
export function pageAiTraces(params = { pageNum: 1, pageSize: 10 }) {
  return request.get('/admin/ai/traces', { params })
}

export function getAiTraceDetail(traceId) {
  return request.get(`/admin/ai/traces/${traceId}`)
}

// M18：AI 用量概览（按天 / 按 Agent / Top 用户 + 成本估算）。
export function getAiUsageOverview(days = 7) {
  return request.get('/admin/ai/usage/overview', { params: { days } })
}

// C-M17：文字规则、待审与历史复扫。
export const listTextRules = () => request.get('/admin/text-moderation/rules')
export const createTextRule = (phrase) => request.post('/admin/text-moderation/rules', { phrase })
export const updateTextRule = (id, payload) => request.put(`/admin/text-moderation/rules/${id}`, payload)
export const deleteTextRule = (id) => request.delete(`/admin/text-moderation/rules/${id}`)
export const pageTextOperations = (params) => request.get('/admin/text-moderation/operations', { params })
export const pageTextPending = (params) => request.get('/admin/text-moderation/pending', { params })
export const decideTextPending = (id, payload) => request.put(`/admin/text-moderation/pending/${id}`, payload)
export const previewTextRescan = () => request.get('/admin/text-moderation/rescan/preview')
export const executeTextRescan = (payload) => request.post('/admin/text-moderation/rescan/execute', payload)

export const pagePendingImages = (params) => request.get('/admin/images/pending', { params })
export const approveImage = (id) => request.put(`/admin/images/${id}/approve`)
export const rejectImage = (id, reason) => request.put(`/admin/images/${id}/reject`, { reason })
