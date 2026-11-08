import { useState } from 'react'
import { Alert, Button, Modal, Popconfirm, Select, Space, Table, Typography, message } from 'antd'
import api from '../api'

export default function PositionCollaborators({ positionId }) {
  const [open, setOpen] = useState(false)
  const [rows, setRows] = useState([])
  const [members, setMembers] = useState([])
  const [selected, setSelected] = useState(null)
  const [permission, setPermission] = useState('VIEWER')
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(false)
  const path = `/recruitment/positions/${positionId}/collaborators`
  const load = async () => {
    setLoading(true); setError(false)
    try {
      const [grants, company] = await Promise.all([api.get(path), api.get('/company/members')])
      setRows(grants.data); setMembers(company.data)
    } catch { setError(true) }
    finally { setLoading(false) }
  }
  const change = async userId => {
    if (saving) return
    setSaving(true)
    try {
      if (userId != null) await api.delete(`${path}/${userId}`)
      else await api.put(path, { userId: selected, permission })
      setSelected(null); await load(); message.success(userId != null ? '已撤销授权' : '已添加协作成员')
    } catch (e) { message.error(e.response?.data?.message || '授权变更失败，请刷新重试') }
    finally { setSaving(false) }
  }
  const changeRole = async (row, next) => {
    setSaving(true)
    try { await api.put(path, { userId: row.userId, permission: next }); await load(); message.success('协作权限已更新') }
    catch(e) { message.error(e.response?.data?.message || '权限修改失败') }
    finally { setSaving(false) }
  }
  const me = JSON.parse(localStorage.getItem('user') || '{}').userId
  return <>
    <Button onClick={() => { setOpen(true); setSelected(null); load() }}>协作授权</Button>
    <Modal title="岗位协作授权" open={open} footer={null} width={680} onCancel={() => { if (!saving) setOpen(false) }}>
      <Typography.Paragraph>只读成员可查看已有资料；招聘协作成员还可筛选决策、匹配、重新解析及生成面试资料，可能产生模型费用。协作成员仅能取消自己发起的任务，创建人可取消全部任务。岗位修改、上传删除及转授权仍由创建人操作。私人知识库和聊天不共享。历史岗位首次授权时确认当前公司归属。</Typography.Paragraph>
      {error ? <Alert type="error" message="授权列表加载失败" action={<Button onClick={load}>重试加载</Button>} /> : <>
        <Space wrap style={{ marginBottom: 16 }}>
          <Select aria-label="只读协作成员" placeholder="选择本公司成员" showSearch optionFilterProp="label" value={selected} onChange={setSelected}
            loading={loading} disabled={loading || saving} style={{ width: 240 }}
            options={members.filter(m => m.userId !== me && !rows.some(r => r.userId === m.userId)).map(m => ({ value: m.userId, label: m.username }))}
            notFoundContent="暂无其他可授权成员" />
          <Select aria-label="协作权限" value={permission} onChange={setPermission} disabled={saving || loading} style={{ width: 140 }} options={[{ value: 'VIEWER', label: '只读协作' }, { value: 'EDITOR', label: '招聘协作' }]} />
          <Button type="primary" disabled={selected == null || loading || saving} loading={saving} onClick={() => change(null)}>添加协作成员</Button>
          <Button disabled={saving} loading={loading} onClick={load}>刷新授权</Button>
        </Space>
        <Table size="small" rowKey="userId" dataSource={rows} loading={loading} pagination={false} scroll={{ x: 500 }} locale={{ emptyText: '尚未授权其他成员' }} columns={[
          { title: '成员', dataIndex: 'username' },
          { title: '权限', render: (_, row) => <Select aria-label={`修改 ${row.username} 权限`} value={row.permission || 'VIEWER'} disabled={saving || loading} style={{ width: 130 }} options={[{ value: 'VIEWER', label: '只读协作' }, { value: 'EDITOR', label: '招聘协作' }]} onChange={next => changeRole(row,next)} /> },
          { title: '操作', render: (_, row) => <Popconfirm title="撤销该成员的协作权限？" description="后续访问及其未完成任务的写回将被拒绝，已查看内容无法收回。" okText="撤销授权" cancelText="取消" onConfirm={() => change(row.userId)}>
            <Button danger type="link" disabled={saving || loading}>撤销</Button>
          </Popconfirm> },
        ]} />
      </>}
    </Modal>
  </>
}
