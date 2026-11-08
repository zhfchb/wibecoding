import { useState } from 'react'
import { Alert, Button, Form, Input, Modal, Select, Space, Table, Typography, message } from 'antd'
import api from '../api'

export const interviewFlow = { UNSCHEDULED: '待排期', UPCOMING: '已排期', IN_PROGRESS: '面试进行中', AWAITING_CONFIRMATION: '待确认结果', AWAITING_EVALUATION: '待提交评价', EVALUATED: '已评价', CANCELLED: '排期已取消', NO_SHOW: '未到场', REVOKED: '分配已撤销' }
const states = { SCHEDULED: '已排期', CANCELLED: '已取消', COMPLETED: '已完成', NO_SHOW: '未到场' }
const modes = { VIDEO: '线上视频', ONSITE: '现场面试', PHONE: '电话面试' }
export function ScheduleInfo({ schedule }) {
  if (!schedule) return <Typography.Text type="secondary">尚未安排时间</Typography.Text>
  return <Space direction="vertical" size={2}>
    <span>{schedule.localStart?.replace('T', ' ')} — {schedule.localEnd?.replace('T', ' ')}</span>
    <Typography.Text type="secondary">{schedule.timeZone} · {modes[schedule.mode] || schedule.mode} · {states[schedule.status] || schedule.status}</Typography.Text>
    <span style={{ overflowWrap: 'anywhere' }}>{schedule.location}</span>
    {schedule.meetingUrl && <Typography.Link href={schedule.meetingUrl} target="_blank" rel="noopener noreferrer">打开会议链接</Typography.Link>}
    {schedule.reason && <Typography.Text type="secondary">变更说明：{schedule.reason}</Typography.Text>}
  </Space>
}

