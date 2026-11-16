import api from '../../shared/api/client.js'
export const agentApi = {
  submit: (question) =>
    api.post('/agent/multi-agent', { question }),
  getTask: (taskId) =>
    api.get(`/agent/tasks/${taskId}`),
  listPending: () =>
    api.get('/agent/tasks/pending'),
  approve: (taskId, expectedRejectCount) =>
    api.post(`/agent/tasks/${taskId}/approve`, { expectedRejectCount }),
  reject: (taskId, feedback, expectedRejectCount) =>
    api.post(`/agent/tasks/${taskId}/reject`, { feedback, expectedRejectCount }),
}

