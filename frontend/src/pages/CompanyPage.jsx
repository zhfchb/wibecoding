import { useEffect, useState } from 'react'
import { Alert, Button, Card, Form, Input, message, Modal, Space, Table, Tag } from 'antd'
import api from '../api'

export default function CompanyPage({ members = false }) {
  const [company, setCompany] = useState(null)
  const [rows, setRows] = useState([])
  const [error, setError] = useState(false)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [open, setOpen] = useState(false)
  const [form] = Form.useForm()
  const [memberForm] = Form.useForm()
  const load = async () => {
    setLoading(true); setError(false)
    try {
      const { data } = await api.get('/company')
      setCompany(data); form.setFieldsValue(data)
      if (members) setRows((await api.get('/company/members')).data)
    } catch { setError(true) }
    finally { setLoading(false) }
  }
  useEffect(() => { load() }, [members])
  const save = async values => {
    setSaving(true)
    try { setCompany((await api.put('/company', values)).data); message.success('公司资料已保存') }
    catch (e) { message.error(e.response?.data?.message || '保存失败') }
    finally { setSaving(false) }
  }
  const add = async () => {
    let values
    try { values = await memberForm.validateFields() } catch { return }
    setSaving(true)
    try { await api.post('/company/members', values); setOpen(false); memberForm.resetFields(); load(); message.success('HR账号已创建') }
    catch (e) { message.error(e.response?.data?.message || '创建失败') }
    finally { setSaving(false) }
  }
  const admin = company?.memberRole === 'ADMIN'
  return <Card title={members ? '成员管理' : '公司资料'} loading={loading}
    extra={<Space><Button onClick={load}>刷新</Button>{members && admin && <Button type="primary" onClick={() => setOpen(true)}>新增HR成员</Button>}</Space>}>
    {error ? <Alert type="error" message="无法加载公司资料，请重试" action={<Button onClick={load}>重试</Button>} /> : company && <>
      <Alert type="info" style={{ marginBottom: 20 }} message="当前阶段已支持公司资料与成员管理；招聘资料仍按原账号隔离，公司内共享将在后续阶段开放。" />
      {members ? <Table rowKey="userId" dataSource={rows} pagination={false} columns={[
        { title: '账号', dataIndex: 'username' }, { title: '公司角色', dataIndex: 'role', render: role => <Tag>{role === 'ADMIN' ? '公司管理员' : 'HR'}</Tag> },
      ]} /> : <Form form={form} layout="vertical" onFinish={save} disabled={!admin}>
        <Form.Item name="name" label="公司名称" rules={[{ required: true, whitespace: true }]}><Input maxLength={100} /></Form.Item>
        <Form.Item name="city" label="所在城市"><Input maxLength={50} /></Form.Item>
        <Form.Item name="description" label="公司简介"><Input.TextArea rows={5} maxLength={5000} showCount /></Form.Item>
        {admin && <Button type="primary" htmlType="submit" loading={saving}>保存公司资料</Button>}
      </Form>}
    </>}
    <Modal title="新增HR账号" open={open} onCancel={() => { setOpen(false); memberForm.resetFields() }} onOk={add} confirmLoading={saving} okText="创建" cancelText="取消">
      <Form form={memberForm} layout="vertical" autoComplete="off">
        <Form.Item name="username" label="账号" rules={[{ required: true }, { pattern: /^[A-Za-z0-9_-]{3,32}$/, message: '3～32位字母数字、下划线或连字符' }]}><Input autoComplete="off" /></Form.Item>
        <Form.Item name="password" label="初始密码" rules={[{ required: true, min: 8, max: 64, message: '请输入8～64位初始密码' }]}><Input.Password autoComplete="new-password" /></Form.Item>
      </Form>
    </Modal>
  </Card>
}
