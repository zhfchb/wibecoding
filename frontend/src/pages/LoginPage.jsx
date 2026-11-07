import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Form, Input, Button, Alert } from 'antd'
import { ArrowRightOutlined, CheckOutlined } from '@ant-design/icons'
import { useAuth } from '../context/AuthContext'

export default function LoginPage() {
  const { login, register } = useAuth()
  const navigate = useNavigate()
  const [mode, setMode] = useState('login')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const submit = async ({ username, password }) => {
    setLoading(true); setError('')
    try {
      await (mode === 'login' ? login : register)(username, password)
      navigate('/recruitment', { replace: true })
    } catch (err) {
      setError(err.response?.data?.message || (mode === 'login' ? '登录失败，请检查用户名和密码。' : '注册失败，请稍后重试。'))
    } finally { setLoading(false) }
  }
  return (
    <main className="login-page">
      <section className="login-story">
        <a className="brand brand-on-dark" href="/login" aria-label="择序招聘首页"><span className="brand-mark">序</span><span>择序<span className="brand-caption">招聘工作台</span></span></a>
        <div className="login-editorial">
          <span className="eyebrow">为中小企业的技术招聘而设计</span>
          <h1>把时间留给<br />值得深入的<span>对话。</span></h1>
          <p>从岗位要求到面试准备，把分散的简历、匹配依据与候选人资料，整理成清晰的招聘流程。</p>
          <div className="login-process" aria-label="招聘工作流程">
            {[['01', '明确岗位', '写清要求，再开始筛选'], ['02', '整理简历', '提取技能，查看匹配依据'], ['03', '准备面试', '围绕经历与缺口深入提问']].map(([n, title, sub]) => <div key={n}><span>{n}</span><div><strong>{title}</strong><small>{sub}</small></div><CheckOutlined /></div>)}
          </div>
        </div>
        <div className="login-footnote">AI 整理信息与提供建议，每一次招聘决策由你做主。</div>
      </section>
      <section className="login-access">
        <span className="access-label">YOUR HIRING DESK</span>
        <div className="login-form-wrap">
          <span className="eyebrow">从一个好岗位开始</span>
          <h2>{mode === 'login' ? '欢迎回到工作台' : '开启你的招聘工作台'}</h2>
          <p>{mode === 'login' ? '登录后管理岗位、整理候选人，继续你的招聘工作。' : '创建个人账号，开始管理你的岗位与招聘资料。'}</p>
          <div className="auth-tabs" role="tablist" aria-label="账号操作">
            <button role="tab" aria-selected={mode === 'login'} onClick={() => { setMode('login'); setError('') }}>账号登录</button>
            <button role="tab" aria-selected={mode === 'register'} onClick={() => { setMode('register'); setError('') }}>创建账号</button>
          </div>
          {error && <Alert type="error" showIcon message={error} className="login-error" />}
          <Form key={mode} layout="vertical" onFinish={submit} requiredMark={false} size="large">
            <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }, ...(mode === 'register' ? [{ min: 3, max: 32, message: '用户名需为 3–32 位' }] : [])]}>
              <Input autoComplete="username" placeholder={mode === 'login' ? '输入你的用户名' : '设置 3–32 位用户名'} />
            </Form.Item>
            <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }, ...(mode === 'register' ? [{ min: 6, max: 64, message: '密码需为 6–64 位' }] : [])]}>
              <Input.Password autoComplete={mode === 'login' ? 'current-password' : 'new-password'} placeholder={mode === 'login' ? '输入你的密码' : '设置至少 6 位密码'} />
            </Form.Item>
            <Button type="primary" htmlType="submit" block loading={loading} icon={<ArrowRightOutlined />} iconPosition="end">{mode === 'login' ? '进入工作台' : '创建账号并进入'}</Button>
          </Form>
          <div className="login-help">使用已有账号登录。新账号将拥有独立的岗位与资料。</div>
        </div>
        <footer>择序 · 技术招聘，井然有序</footer>
      </section>
    </main>
  )
}
