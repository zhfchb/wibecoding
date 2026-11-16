import api from '../../shared/api/client.js'
export const interviewApi = {
  submitMaterialTask: (resumeId, kind, requestKey) => api.post(`/recruitment/resumes/${resumeId}/materials/${kind}/tasks`, { requestKey }),
  latestMaterialTask: (resumeId, kind, config) => api.get(`/recruitment/resumes/${resumeId}/materials/${kind}/tasks/latest`, config),
  cancelMaterialTask: (taskId) => api.post(`/recruitment/materials/tasks/${taskId}/cancel`),
  generateQuestions: (resumeId) => api.post(`/recruitment/resumes/${resumeId}/questions/generate`, null, { timeout: 300000 }),
  listQuestions: (resumeId) => api.get(`/recruitment/resumes/${resumeId}/questions`),
  generateReport: (resumeId) => api.post(`/recruitment/resumes/${resumeId}/report/generate`, null, { timeout: 300000 }),
  getReport: (resumeId) => api.get(`/recruitment/resumes/${resumeId}/report`),
}
