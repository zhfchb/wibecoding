import { useEffect, useState } from 'react'
import { Alert, Button, Card, Form, Input, message, Modal, Space, Table, Tag, Select, Row, Col, Divider } from 'antd'
import api from '../api'

export default function CompanyPage({ members = false }) {
  const [company, setCompany] = useState(null)
  const [options, setOptions] = useState(null)
  const [rows, setRows] = useState([])
  const [error, setError] = useState(false)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [open, setOpen] = useState(false)
  const [form] = Form.useForm()
  const [memberForm] = Form.useForm()
  const province = Form.useWatch(['profile', 'provinceCode'], form)
  const cities = options?.regions.find(r => r.value === province)?.children || []
  const load = async () => {
    setLoading(true); setError(false)
    try {
      const { data } = await api.get('/company')
      if (!members) setOptions((await api.get('/company/options')).data)
      setCompany(data); form.setFieldsValue({ ...data, profile: data.profile || {} })
      if (members) setRows((await api.get('/company/members')).data)
    } catch { setError(true) }
    finally { setLoading(false) }
  }
  useEffect(() => { load() }, [members])
  const save = async values => {
    setSaving(true)
    try { setCompany((await api.put('/company', values)).data); message.success('公司资料已保存') }
    catch (e) { message.error(e.response?.data?.message || '保存失败') }
    finally { setSaving(false) }
  }
  const add = async () => {
    let values
    try { values = await memberForm.validateFields() } catch { return }
    setSaving(true)
    try { await api.post('/company/members', values); setOpen(false); memberForm.resetFields(); load(); message.success('HR账号已创建') }
    catch (e) { message.error(e.response?.data?.message || '创建失败') }
    finally { setSaving(false) }
  }
  const admin = company?.memberRole === 'ADMIN'
  return <Card title={members ? '成员管理' : '公司资料'} loading={loading}
    extra={<Space><Button onClick={load}>刷新</Button>{members && admin && <Button type="primary" onClick={() => setOpen(true)}>新增HR成员</Button>}</Space>}>
    {error ? <Alert type="error" message="无法加载公司资料，请重试" action={<Button onClick={load}>重试</Button>} /> : company && <>
      <Alert type="info" style={{ marginBottom: 20 }} message="当前阶段已支持公司资料与成员管理；招聘资料仍按原账号隔离，公司内共享将在后续阶段开放。" />
      {members ? <Table rowKey="userId" dataSource={rows} pagination={false} columns={[
        { title: '账号', dataIndex: 'username' }, { title: '公司角色', dataIndex: 'role', render: role => <Tag>{role === 'ADMIN' ? '公司管理员' : 'HR'}</Tag> },
      ]} /> : <Form form={form} layout="vertical" onFinish={save} disabled={!admin} onValuesChange={changed => {
        if (changed.profile && Object.prototype.hasOwnProperty.call(changed.profile, 'provinceCode')) form.setFieldValue(['profile', 'cityCode'], undefined)
      }}>
        {company.needsCompletion && <Alert style={{ marginBottom: 20 }} type="warning" showIcon message="公司资料待完善，请补齐必填信息后保存。"
          description={company.city && !company.profile?.cityCode ? `原所在地：${company.city}，请在省市选项中重新确认。` : undefined} />}
        <Divider orientation="left">基本信息</Divider>
        <Row gutter={24}>
          <Col xs={24} md={12}><Form.Item name="name" label="公司全称" rules={[{ required: true, whitespace: true, message: '请输入公司全称' }]}><Input maxLength={100} /></Form.Item></Col>
          <Col xs={24} md={12}><Form.Item name={['profile', 'shortName']} label="公司简称（选填）"><Input maxLength={100} /></Form.Item></Col>
          <Col xs={24} md={12}><Form.Item name={['profile', 'industry']} label="所属行业" rules={[{ required: true, message: '请选择行业' }]}><Select showSearch optionFilterProp="label" options={options?.industries.map(value => ({ value, label: value }))} /></Form.Item></Col>
          <Col xs={24} md={12}><Form.Item name={['profile', 'scale']} label="公司规模" rules={[{ required: true, message: '请选择规模' }]}><Select options={options?.scales.map(value => ({ value, label: value }))} /></Form.Item></Col>
          <Col xs={24} md={12}><Form.Item name={['profile', 'nature']} label="公司性质（选填）"><Select allowClear options={options?.natures.map(value => ({ value, label: value }))} /></Form.Item></Col>
          <Col xs={24} md={12}><Form.Item name={['profile', 'website']} label="公司官网（选填）" rules={[{ type: 'url', message: '请输入完整的http或https网址' }, { validator: (_, value) => !value || /^https?:\/\//i.test(value) ? Promise.resolve() : Promise.reject(new Error('仅支持http或https网址')) }]}><Input maxLength={500} placeholder="https://example.com" /></Form.Item></Col>
        </Row>
        <Divider orientation="left">所在地与联系方式</Divider>
        <Row gutter={24}>
          <Col xs={24} md={12}><Form.Item name={['profile', 'provinceCode']} label="省份 / 自治区 / 直辖市" rules={[{ required: true, message: '请选择省份' }]}><Select showSearch optionFilterProp="label" allowClear options={options?.regions.map(({ value, label }) => ({ value, label }))} /></Form.Item></Col>
          <Col xs={24} md={12}><Form.Item name={['profile', 'cityCode']} label="城市" rules={[{ required: true, message: '请选择城市' }]}><Select showSearch optionFilterProp="label" allowClear disabled={!admin || !province} options={cities.map(({ value, label }) => ({ value, label }))} placeholder={cities.length === 1 && cities[0].label === options?.regions.find(r => r.value === province)?.label ? '请选择直辖市' : '请先选择省份'} /></Form.Item></Col>
          <Col span={24}><Form.Item name={['profile', 'address']} label="详细地址（选填）"><Input maxLength={500} placeholder="街道、园区、楼宇等" /></Form.Item></Col>
          <Col xs={24} md={12}><Form.Item name={['profile', 'contactName']} label="招聘联系人（选填）"><Input maxLength={100} /></Form.Item></Col>
          <Col xs={24} md={12}><Form.Item name={['profile', 'contactPhone']} label="招聘联系电话（选填）" rules={[{ pattern: /^[+0-9()（）\-\s]{5,30}$/, message: '请输入有效联系电话' }]}><Input maxLength={30} /></Form.Item></Col>
          <Col xs={24} md={12}><Form.Item name={['profile', 'contactEmail']} label="招聘联系邮箱（选填）" rules={[{ type: 'email', message: '请输入有效邮箱' }]}><Input maxLength={254} /></Form.Item></Col>
        </Row>
        <Divider orientation="left">公司介绍</Divider>
        <Form.Item name="description" label="公司简介" rules={[{ required: true, whitespace: true, message: '请介绍主营业务、产品或团队' }]}><Input.TextArea rows={5} maxLength={5000} showCount /></Form.Item>
        {admin && <Button type="primary" htmlType="submit" loading={saving}>保存公司资料</Button>}
      </Form>}
    </>}
    <Modal title="新增HR账号" open={open} onCancel={() => { setOpen(false); memberForm.resetFields() }} onOk={add} confirmLoading={saving} okText="创建" cancelText="取消">
      <Form form={memberForm} layout="vertical" autoComplete="off">
        <Form.Item name="username" label="账号" rules={[{ required: true }, { pattern: /^[A-Za-z0-9_-]{3,32}$/, message: '3～32位字母数字、下划线或连字符' }]}><Input autoComplete="off" /></Form.Item>
        <Form.Item name="password" label="初始密码" rules={[{ required: true, min: 8, max: 64, message: '请输入8～64位初始密码' }]}><Input.Password autoComplete="new-password" /></Form.Item>
      </Form>
    </Modal>
  </Card>
}
