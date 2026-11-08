import { useState } from 'react'
import { Alert, Button, Input, Modal, Select, Space, Table, Tag, Typography, message } from 'antd'
import api from '../api'

const outcomes = { PENDING: '待定', HIRED: '录用', REJECTED: '淘汰', WITHDRAWN: '候选人退出' }
const actions = { DECIDE: '修改结果', ARCHIVE: '归档', REOPEN: '重新开启' }
export default function CandidateOutcome({ resume, canOperate, onChanged }) {
  const [open, setOpen] = useState(false)
  const [state, setState] = useState(null)
  const [history, setHistory] = useState([])
  const [error, setError] = useState(false)
  const [busy, setBusy] = useState(false)
  const [stale, setStale] = useState(false)
  const [outcome, setOutcome] = useState('PENDING')
  const [reason, setReason] = useState('')
  const load = async () => {
    setBusy(true); setError(false); setState(null)
    try {
      const [current, events] = await Promise.all([api.get(`/recruitment/resumes/${resume.id}/outcome`), api.get(`/recruitment/resumes/${resume.id}/outcome/history`)])
      setState(current.data); setHistory(events.data); setOutcome(current.data.outcome); setReason(''); setStale(false)
    } catch { setError(true) } finally { setBusy(false) }
  }
  const change = async action => {
    if (!reason.trim()) { message.error('请填写操作原因'); return }
    setBusy(true)
    try {
      await api.post(`/recruitment/resumes/${resume.id}/outcome`, { version: state.version, action, outcome, reason })
      message.success('招聘结果已保存'); await load(); onChanged?.()
    } catch (e) {
      if (e.response?.status === 409 || !e.response || e.response.status >= 500) setStale(true)
      message.error(e.response?.data?.message || '保存结果未确认，请刷新后核对')
    } finally { setBusy(false) }
  }
  return <>
    <Button type="link" size="small" onClick={() => { setOpen(true); load() }}>招聘结果</Button>
    <Modal title={`招聘结果 · ${resume.candidateName || '候选人'}`} open={open} width={760} footer={null} onCancel={() => !busy && setOpen(false)}>
      <Typography.Paragraph>人工记录最终招聘结果，与初筛及面试建议分别保存。归档仅锁定结果记录，不会取消面试、后台任务或删除资料。</Typography.Paragraph>
      {error && <Alert type="error" message="招聘结果加载失败" action={<Button onClick={load}>重试</Button>} />}
      {stale && <Alert type="warning" message="当前版本可能已变化，输入已保留。请刷新并核对结果后重新操作。" action={<Button disabled={busy} onClick={load}>刷新结果</Button>} />}
      {state && <>
        <Space style={{ marginBottom: 16 }}><Tag>{outcomes[state.outcome]}</Tag><Tag>{state.archived ? '已归档' : '未归档'}</Tag><span>版本 {state.version}</span></Space>
        {canOperate && <Space direction="vertical" style={{ width: '100%', marginBottom: 20 }}>
          <Select aria-label="最终招聘结果" value={outcome} onChange={setOutcome} disabled={busy || stale || state.archived} style={{ width: 200 }} options={Object.entries(outcomes).map(([value, label]) => ({ value, label }))} />
          <Input.TextArea aria-label="招聘结果操作原因" placeholder="填写本次决定、归档或重新开启的原因（必填）" value={reason} onChange={e => setReason(e.target.value)} maxLength={2000} showCount rows={3} disabled={busy || stale} />
          <Space wrap>
            {!state.archived && <Button type="primary" disabled={busy || stale || outcome === state.outcome} onClick={() => change('DECIDE')}>保存招聘结果</Button>}
            <Button disabled={busy || stale || (!state.archived && state.outcome === 'PENDING')} onClick={() => change(state.archived ? 'REOPEN' : 'ARCHIVE')}>{state.archived ? '重新开启记录' : '归档结果记录'}</Button>
          </Space>
        </Space>}
        <Table size="small" rowKey="version" dataSource={history} pagination={{ pageSize: 5 }} scroll={{ x: 600 }} columns={[
          { title: '版本', dataIndex: 'version' }, { title: '操作', dataIndex: 'action', render: a => actions[a] || a },
          { title: '结果', dataIndex: 'outcome', render: o => outcomes[o] || o },
          { title: '归档', dataIndex: 'archived', render: a => a ? '是' : '否' },
          { title: '原因', dataIndex: 'reason', render: text => <span style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{text}</span> },
          { title: '操作人', dataIndex: 'username' }, { title: '时间（UTC）', dataIndex: 'changedAt' },
        ]} />
      </>}
    </Modal>
  </>
}
