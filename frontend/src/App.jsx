import { Routes, Route, Navigate } from 'react-router-dom'
import { useAuth } from './context/AuthContext'
import LoginPage from './pages/LoginPage.jsx'
import MainLayout from './layouts/MainLayout.jsx'
import ChatPage from './pages/ChatPage.jsx'
import AgentPage from './pages/AgentPage.jsx'
import KnowledgePage from './pages/KnowledgePage.jsx'
import RecruitmentPage from './pages/RecruitmentPage.jsx'
import PositionWorkbenchPage from './pages/PositionWorkbenchPage.jsx'

// 路由守卫：未登录跳转 /login
function ProtectedRoute({ children }) {
  const { user } = useAuth()
  if (!user) return <Navigate to="/login" replace />
  return children
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/"
        element={
          <ProtectedRoute>
            <MainLayout />
          </ProtectedRoute>
        }
      >
        <Route index element={<Navigate to="/recruitment" replace />} />
        <Route path="chat" element={<ChatPage />} />
        <Route path="agent" element={<AgentPage />} />
        <Route path="knowledge" element={<KnowledgePage />} />
        <Route path="recruitment" element={<RecruitmentPage />} />
        <Route path="recruitment/positions/:id" element={<PositionWorkbenchPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
