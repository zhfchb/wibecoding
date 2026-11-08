import { useState } from 'react'
import { Outlet, useNavigate, useLocation } from 'react-router-dom'
import { Layout, Menu, Button, Drawer, Avatar } from 'antd'
import { LogoutOutlined, MessageOutlined, FileTextOutlined, TeamOutlined, CheckSquareOutlined, MenuOutlined, ArrowRightOutlined } from '@ant-design/icons'
import { useAuth } from '../context/AuthContext'

const companyRoutes = [
  { key: '/company/profile', label: '公司资料', hint: '公司管理 / 公司资料' },
  { key: '/company/departments', label: '部门管理', hint: '公司管理 / 部门管理' },
  { key: '/company/members', label: '成员管理', hint: '公司管理 / 成员管理' },
]
const routes = [
  { key: '/recruitment', icon: <TeamOutlined />, label: '岗位工作台', hint: '招聘 / 岗位工作台' },
  { key: '/knowledge', icon: <FileTextOutlined />, label: '招聘资料库', hint: '招聘支持 / 招聘资料库' },
  { key: '/chat', icon: <MessageOutlined />, label: '资料问答', hint: '招聘支持 / 资料问答' },
  { key: '/agent', icon: <CheckSquareOutlined />, label: '内容审核', hint: '招聘支持 / 内容审核' },
  { key: '/interviews', icon: <CheckSquareOutlined />, label: '面试工作台', hint: '面试 / 我的面试安排' },
]
export default function MainLayout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [mobileOpen, setMobileOpen] = useState(false)
  const interviewer = user?.companyRole === 'INTERVIEWER'
  const active = [...routes, ...companyRoutes].find(r => pathname.startsWith(r.key)) || routes[0]
  const sidebar = <>
    <a className="brand" href="/recruitment" onClick={e => { e.preventDefault(); navigate('/recruitment'); setMobileOpen(false) }}><span className="brand-mark">序</span><span>择序<span className="brand-caption">技术招聘工作台</span></span></a>
    <div className="nav-caption">招聘管理</div>
    <Menu mode="inline" selectedKeys={[active.key]} items={interviewer ? [routes[4]] : [routes[0], routes[4]]} onClick={({ key }) => { navigate(key); setMobileOpen(false) }} />
    {!interviewer && <><div className="nav-caption">招聘支持</div>
    <Menu mode="inline" selectedKeys={[active.key]} items={routes.slice(1, 4)} onClick={({ key }) => { navigate(key); setMobileOpen(false) }} />
    <div className="nav-caption">公司管理</div>
    <Menu mode="inline" selectedKeys={[active.key]} items={companyRoutes} onClick={({ key }) => { navigate(key); setMobileOpen(false) }} /></>}
    <div className="sidebar-note"><span className="eyebrow">一个清晰的起点</span><strong>{interviewer ? '围绕证据，独立评价。' : '先建岗位，再看人选。'}</strong><p>{interviewer ? '查看获分配人选，及时保存面试记录并提交评价。' : '明确技能与经验要求，让每一次筛选都有依据。'}</p><button onClick={() => { navigate(interviewer ? '/interviews' : '/recruitment'); setMobileOpen(false) }}>回到工作台 <ArrowRightOutlined /></button></div>
    <div className="sidebar-bottom"><span className="small-dot" />招聘辅助 · 决策由你</div>
  </>
  return <Layout className="workspace-shell">
    <aside className="desktop-sidebar">{sidebar}</aside>
    <Drawer placement="left" open={mobileOpen} onClose={() => setMobileOpen(false)} width={256} title="工作导航"><div className="mobile-sidebar">{sidebar}</div></Drawer>
    <Layout className="workspace-main">
      <header className="workspace-header"><div className="header-location"><Button type="text" className="mobile-menu-button" icon={<MenuOutlined />} aria-label="打开导航" onClick={() => setMobileOpen(true)} /><span>{active.hint}</span>{pathname.includes('/positions/') && <span>/ 候选人筛选</span>}</div><div className="user-account"><Avatar size={30} style={{ background: '#e0eae5', color: '#215446' }}>{user?.username?.slice(0, 1).toUpperCase()}</Avatar><span>{user?.username}<small>{interviewer ? '面试官' : user?.role === 'ADMIN' ? '管理员' : '招聘用户'}</small></span><Button type="text" icon={<LogoutOutlined />} onClick={() => { logout(); navigate('/login', { replace: true }) }}>退出</Button></div></header>
      <main className="workspace-content"><Outlet /></main>
    </Layout>
  </Layout>
}
