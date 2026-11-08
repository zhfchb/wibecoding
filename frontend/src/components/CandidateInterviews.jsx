import { useState } from 'react'
import { Alert, Button, Descriptions, Modal, Popconfirm, Select, Space, Table, Tag, Typography, message } from 'antd'
import api from '../api'
import InterviewSchedule, { ScheduleInfo, interviewFlow } from './InterviewSchedule'

const states = { ASSIGNED: '待面试', DRAFT: '评价草稿', SUBMITTED: '已提交', REVOKED: '已撤销' }
export default function CandidateInterviews({ positionId, resume }) {
  const [open, setOpen] = useState(false)
  const [rows, setRows] = useState([])
  const [members, setMembers] = useState([])
  const [selected, setSelected] = useState(null)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(false)
  const [evaluation, setEvaluation] = useState(null)
  const load = async () => {
    setLoading(true); setError(false)
    try {
      const [assignments, company] = await Promise.all([api.get(`/recruitment/positions/${positionId}/interviews`), api.get('/company/members')])
      setRows(assignments.data.filter(a => a.resumeId === resume.id)); setMembers(company.data.filter(m => m.role === 'INTERVIEWER'))
    } catch { setError(true); setRows([]) }
    finally { setLoading(false) }
  }
  const mutate = async assignment => {
    setSaving(true)
    try {
      if (assignment) await api.post(`/recruitment/interviews/${assignment.id}/revoke`)
      else await api.post(`/recruitment/resumes/${resume.id}/interviews`, { interviewerId: selected })
      setSelected(null); await load(); message.success(assignment ? '面试分配已撤销' : '已分配面试官')
    } catch(e) { message.error(e.response?.data?.message || '操作失败，请重试') }
    finally { setSaving(false) }
  }
  return <>
    <Button type="link" size="small" onClick={() => { setOpen(true); load() }}>分配面试</Button>
    <Modal title={`面试分配 · ${resume.candidateName || '候选人'}`} open={open} footer={null} width={700} onCancel={() => { if (!saving) setOpen(false) }}>
      <Typography.Paragraph>面试官只查看获分配候选人的必要资料与已有面试题。评价草稿仅本人可见，提交后可在此查看；评价不自动改变HR筛选决策。历史岗位首次分配会确认当前公司归属。</Typography.Paragraph>
      {error ? <Alert type="error" message="面试分配加载失败" action={<Button onClick={load}>重试加载</Button>} /> : <>
        <Space wrap style={{ marginBottom: 16 }}>
          <Select aria-label="选择面试官" showSearch optionFilterProp="label" placeholder="选择本公司面试官" value={selected} onChange={setSelected} disabled={loading || saving} style={{ width: 240 }}
            options={members.filter(m => !rows.some(a => a.interviewerId === m.userId && a.status !== 'REVOKED')).map(m => ({ value: m.userId, label: m.username }))} notFoundContent="暂无可分配面试官，请联系公司管理员新增成员" />
          <Button type="primary" disabled={selected == null || loading || saving} loading={saving} onClick={() => mutate(null)}>确认分配</Button>
          <Button loading={loading} disabled={saving} onClick={load}>刷新分配</Button>
        </Space>
        <Table rowKey="id" size="small" loading={loading} dataSource={rows} pagination={false} scroll={{ x: 900 }} locale={{ emptyText: '尚未分配面试官' }} columns={[
          { title: '面试官', dataIndex: 'username' }, { title: '状态', dataIndex: 'status', render: s => <Tag>{states[s] || s}</Tag> },
          { title: '面试进度', dataIndex: 'flowStatus', render: value => interviewFlow[value] || '待排期' },
          { title: '时间与方式', width: 260, render: (_, row) => <><ScheduleInfo schedule={row.schedule} /><InterviewSchedule assignment={row} onChanged={load} /></> },
          { title: '评价', render: (_, row) => row.evaluation ? <Button size="small" type="link" onClick={() => setEvaluation(row.evaluation)}>查看已提交评价</Button> : row.status === 'DRAFT' ? '草稿仅面试官本人可见' : '尚未提交' },
          { title: '操作', render: (_, row) => row.status !== 'REVOKED' && <Popconfirm title="撤销面试分配？" description="后续访问与评价保存将被拒绝，已提交的评价保留。" onConfirm={() => mutate(row)} okText="撤销分配" cancelText="取消"><Button type="link" danger disabled={saving}>撤销</Button></Popconfirm> },
        ]} />
      </>}
    </Modal>
    <Modal title="已提交的面试评价" open={!!evaluation} onCancel={() => setEvaluation(null)} footer={null}>
      {evaluation && <Descriptions bordered column={1} items={[
        { key: 'score', label: '评分', children: `${evaluation.score} / 5` },
        { key: 'recommendation', label: '面试建议', children: ({ PASS: '建议通过', HOLD: '待进一步核实', NO: '建议不通过' })[evaluation.recommendation] },
        ...[['strengths', '优势证据'], ['concerns', '待核实问题'], ['summary', '评价总结']].map(([key, label]) => ({ key, label, children: <span style={{ whiteSpace: 'pre-wrap' }}>{evaluation[key] || '—'}</span> })),
      ]} />}
    </Modal>
  </>
}
