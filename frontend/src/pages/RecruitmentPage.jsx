import { useState, useEffect } from 'react'
import {
  Card, Table, Button, message, Space, Tag, Popconfirm, Modal, Form,
  Input, InputNumber, Select, Upload, Typography, Empty,
} from 'antd'
import {
  PlusOutlined, ReloadOutlined, DeleteOutlined, UploadOutlined,
  ArrowLeftOutlined, TeamOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { recruitmentApi } from '../api'

const { Text } = Typography

// 创建岗位表单
function PositionFormModal({ open, onClose, onCreated }) {
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)

  const handleOk = async () => {
    const values = await form.validateFields()
    setLoading(true)
    try {
      const resp = await recruitmentApi.createPosition(values)
      message.success(`岗位已创建：${resp.data.title}`)
      form.resetFields()
      onCreated(resp.data)
      onClose()
    } catch (err) {
      const errMsg = err.response?.data?.message || err.message || '创建失败'
      message.error(`创建失败：${errMsg}`)
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal
      title="创建招聘岗位"
      open={open}
      onOk={handleOk}
      onCancel={onClose}
      confirmLoading={loading}
      okText="创建"
      cancelText="取消"
      width={640}
      destroyOnClose
    >
      <Form form={form} layout="vertical" initialValues={{ minYears: 3 }}>
        <Form.Item name="title" label="岗位名称" rules={[{ required: true, message: '请输入岗位名称' }]}>
          <Input placeholder="如：Java 后端工程师" />
        </Form.Item>
        <Space size="middle" style={{ display: 'flex' }}>
          <Form.Item name="department" label="部门" style={{ width: 190 }}>
            <Input placeholder="技术部" />
          </Form.Item>
          <Form.Item name="city" label="城市" style={{ width: 190 }}>
            <Input placeholder="上海" />
          </Form.Item>
          <Form.Item label="薪资 (k)" required style={{ width: 200 }}>
            <Space.Compact>
              <Form.Item name="salaryMin" noStyle rules={[{ required: true, message: '下限' }]}>
                <InputNumber min={0} placeholder="下限" style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="salaryMax" noStyle rules={[{ required: true, message: '上限' }]}>
                <InputNumber min={0} placeholder="上限" style={{ width: '100%' }} />
              </Form.Item>
            </Space.Compact>
          </Form.Item>
        </Space>
        <Form.Item
          name="requiredSkills"
          label="必须技能"
          rules={[{ required: true, message: '请输入至少一个必须技能' }]}
        >
          <Select mode="tags" placeholder="输入后回车添加，如 Java、Spring Boot" />
        </Form.Item>
        <Form.Item name="preferredSkills" label="加分技能">
          <Select mode="tags" placeholder="输入后回车添加，如 Docker、K8s" />
        </Form.Item>
        <Space size="middle" style={{ display: 'flex' }}>
          <Form.Item name="minYears" label="最低年限" style={{ width: 190 }}>
            <InputNumber min={0} max={30} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="education" label="学历要求" style={{ width: 190 }}>
            <Select
              allowClear
              options={[
                { value: '大专' }, { value: '本科' }, { value: '硕士' }, { value: '博士' },
              ]}
            />
          </Form.Item>
        </Space>
        <Form.Item name="description" label="JD 描述">
          <Input.TextArea rows={3} placeholder="岗位职责与要求（可选）" />
        </Form.Item>
      </Form>
    </Modal>
  )
}

// JD 文档导入
function ImportJdModal({ open, onClose, onImported }) {
  const [file, setFile] = useState(null)
  const [loading, setLoading] = useState(false)

  const handleOk = async () => {
    if (!file) return message.warning('请先选择 JD 文档')
    setLoading(true)
    try {
      const resp = await recruitmentApi.importPosition(file)
      message.success(`JD 解析成功，已创建岗位：${resp.data.title}`)
      onImported(resp.data)
      setFile(null)
      onClose()
    } catch (err) {
      const errMsg = err.response?.data?.message || err.message || '导入失败'
      message.error(`JD 导入失败：${errMsg}`)
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal
      title="上传 JD 导入岗位"
      open={open}
      onOk={handleOk}
      onCancel={onClose}
      confirmLoading={loading}
      okText="解析并创建"
      cancelText="取消"
      destroyOnClose
    >
      <Upload.Dragger
        accept=".pdf,.txt,.md,.markdown"
        maxCount={1}
        beforeUpload={() => false}
        onChange={(info) => setFile(info.fileList[0]?.originFileObj || null)}
        onRemove={() => setFile(null)}
      >
        <p className="ant-upload-drag-icon"><UploadOutlined /></p>
        <p className="ant-upload-text">点击或拖拽上传 JD 文档</p>
        <p className="ant-upload-hint">
          支持 pdf / txt / md，LLM 自动抽取岗位信息（名称/技能/年限/学历），JD 原文同步入知识库
        </p>
      </Upload.Dragger>
    </Modal>
  )
}

export default function RecruitmentPage() {
  const navigate = useNavigate()
  const [positions, setPositions] = useState([])
  const [loading, setLoading] = useState(false)
  const [createOpen, setCreateOpen] = useState(false)
  const [importOpen, setImportOpen] = useState(false)

  const loadPositions = async () => {
    setLoading(true)
    try {
      const resp = await recruitmentApi.listPositions()
      setPositions(resp.data || [])
    } catch (err) {
      message.error('加载岗位列表失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadPositions()
  }, [])

  const handleDelete = async (id) => {
    try {
      await recruitmentApi.deletePosition(id)
      message.success('岗位已删除')
      loadPositions()
    } catch (err) {
      const errMsg = err.response?.data?.message || err.message || '删除失败'
      message.error(`删除失败：${errMsg}`)
    }
  }

  const handleClose = async (id) => {
    try {
      await recruitmentApi.closePosition(id)
      message.success('岗位已关闭')
      loadPositions()
    } catch (err) {
      const errMsg = err.response?.data?.message || err.message || '操作失败'
      message.error(`关闭失败：${errMsg}`)
    }
  }

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '岗位名称', dataIndex: 'title', render: (t) => <Text strong>{t}</Text> },
    { title: '部门', dataIndex: 'department', width: 100, render: (v) => v || '-' },
    { title: '城市', dataIndex: 'city', width: 90, render: (v) => v || '-' },
    {
      title: '薪资', key: 'salary', width: 120,
      render: (_, r) => (r.salaryMin || r.salaryMax) ? `${r.salaryMin ?? '-'}~${r.salaryMax ?? '-'}k` : '-',
    },
    {
      title: '必须技能', dataIndex: 'requiredSkills', ellipsis: true,
      render: (skills) => (skills || []).map((s) => <Tag key={s} color="blue">{s}</Tag>),
    },
    {
      title: '简历数', dataIndex: 'resumeCount', width: 80,
      render: (c) => <Space><TeamOutlined />{c ?? 0}</Space>,
    },
    {
      title: '状态', dataIndex: 'status', width: 80,
      render: (s) => <Tag color={s === 'OPEN' ? 'green' : 'default'}>{s === 'OPEN' ? '招聘中' : '已关闭'}</Tag>,
    },
    {
      title: '操作', key: 'action', width: 200,
      render: (_, record) => (
        <Space>
          <Button type="link" size="small" onClick={() => navigate(`/recruitment/positions/${record.id}`)}>
            工作台
          </Button>
          {record.status === 'OPEN' && (
            <Popconfirm title="关闭此岗位？" description="关闭后不再参与匹配，数据保留" onConfirm={() => handleClose(record.id)}>
              <Button type="link" size="small">关闭</Button>
            </Popconfirm>
          )}
          <Popconfirm
            title="删除此岗位？"
            description="将连带删除岗位下全部简历、向量、匹配结果、面试题与报告，不可恢复"
            onConfirm={() => handleDelete(record.id)}
            okText="删除" cancelText="取消" okButtonProps={{ danger: true }}
          >
            <Button type="link" size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <Card
        title="招聘岗位管理"
        extra={
          <Space>
            <Button icon={<UploadOutlined />} onClick={() => setImportOpen(true)}>上传 JD 导入</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>创建岗位</Button>
            <Button icon={<ReloadOutlined />} onClick={loadPositions} loading={loading}>刷新</Button>
          </Space>
        }
      >
        {positions.length === 0 && !loading ? (
          <Empty description="暂无岗位，点击右上角「创建岗位」或「上传 JD 导入」开始招聘流程" />
        ) : (
          <Table
            columns={columns}
            dataSource={positions}
            rowKey="id"
            loading={loading}
            pagination={false}
            size="middle"
          />
        )}
      </Card>

      <PositionFormModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={loadPositions}
      />
      <ImportJdModal
        open={importOpen}
        onClose={() => setImportOpen(false)}
        onImported={loadPositions}
      />
    </div>
  )
}
