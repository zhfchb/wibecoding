import api from '../../shared/api/client.js'
export const authApi = {
  login: (username, password) =>
    api.post('/auth/login', { username, password }),
  register: (username, password) =>
    api.post('/auth/register', { username, password }),
  me: () => api.get('/auth/me'),
}

