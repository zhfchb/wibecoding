import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Card, Empty, Form, Input, message, Modal, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd'
import api from '../api'

export default function DepartmentsPage() {
  const [company, setCompany] = useState(null)
  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('ALL')
  const [editor, setEditor] = useState(null)
  const [saving, setSaving] = useState(false)
  const [changing, setChanging] = useState(null)
  const [form] = Form.useForm()
  const submitting = useRef(false)
  const loadVersion = useRef(0)
  const load = async () => {
    const version = ++loadVersion.current
    setLoading(true); setError(false)
    try {
      const [profile, departments] = await Promise.all([api.get('/company'), api.get('/company/departments')])
      if (loadVersion.current !== version) return
      setCompany(profile.data); setRows(departments.data)
    } catch { if (loadVersion.current === version) setError(true) }
    finally { if (loadVersion.current === version) setLoading(false) }
  }
  useEffect(() => { load(); return () => { loadVersion.current += 1 } }, [])
  const admin = company?.memberRole === 'ADMIN'
  const edit = row => { form.resetFields(); form.setFieldsValue(row || {}); setEditor(row || {}) }
  const save = async () => {
    if (submitting.current) return
    let values
    try { values = await form.validateFields() } catch { return }
    submitting.current = true; setSaving(true)
    try {
      if (editor.id) await api.put(`/company/departments/${editor.id}`, values)
      else await api.post('/company/departments', values)
      setEditor(null); form.resetFields(); message.success('部门已保存'); load()
    } catch (e) { message.error(e.response?.data?.message || '保存失败，请重试') }
    finally { submitting.current = false; setSaving(false) }
  }
  const toggle = async row => {
    if (changing != null) return
    setChanging(row.id)
    try {
      await api.post(`/company/departments/${row.id}/status`, { enabled: row.status !== 'ACTIVE' })
      message.success(row.status === 'ACTIVE' ? '部门已停用' : '部门已启用'); load()
    } catch (e) { message.error(e.response?.data?.message || '状态修改失败，请重试') }
    finally { setChanging(null) }
  }
  const visible = rows.filter(row => (status === 'ALL' || row.status === status) &&
    `${row.name} ${row.description || ''}`.toLowerCase().includes(query.trim().toLowerCase()))
  return <Card title="部门管理" extra={<Space wrap><Button onClick={load} loading={loading}>刷新部门</Button>
    {admin && <Button type="primary" disabled={error || loading} onClick={() => edit(null)}>新增部门</Button>}</Space>}>
    <Typography.Paragraph type="secondary">维护公司的部门目录。停用后保留记录，可重新启用；已关联岗位仍可查看，创建或更换岗位部门时仅可选择已启用部门。</Typography.Paragraph>
    {company && !admin && <Alert style={{ marginBottom: 16 }} type="info" showIcon message="你可以查看本公司部门，新增、编辑及启停由公司管理员操作。" />}
    {error ? <Alert type="error" showIcon message="部门加载失败，已有数据未清除，请重试" action={<Button onClick={load}>重试加载</Button>} /> : <>
      <Space wrap style={{ marginBottom: 16 }}>
        <Input.Search aria-label="搜索部门" placeholder="搜索部门名称或说明" allowClear value={query} onChange={e => setQuery(e.target.value)} style={{ width: 240 }} />
        <Select aria-label="部门状态" value={status} onChange={setStatus} style={{ width: 140 }} options={[
          { value: 'ALL', label: '全部状态' }, { value: 'ACTIVE', label: '启用中' }, { value: 'DISABLED', label: '已停用' },
        ]} />
      </Space>
      <Table rowKey="id" dataSource={visible} loading={loading} scroll={{ x: 620 }} pagination={{ pageSize: 10 }}
        locale={{ emptyText: <Empty description={rows.length ? '没有符合条件的部门，请调整筛选' : admin ? '暂无部门，点击右上角新增部门' : '暂无部门，请联系公司管理员'} /> }}
        columns={[
          { title: '部门名称', dataIndex: 'name', width: 180 },
          { title: '部门说明', dataIndex: 'description', render: v => v || '—' },
          { title: '状态', dataIndex: 'status', width: 100, render: value => <Tag color={value === 'ACTIVE' ? 'green' : 'default'}>{value === 'ACTIVE' ? '启用中' : '已停用'}</Tag> },
          ...(admin ? [{ title: '操作', width: 160, render: (_, row) => <Space>
            <Button type="link" size="small" disabled={error || loading} onClick={() => edit(row)}>编辑</Button>
            <Popconfirm title={row.status === 'ACTIVE' ? '停用此部门？' : '重新启用此部门？'} description="部门记录会保留。" okText="确认修改" cancelText="取消" onConfirm={() => toggle(row)}>
              <Button type="link" size="small" loading={changing === row.id} disabled={changing != null || loading}>{row.status === 'ACTIVE' ? '停用' : '启用'}</Button>
            </Popconfirm>
          </Space> }] : []),
        ]} />
    </>}
    <Modal title={editor?.id ? '编辑部门' : '新增部门'} open={editor !== null} onCancel={() => { if (!saving) setEditor(null) }}
      onOk={save} confirmLoading={saving} okText="保存部门" cancelText="取消" closable={!saving} maskClosable={!saving}>
      <Form form={form} layout="vertical" disabled={saving}>
        <Form.Item name="name" label="部门名称" rules={[{ required: true, whitespace: true, message: '请输入部门名称' }, { max: 100 }]}><Input maxLength={100} placeholder="如：研发部" /></Form.Item>
        <Form.Item name="description" label="部门说明（选填）"><Input.TextArea rows={3} maxLength={500} showCount /></Form.Item>
      </Form>
    </Modal>
  </Card>
}
