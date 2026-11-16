import { Routes, Route, Navigate } from 'react-router-dom'
import { useAuth } from './features/auth/context/AuthContext.jsx'
import LoginPage from './features/auth/pages/LoginPage.jsx'
import DepartmentsPage from './features/company/pages/DepartmentsPage.jsx'
import CompanyPage from './features/company/pages/CompanyPage.jsx'
import MainLayout from './layouts/MainLayout.jsx'
import ChatPage from './features/chat/pages/ChatPage.jsx'
import ContentReviewPage from './features/review/pages/ContentReviewPage.jsx'
import KnowledgePage from './features/knowledge/pages/KnowledgePage.jsx'
import RecruitmentPage from './features/recruitment/pages/RecruitmentPage.jsx'
import PositionWorkbenchPage from './features/recruitment/pages/PositionWorkbenchPage.jsx'
import InterviewWorkbenchPage from './features/interview/pages/InterviewWorkbenchPage.jsx'
// 路由守卫：未登录跳转 /login
function ProtectedRoute({ children }) {
  const { user } = useAuth()
  if (!user) return <Navigate to="/login" replace />
  return children
}

export default function App() {
  const { user } = useAuth()
  const interviewer = user?.companyRole === 'INTERVIEWER'
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
        <Route index element={<Navigate to={interviewer ? '/interviews' : '/recruitment'} replace />} />
        <Route path="interviews" element={<InterviewWorkbenchPage />} />
        <Route path="company/departments" element={<DepartmentsPage />} />
        <Route path="company/profile" element={<CompanyPage key="profile" />} />
        <Route path="company/members" element={<CompanyPage key="members" members />} />
        <Route path="chat" element={<ChatPage />} />
        <Route path="agent" element={<ContentReviewPage />} />
        <Route path="knowledge" element={<KnowledgePage />} />
        <Route path="recruitment" element={interviewer ? <Navigate to="/interviews" replace /> : <RecruitmentPage />} />
        <Route path="recruitment/positions/:id" element={interviewer ? <Navigate to="/interviews" replace /> : <PositionWorkbenchPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