export default function InterviewSchedule({ assignment, onChanged }) {
  const [open, setOpen] = useState(false)
  const [history, setHistory] = useState(null)
  const [historyLoading, setHistoryLoading] = useState(false)
  const [historyError, setHistoryError] = useState(false)
  const [saving, setSaving] = useState(false)
  const [conflict, setConflict] = useState(false)
  const [version, setVersion] = useState(0)
  const [form] = Form.useForm()
  const schedule = assignment.schedule
  const finalized = assignment.status === 'SUBMITTED' || assignment.status === 'REVOKED' || ['COMPLETED', 'NO_SHOW'].includes(schedule?.status)
  const show = () => {
    setVersion(schedule?.version || 0); setConflict(false); form.resetFields()
    form.setFieldsValue({ localStart: schedule?.localStart?.slice(0, 16), localEnd: schedule?.localEnd?.slice(0, 16), timeZone: schedule?.timeZone || 'Asia/Shanghai', mode: schedule?.mode || 'VIDEO', location: schedule?.location, meetingUrl: schedule?.meetingUrl, reason: '' })
    setOpen(true)
  }
  const loadHistory = async () => {
    setHistoryLoading(true); setHistoryError(false); setHistory([])
    try { setHistory((await api.get(`/recruitment/interviews/${assignment.id}/schedule/history`)).data) }
    catch { setHistoryError(true) }
    finally { setHistoryLoading(false) }
  }
  const change = async action => {
    let values
    if (!action) { try { values = await form.validateFields() } catch { return } }
    else values = form.getFieldsValue()
    setSaving(true)
    try {
      if (action) await api.post(`/recruitment/interviews/${assignment.id}/schedule/state`, { version, action, reason: values.reason })
      else await api.put(`/recruitment/interviews/${assignment.id}/schedule`, { ...values, localStart: `${values.localStart.slice(0, 16)}:00`, localEnd: `${values.localEnd.slice(0, 16)}:00`, version })
      await onChanged(); setOpen(false); message.success('面试排期已更新')
    } catch(e) {
      if (e.response?.data?.error === 'SCHEDULE_VERSION') setConflict(true)
      message.error(!e.response || e.response.status >= 500 ? '保存结果尚未确认，请关闭并刷新排期核对' : e.response?.data?.message || '排期更新失败')
    } finally { setSaving(false) }
  }
  const ended = schedule && Date.parse(schedule.endAt) <= Date.now()
  return <>
    <Space wrap>
      {!finalized && <Button type="link" size="small" onClick={show}>{schedule ? '调整排期' : '安排时间'}</Button>}
      {schedule && <Button type="link" size="small" onClick={loadHistory}>排期记录</Button>}
    </Space>
    <Modal title={`面试排期 · ${assignment.username}`} open={open} width={640} onCancel={() => { if (!saving) setOpen(false) }} footer={<Space wrap>
      <Button disabled={saving} onClick={async () => { await onChanged(); setOpen(false) }}>关闭并刷新排期</Button>
      <Button type="primary" loading={saving} disabled={conflict || finalized} onClick={() => change(null)}>保存排期</Button>
      {schedule?.status === 'SCHEDULED' && <>
        <Button danger disabled={saving || conflict} onClick={() => change('CANCEL')}>取消排期</Button>
        <Button disabled={saving || conflict || !ended} onClick={() => change('COMPLETE')}>确认已完成</Button>
        <Button disabled={saving || conflict || !ended} onClick={() => change('NO_SHOW')}>标记未到场</Button>
      </>}
    </Space>}>
      <Typography.Paragraph type="secondary">时间按所选时区解释，系统会检查面试官及该候选人的时间冲突。此处只更新站内安排，不发送邀请。改期、取消、未到场须说明原因；结束后可确认完成或未到场，确认后不能改期。</Typography.Paragraph>
      {conflict && <Alert type="warning" message="排期已变化，请关闭并刷新后重新核对，当前输入尚未覆盖服务器" />}
      <Form form={form} layout="vertical" disabled={saving || conflict}>
        <Form.Item name="timeZone" label="排期时区" rules={[{ required: true }]}><Select showSearch options={['Asia/Shanghai', 'Asia/Hong_Kong', 'Asia/Tokyo', 'UTC', 'Europe/London', 'America/New_York', 'America/Los_Angeles'].map(value => ({ value, label: value }))} /></Form.Item>
        <Space wrap align="start"><Form.Item name="localStart" label="开始时间" rules={[{ required: true }]}><Input type="datetime-local" step={60} /></Form.Item><Form.Item name="localEnd" label="结束时间" rules={[{ required: true }]}><Input type="datetime-local" step={60} /></Form.Item></Space>
        <Form.Item name="mode" label="面试方式" rules={[{ required: true }]}><Select options={Object.entries(modes).map(([value, label]) => ({ value, label }))} /></Form.Item>
        <Form.Item name="location" label="地点 / 会议号 / 电话说明" rules={[{ required: true, whitespace: true }, { max: 500 }]}><Input maxLength={500} /></Form.Item>
        <Form.Item name="meetingUrl" label="会议链接（选填）"><Input maxLength={1000} placeholder="https://..." /></Form.Item>
        <Form.Item name="reason" label="变更原因（改期、取消、未到场必填）"><Input.TextArea rows={2} maxLength={500} showCount /></Form.Item>
      </Form>
    </Modal>
    <Modal title="排期变更记录" open={history !== null} width={760} onCancel={() => setHistory(null)} footer={<Button loading={historyLoading} onClick={loadHistory}>刷新排期记录</Button>}>
      {historyError ? <Alert type="warning" message="排期记录读取失败，请重试或确认权限" /> : <Table rowKey="id" size="small" loading={historyLoading} dataSource={history || []} scroll={{ x: 650 }} columns={[
        { title: '版本', dataIndex: 'version' }, { title: '状态', dataIndex: 'status', render: value => states[value] || value },
        { title: '时间与时区', render: (_, row) => <span>{row.startAt} — {row.endAt}<br />{row.timeZone}（时间为UTC）</span> },
        { title: '操作人', dataIndex: 'username' }, { title: '原因', dataIndex: 'reason' },
      ]} />}
    </Modal>
  </>
}
