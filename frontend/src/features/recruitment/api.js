import api from '../../shared/api/client.js'
export const recruitmentApi = {
  // 岗位管理
  createPosition: (data) => api.post('/recruitment/positions', data),
  importPosition: (file) => {
    const formData = new FormData()
    formData.append('file', file)
    return api.post('/recruitment/positions/import', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
  listPositions: () => api.get('/recruitment/positions'),
  getPosition: (id) => api.get(`/recruitment/positions/${id}`),
  closePosition: (id) => api.post(`/recruitment/positions/${id}/close`),
  deletePosition: (id) => api.delete(`/recruitment/positions/${id}`),

  // 简历管理
  uploadResumes: (positionId, files) => {
    const formData = new FormData()
    files.forEach((f) => formData.append('files', f))
    return api.post(`/recruitment/positions/${positionId}/resumes`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 300000, // 批量解析+LLM抽取较慢
    })
  },
  listResumes: (positionId) => api.get(`/recruitment/positions/${positionId}/resumes`),
  deleteResume: (resumeId) => api.delete(`/recruitment/resumes/${resumeId}`),
  submitResumeParseTask: (positionId, requestKey, resumeIds) =>
    api.post(`/recruitment/positions/${positionId}/resumes/parse-tasks`, { requestKey, resumeIds }),
  latestResumeParseTask: (positionId) => api.get(`/recruitment/positions/${positionId}/resumes/parse-tasks/latest`),
  cancelResumeParseTask: (taskId) => api.post(`/recruitment/resumes/parse-tasks/${taskId}/cancel`),
  // 人岗匹配
  runMatch: (positionId) =>
    api.post(`/recruitment/positions/${positionId}/match/run`, null, { timeout: 300000 }),
  submitMatchTask: (positionId, requestKey) =>
    api.post(`/recruitment/positions/${positionId}/match/tasks`, { requestKey }),
  latestMatchTask: (positionId) =>
    api.get(`/recruitment/positions/${positionId}/match/tasks/latest`),
  cancelMatchTask: (taskId) => api.post(`/recruitment/match/tasks/${taskId}/cancel`),
  listMatchResults: (positionId) =>
    api.get(`/recruitment/positions/${positionId}/match/results`),
  decide: (matchId, approved) =>
    api.post(`/recruitment/match/${matchId}/decision`, null, { params: { approved } }),

}
