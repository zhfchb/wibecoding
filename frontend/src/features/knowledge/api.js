import api from '../../shared/api/client.js'
export const knowledgeApi = {
  upload: (file) => {
    const formData = new FormData()
    formData.append('file', file)
    return api.post('/knowledge/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 300000, // Embedding and bounded vector-store recovery can exceed the normal 30s request budget.
    })
  },
  listDocuments: (page = 1, size = 10) =>
    api.get('/knowledge/documents', { params: { page, size } }),
  // 删除单个文档（含向量数据）
  deleteDocument: (documentId) =>
    api.delete(`/knowledge/documents/${documentId}`),
}

