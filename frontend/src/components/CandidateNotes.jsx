import { useRef, useState } from 'react'
import { Alert, Button, Input, Modal, Select, Space, Table, Typography, message } from 'antd'
import api from '../api'

const labels = { FOLLOWUP: '沟通跟进', REVIEW_SUMMARY: '负责人汇总意见' }
export default function CandidateNotes({ resume, canOperate, canManage }) {
  const [open, setOpen] = useState(false)
  const [data, setData] = useState(null)
  const [error, setError] = useState(false)
  const [busy, setBusy] = useState(false)
  const [saving, setSaving] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const [kind, setKind] = useState('FOLLOWUP')
  const [content, setContent] = useState('')
  const [next, setNext] = useState('')
  const pending = useRef(null)
  const inFlight = useRef(false)
  const load = async (page = 1) => {
    setBusy(true); setError(false)
    try { setData((await api.get(`/recruitment/resumes/${resume.id}/notes`, { params: { page, size: 10 } })).data) }
    catch { setData(null); setError(true) }
    finally { setBusy(false) }
  }
  const save = async () => {
    if (inFlight.current) return
    if (!pending.current) {
      if (!content.trim()) { message.warning('请填写记录内容'); return }
      if (next && (!Number.isFinite(Date.parse(next)) || Date.parse(next) <= Date.now())) { message.warning('下次跟进时间须晚于当前时间'); return }
      pending.current = { kind, content: content.trim(), nextContactAt: kind === 'FOLLOWUP' && next ? new Date(next).toISOString() : null,
        requestKey: globalThis.crypto?.randomUUID?.() || `note_${Date.now()}_${Math.random().toString(36).slice(2)}` }
    }
    inFlight.current = true; setSaving(true)
    try {
      await api.post(`/recruitment/resumes/${resume.id}/notes`, pending.current)
      pending.current = null; setUncertain(false); setContent(''); setNext('')
      message.success('记录已保存'); await load()
    } catch (e) {
      if (!e.response || e.response.status >= 500) { setUncertain(true); message.warning('保存结果待确认，重试会复用原记录标识，请勿重复新增') }
      else { pending.current = null; setUncertain(false); message.error(e.response.data?.message || '无权保存或记录已变化') }
    } finally { inFlight.current = false; setSaving(false) }
  }
  return <>
    <Button type="link" size="small" onClick={() => { setOpen(true); load() }}>跟进与汇总</Button>
    <Modal title={`跟进与汇总 · ${resume.candidateName || resume.fileName || '候选人'}`} width={840} open={open} footer={null} onCancel={() => !saving && setOpen(false)}>
      <Typography.Paragraph>记录沟通过程和下次行动；岗位负责人可结合已提交的面试评价补充汇总意见。记录追加保存，不覆盖历史，不自动修改招聘结果。</Typography.Paragraph>
      {uncertain && <Alert type="warning" message="上次保存结果待确认。重试确认会复用原请求，不新增重复记录。" />}
      {canOperate && <Space direction="vertical" style={{ width: '100%', marginBottom: 20 }}>
        <Select aria-label="候选人记录类型" value={kind} disabled={saving || uncertain} onChange={value => { setKind(value); setNext('') }} style={{ width: 210 }} options={Object.entries(labels).filter(([value]) => canManage || value === 'FOLLOWUP').map(([value, label]) => ({ value, label }))} />
        <Input.TextArea aria-label="候选人跟进内容" placeholder={kind === 'FOLLOWUP' ? '例如：已沟通意向，等待候选人确认面试时间；记录实际发生的沟通。' : '综合已提交评价，写明共识、分歧及仍需核实的证据。最终决定另在招聘结果中记录。'} value={content} onChange={e => setContent(e.target.value)} rows={4} maxLength={3000} showCount disabled={saving || uncertain} />
        {kind === 'FOLLOWUP' && <><Typography.Text type="secondary">下次跟进时间（选填，{Intl.DateTimeFormat().resolvedOptions().timeZone}；仅记录，不发送提醒）</Typography.Text><Input aria-label="下次跟进时间" type="datetime-local" value={next} onChange={e => setNext(e.target.value)} disabled={saving || uncertain} style={{ width: 260 }} /></>}
        <Button type="primary" loading={saving} onClick={save}>{uncertain ? '重试确认保存' : '保存候选人记录'}</Button>
      </Space>}
      <Space style={{ marginBottom: 12 }}><Typography.Text strong>历史记录</Typography.Text><Button loading={busy} onClick={() => load(data?.current || 1)}>刷新记录</Button></Space>
      {error ? <Alert type="error" message="候选人记录加载失败，请重试或确认岗位权限" /> : <Table size="small" rowKey="id" loading={busy} dataSource={data?.records || []} scroll={{ x: 650 }} pagination={{ current: data?.current || 1, pageSize: 10, total: data?.total || 0, showSizeChanger: false, onChange: page => load(page) }} columns={[
        { title: '类型', dataIndex: 'kind', render: value => labels[value] || value },
        { title: '内容', dataIndex: 'content', render: value => <span style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{value}</span> },
        { title: '下次跟进', dataIndex: 'nextContactAt', render: value => value ? new Date(value).toLocaleString('zh-CN') : '—' },
        { title: '记录人', dataIndex: 'username' }, { title: '记录时间', dataIndex: 'createdAt', render: value => new Date(value).toLocaleString('zh-CN') },
      ]} />}
    </Modal>
  </>
}
