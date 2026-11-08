import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Card, Input, Select, Space, Table, Tag, Typography } from 'antd'
import api from '../api'
import CandidateOutcome from './CandidateOutcome'

const labels = { PENDING: '待定', HIRED: '录用', REJECTED: '淘汰', WITHDRAWN: '候选人退出' }
export default function RecruitmentProgress({ positionId, canOperate, revision }) {
  const [rows, setRows] = useState([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(false)
  const [query, setQuery] = useState('')
  const [result, setResult] = useState('ALL')
  const [archive, setArchive] = useState('ALL')
  const [followup, setFollowup] = useState('ALL')
  const request = useRef(0)
  const load = async () => {
    const current = ++request.current
    setBusy(true); setError(false); setRows([])
    try {
      const response = await api.get(`/recruitment/positions/${positionId}/progress`)
      if (current === request.current) setRows(response.data)
    } catch { if (current === request.current) setError(true) }
    finally { if (current === request.current) setBusy(false) }
  }
  useEffect(() => {
    setQuery(''); setResult('ALL'); setArchive('ALL'); setFollowup('ALL'); load()
    return () => { ++request.current }
  }, [positionId, revision])
  const filtered = rows.filter(r => (result === 'ALL' || r.outcome === result)
    && (archive === 'ALL' || r.archived === (archive === 'ARCHIVED'))
    && (followup === 'ALL' || (r.outcome === 'PENDING' && !r.archived && r.submittedEvaluations > 0))
    && `${r.candidateName || ''} ${r.fileName || ''}`.toLowerCase().includes(query.trim().toLowerCase()))
  return <Card type="inner" title="招聘进度总览" style={{ marginBottom: 16 }} extra={<Button loading={busy} onClick={load}>刷新进度</Button>}>
    <Typography.Paragraph type="secondary">按当前岗位的简历记录统计。同一人多份简历分别计数；已有评价仍待决定仅作跟进提示，由HR人工确认结果。</Typography.Paragraph>
    {error ? <Alert type="error" message="招聘进度加载失败" action={<Button onClick={load}>重试进度</Button>} /> : <>
      <Space wrap style={{ marginBottom: 16 }}>
        <Tag>总计 {rows.length}</Tag>
        {Object.entries(labels).map(([key, label]) => <Tag key={key}>{label} {rows.filter(r => r.outcome === key).length}</Tag>)}
        <Tag>已归档 {rows.filter(r => r.archived).length}</Tag>
      </Space>
      <Space wrap style={{ marginBottom: 16, display: 'flex' }}>
        <Input aria-label="搜索进度候选人" placeholder="搜索姓名或文件名" value={query} onChange={e => setQuery(e.target.value)} style={{ width: 210 }} allowClear />
        <Select aria-label="按招聘结果筛选" value={result} onChange={setResult} style={{ width: 150 }} options={[{ value: 'ALL', label: '全部招聘结果' }, ...Object.entries(labels).map(([value, label]) => ({ value, label }))]} />
        <Select aria-label="按归档筛选" value={archive} onChange={setArchive} style={{ width: 130 }} options={[{ value: 'ALL', label: '全部归档状态' }, { value: 'ARCHIVED', label: '已归档' }, { value: 'ACTIVE', label: '未归档' }]} />
        <Select aria-label="按跟进筛选" value={followup} onChange={setFollowup} style={{ width: 220 }} options={[{ value: 'ALL', label: '全部跟进状态' }, { value: 'FOLLOWUP', label: '已有评价、仍待决定' }]} />
      </Space>
      <Typography.Paragraph>当前筛选 {filtered.length} 条</Typography.Paragraph>
      <Table rowKey="id" loading={busy} dataSource={filtered} pagination={{ pageSize: 10, showSizeChanger: false }} scroll={{ x: 650 }} locale={{ emptyText: rows.length ? '没有符合条件的候选人' : '暂无候选人' }} columns={[
        { title: '候选人', render: (_, row) => row.candidateName || row.fileName || `简历 #${row.id}` },
        { title: '招聘结果', dataIndex: 'outcome', render: value => labels[value] || value },
        { title: '归档状态', dataIndex: 'archived', render: value => value ? '已归档' : '未归档' },
        { title: '已提交评价', dataIndex: 'submittedEvaluations' },
        { title: '结果记录', render: (_, row) => <CandidateOutcome resume={row} canOperate={canOperate} onChanged={load} /> },
      ]} />
    </>}
  </Card>
}
