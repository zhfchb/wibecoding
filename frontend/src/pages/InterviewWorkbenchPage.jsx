import { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, Button, Card, Descriptions, Empty, Form, Input, InputNumber, Modal, Select, Space, Spin, Table, Tag, Typography, message } from 'antd'
import api from '../api'
import PageIntro from '../components/PageIntro'

const labels = { ASSIGNED: '待面试', DRAFT: '评价草稿', SUBMITTED: '已提交' }
export default function InterviewWorkbenchPage() {
  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [detail, setDetail] = useState(null)
  const [opening, setOpening] = useState(false)
  const [saving, setSaving] = useState(false)
  const [conflict, setConflict] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [form] = Form.useForm()
  const sequence = useRef(0)
  const inFlight = useRef(false)
  const load = useCallback(async () => {
    setLoading(true); setError('')
    try { setRows((await api.get('/interviews/assignments')).data) }
    catch { setRows([]); setError('面试安排加载失败，请重试') }
    finally { setLoading(false) }
  }, [])
  useEffect(() => { load() }, [load])
  const show = async id => {
    const current = ++sequence.current
    setOpening(true)
    try {
      const { data } = await api.get(`/interviews/assignments/${id}`)
      if (current !== sequence.current) return
      setDetail(data); form.resetFields(); form.setFieldsValue(data.assignment.evaluation || {}); setDirty(false); setConflict(false)
    } catch {
      if (current !== sequence.current) return
      setDetail(null); await load(); setError('面试分配不可访问或已撤销，请刷新列表')
    }
    finally { if (current === sequence.current) setOpening(false) }
  }
  const close = () => {
    if (saving) return
    const leave = () => { sequence.current++; setDetail(null); setDirty(false) }
    if (dirty) Modal.confirm({ title: '放弃未保存的评价内容？', content: '已保存的草稿不会丢失。', okText: '放弃编辑', cancelText: '继续编辑', onOk: leave })
    else leave()
  }
  useEffect(() => {
    if (!detail) return
    let active = true
    const timer = setInterval(async () => {
      try {
        const { data } = await api.get(`/interviews/assignments/${detail.assignment.id}`)
        if (active && data.assignment.version !== detail.assignment.version) setConflict(true)
      } catch (e) {
        if (active && [403, 404].includes(e.response?.status)) { sequence.current++; setDetail(null); setDirty(false); load(); message.warning('面试分配已撤销或不可访问') }
      }
    }, 10000)
    return () => { active = false; clearInterval(timer) }
  }, [detail?.assignment.id, detail?.assignment.version, load])
  const save = async submit => {
    if (inFlight.current) return
    let values
    try { values = await form.validateFields() } catch { return }
    if (submit && (values.score == null || !values.recommendation || !values.summary?.trim())) { message.warning('提交前请填写评分、建议与评价总结'); return }
    inFlight.current = true; setSaving(true)
    try {
      const { data } = await api.put(`/interviews/assignments/${detail.assignment.id}/evaluation`, { ...values, version: detail.assignment.version, submit })
      setDetail(data); setDirty(false); setConflict(false); load(); message.success(submit ? '评价已提交，提交后不能修改' : '评价草稿已保存')
    } catch (e) {
      if ([403, 404].includes(e.response?.status)) { sequence.current++; setDetail(null); setDirty(false); await load(); message.warning('面试分配已撤销或不可访问') }
      else if (e.response?.status === 409) { setConflict(true); message.warning('评价版本已变化或已提交，请重新加载确认') }
      else message.error(e.response?.data?.message || '保存结果尚未确认，请重新加载核对')
    } finally { inFlight.current = false; setSaving(false) }
  }
  const submitted = detail?.assignment.status === 'SUBMITTED'
  return <div>
    <PageIntro eyebrow="INTERVIEW WORKSPACE" title="我的面试安排">围绕候选人经历与岗位要求独立评价。草稿仅本人可见；提交后供招聘负责人参考，不自动改变筛选决策。</PageIntro>
    <Card title={`获分配的面试（${rows.length}）`} extra={<Button loading={loading} onClick={load}>刷新安排</Button>}>
      {error && <Alert type="warning" message={error} style={{ marginBottom: 16 }} />}
      <Table rowKey="id" loading={loading} dataSource={rows} scroll={{ x: 600 }} locale={{ emptyText: <Empty description="暂无面试安排，请联系招聘负责人分配候选人" /> }} columns={[
        { title: '岗位', dataIndex: 'title' }, { title: '候选人', dataIndex: 'candidateName' },
        { title: '状态', dataIndex: 'status', render: s => <Tag>{labels[s] || s}</Tag> },
        { title: '操作', render: (_, row) => <Button type="link" loading={opening} onClick={() => show(row.id)}>{row.status === 'SUBMITTED' ? '查看评价' : '准备面试与评价'}</Button> },
      ]} />
    </Card>
    <Modal title={`面试准备 · ${detail?.candidate.name || ''}`} open={!!detail} onCancel={close} width={800} footer={detail && <Space wrap>
      <Button disabled={saving} onClick={() => dirty ? Modal.confirm({ title: '重新加载会丢弃未保存的内容，是否继续？', onOk: () => show(detail.assignment.id), okText: '重新加载', cancelText: '取消' }) : show(detail.assignment.id)}>重新加载</Button>
      {!submitted && <><Button disabled={saving || conflict} onClick={() => save(false)}>保存草稿</Button><Button type="primary" disabled={saving || conflict} loading={saving} onClick={() => save(true)}>提交评价</Button></>}
    </Space>}>
      {detail && <Spin spinning={opening}>
        {conflict && <Alert type="warning" showIcon message="评价已有更新，请重新加载后继续" style={{ marginBottom: 16 }} />}
        {submitted && <Alert type="success" message="评价已提交，不能修改" style={{ marginBottom: 16 }} />}
        <Descriptions bordered size="small" column={1} items={[
          { key: 'position', label: '岗位', children: detail.position.title },
          { key: 'requirements', label: '岗位要求', children: `${detail.position.requiredSkills.join('、')} · ${detail.position.minYears || 0}年以上 · ${detail.position.education || '学历不限'}` },
          { key: 'candidate', label: '候选人', children: `${detail.candidate.name} · ${detail.candidate.yearsExp ?? '待核实'}年 · ${detail.candidate.education || '学历待核实'}` },
          { key: 'skills', label: '候选人技能', children: detail.candidate.skills.join('、') || '待核实' },
          { key: 'jd', label: '岗位描述', children: detail.position.description || '未填写' },
        ]} />
        <Typography.Title level={5}>已有面试题</Typography.Title>
        {detail.questions.length ? detail.questions.map((q, index) => <Card key={index} size="small" style={{ marginBottom: 8 }} title={`${index + 1}. ${q.category || '面试问题'}`}><Typography.Paragraph>{q.question}</Typography.Paragraph><Typography.Text type="secondary">考察：{q.assessPoint || '待确认'}；参考：{q.referenceAnswer || '未提供'}</Typography.Text></Card>) : <Empty description="招聘负责人尚未准备面试题" />}
        <Typography.Title level={5}>面试评价</Typography.Title>
        <Typography.Paragraph type="secondary">草稿仅本人可见；提交后评价将锁定，并供招聘负责人查看。</Typography.Paragraph>
        <Form form={form} layout="vertical" disabled={submitted || saving || conflict} onValuesChange={() => setDirty(true)}>
          <Space wrap align="start"><Form.Item name="score" label="综合评分（1～5）"><InputNumber min={1} max={5} precision={0} /></Form.Item><Form.Item name="recommendation" label="面试建议"><Select style={{ width: 180 }} allowClear options={[{ value: 'PASS', label: '建议通过' }, { value: 'HOLD', label: '待进一步核实' }, { value: 'NO', label: '建议不通过' }]} /></Form.Item></Space>
          {[['strengths', '优势证据'], ['concerns', '待核实问题'], ['summary', '评价总结（提交时必填）']].map(([name, label]) => <Form.Item key={name} name={name} label={label} rules={[{ max: 3000 }]}><Input.TextArea rows={3} maxLength={3000} showCount /></Form.Item>)}
        </Form>
      </Spin>}
    </Modal>
  </div>
}
