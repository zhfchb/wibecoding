import { Outlet, useNavigate, useLocation } from 'react-router-dom'
import { Layout, Menu, Button, Space, Typography } from 'antd'
import { LogoutOutlined, MessageOutlined, RobotOutlined, FileTextOutlined } from '@ant-design/icons'
import { useAuth } from '../context/AuthContext'

const { Header, Content, Sider } = Layout

const menuItems = [
  { key: '/chat', icon: <MessageOutlined />, label: 'RAG 对话' },
  { key: '/agent', icon: <RobotOutlined />, label: '多 Agent 任务' },
  { key: '/knowledge', icon: <FileTextOutlined />, label: '知识库管理' },
]

export default function MainLayout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  const handleLogout = () => {
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider width={220} theme="light" breakpoint="lg" collapsedWidth="0">
        <div style={{ padding: '16px 24px', fontSize: 16, fontWeight: 600 }}>
          智能知识库 Agent
        </div>
        <Menu
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: '#fff',
            padding: '0 24px',
            display: 'flex',
            justifyContent: 'flex-end',
            alignItems: 'center',
          }}
        >
          <Space>
            <Typography.Text>
              {user?.username}（{user?.role}）
            </Typography.Text>
            <Button type="text" icon={<LogoutOutlined />} onClick={handleLogout}>
              退出
            </Button>
          </Space>
        </Header>
        <Content style={{ margin: 24 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
