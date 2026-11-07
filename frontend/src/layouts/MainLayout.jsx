import { useState } from 'react'
import { Outlet, useNavigate, useLocation } from 'react-router-dom'
import { Layout, Menu, Button, Drawer, Avatar } from 'antd'
import { LogoutOutlined, MessageOutlined, FileTextOutlined, TeamOutlined, CheckSquareOutlined, MenuOutlined, ArrowRightOutlined } from '@ant-design/icons'
import { useAuth } from '../context/AuthContext'

const routes = [
  { key: '/recruitment', icon: <TeamOutlined />, label: '岗位工作台', hint: '招聘 / 岗位工作台' },
  { key: '/knowledge', icon: <FileTextOutlined />, label: '招聘资料库', hint: '招聘支持 / 招聘资料库' },
  { key: '/chat', icon: <MessageOutlined />, label: '资料问答', hint: '招聘支持 / 资料问答' },
  { key: '/agent', icon: <CheckSquareOutlined />, label: '内容审核', hint: '招聘支持 / 内容审核' },
]
export default function MainLayout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [mobileOpen, setMobileOpen] = useState(false)
  const active = routes.find(r => pathname.startsWith(r.key)) || routes[0]
  const sidebar = <>
    <a className="brand" href="/recruitment" onClick={e => { e.preventDefault(); navigate('/recruitment'); setMobileOpen(false) }}><span className="brand-mark">序</span><span>择序<span className="brand-caption">技术招聘工作台</span></span></a>
    <div className="nav-caption">招聘管理</div>
    <Menu mode="inline" selectedKeys={[active.key]} items={[routes[0]]} onClick={({ key }) => { navigate(key); setMobileOpen(false) }} />
    <div className="nav-caption">招聘支持</div>
    <Menu mode="inline" selectedKeys={[active.key]} items={routes.slice(1)} onClick={({ key }) => { navigate(key); setMobileOpen(false) }} />
    <div className="sidebar-note"><span className="eyebrow">一个清晰的起点</span><strong>先建岗位，再看人选。</strong><p>明确技能与经验要求，让每一次筛选都有依据。</p><button onClick={() => { navigate('/recruitment'); setMobileOpen(false) }}>回到岗位工作台 <ArrowRightOutlined /></button></div>
    <div className="sidebar-bottom"><span className="small-dot" />招聘辅助 · 决策由你</div>
  </>
  return <Layout className="workspace-shell">
    <aside className="desktop-sidebar">{sidebar}</aside>
    <Drawer placement="left" open={mobileOpen} onClose={() => setMobileOpen(false)} width={256} title="工作导航"><div className="mobile-sidebar">{sidebar}</div></Drawer>
    <Layout className="workspace-main">
      <header className="workspace-header"><div className="header-location"><Button type="text" className="mobile-menu-button" icon={<MenuOutlined />} aria-label="打开导航" onClick={() => setMobileOpen(true)} /><span>{active.hint}</span>{pathname.includes('/positions/') && <span>/ 候选人筛选</span>}</div><div className="user-account"><Avatar size={30} style={{ background: '#e0eae5', color: '#215446' }}>{user?.username?.slice(0, 1).toUpperCase()}</Avatar><span>{user?.username}<small>{user?.role === 'ADMIN' ? '管理员' : '招聘用户'}</small></span><Button type="text" icon={<LogoutOutlined />} onClick={() => { logout(); navigate('/login', { replace: true }) }}>退出</Button></div></header>
      <main className="workspace-content"><Outlet /></main>
    </Layout>
  </Layout>
}
