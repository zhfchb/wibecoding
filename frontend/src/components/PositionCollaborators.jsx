import { useState } from 'react'
import { Alert, Button, Modal, Popconfirm, Select, Space, Table, Typography, message } from 'antd'
import api from '../api'

export default function PositionCollaborators({ positionId }) {
  const [open, setOpen] = useState(false)
  const [rows, setRows] = useState([])
  const [members, setMembers] = useState([])
  const [selected, setSelected] = useState(null)
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
      else await api.put(path, { userId: selected })
      setSelected(null); await load(); message.success(userId != null ? '已撤销只读授权' : '已授予岗位只读权限')
    } catch (e) { message.error(e.response?.data?.message || '授权变更失败，请刷新重试') }
    finally { setSaving(false) }
  }
  const me = JSON.parse(localStorage.getItem('user') || '{}').userId
  return <>
    <Button onClick={() => { setOpen(true); setSelected(null); load() }}>协作授权</Button>
    <Modal title="岗位只读协作" open={open} footer={null} width={640} onCancel={() => { if (!saving) setOpen(false) }}>
      <Typography.Paragraph>指定成员可查看本岗位、候选人、匹配结果及已有面试题和报告。上传、删除、筛选决策及AI任务仍由创建人操作。私人知识库和聊天不会共享。历史岗位首次授权时会确认属于当前公司，原创建人不变。</Typography.Paragraph>
      {error ? <Alert type="error" message="授权列表加载失败" action={<Button onClick={load}>重试加载</Button>} /> : <>
        <Space wrap style={{ marginBottom: 16 }}>
          <Select aria-label="只读协作成员" placeholder="选择本公司成员" showSearch optionFilterProp="label" value={selected} onChange={setSelected}
            loading={loading} disabled={loading || saving} style={{ width: 240 }}
            options={members.filter(m => m.userId !== me && !rows.some(r => r.userId === m.userId)).map(m => ({ value: m.userId, label: m.username }))}
            notFoundContent="暂无其他可授权成员" />
          <Button type="primary" disabled={selected == null || loading || saving} loading={saving} onClick={() => change(null)}>添加只读成员</Button>
          <Button disabled={saving} loading={loading} onClick={load}>刷新授权</Button>
        </Space>
        <Table size="small" rowKey="userId" dataSource={rows} loading={loading} pagination={false} locale={{ emptyText: '尚未授权其他成员' }} columns={[
          { title: '成员', dataIndex: 'username' },
          { title: '权限', render: () => '本岗位只读' },
          { title: '操作', render: (_, row) => <Popconfirm title="撤销该成员的只读权限？" description="撤销后，后续访问将被拒绝；已查看的内容无法收回。" okText="撤销授权" cancelText="取消" onConfirm={() => change(row.userId)}>
            <Button danger type="link" disabled={saving || loading}>撤销</Button>
          </Popconfirm> },
        ]} />
      </>}
    </Modal>
  </>
}
