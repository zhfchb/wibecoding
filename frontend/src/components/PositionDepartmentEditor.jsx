import { useRef, useState } from 'react'
import { Button, Form, Modal, Tag, message, Typography } from 'antd'
import api from '../api'
import CompanyDepartmentSelect from './CompanyDepartmentSelect'

export default function PositionDepartmentEditor({ position, onUpdated }) {
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()
  const submitting = useRef(false)
  const save = async () => {
    if (submitting.current) return
    submitting.current = true
    let values
    try { values = await form.validateFields() } catch { submitting.current = false; return }
    setSaving(true)
    try {
      const { data } = await api.put(`/recruitment/positions/${position.id}/department`, values)
      onUpdated(data); setOpen(false); message.success('岗位部门已关联')
    } catch (e) { message.error(e.response?.data?.message || '关联失败，请刷新部门后重试') }
    finally { submitting.current = false; setSaving(false) }
  }
  return <>
    {position.department || '未指定部门'}{' '}
    {!position.departmentId ? <Tag>待关联</Tag> : position.departmentStatus !== 'ACTIVE' ? <Tag color="orange">部门已停用或不可用</Tag> : null}
    <Button type="link" size="small" onClick={() => { form.resetFields(); setOpen(true) }}>{position.departmentId ? '更换部门' : '关联部门'}</Button>
    <Modal title="关联岗位部门" open={open} onCancel={() => { if (!saving) setOpen(false) }} onOk={save} confirmLoading={saving} okText="确认关联" cancelText="取消" destroyOnHidden>
      <Typography.Paragraph>当前部门：{position.department || '未指定'}。请核对后选择本公司部门，原有岗位和候选人访问权限保持不变。</Typography.Paragraph>
      <Form form={form} layout="vertical">
        <Form.Item name="departmentId" label="公司部门" rules={[{ required: true, message: '请选择已启用的公司部门' }]}>
          {open && <CompanyDepartmentSelect refreshKey={open} placeholder="选择已启用的公司部门" />}
        </Form.Item>
      </Form>
    </Modal>
  </>
}
