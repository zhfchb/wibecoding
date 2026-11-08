import { useState, useEffect } from 'react'
import {
  Card, Table, Button, message, Space, Tag, Popconfirm, Modal, Form,
  Input, InputNumber, Select, AutoComplete, Upload, Typography, Alert,
} from 'antd'
import {
  PlusOutlined, ReloadOutlined, DeleteOutlined, UploadOutlined,
  ArrowRightOutlined, TeamOutlined, FileSearchOutlined, SearchOutlined, DownOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { recruitmentApi } from '../api'
import CompanyDepartmentSelect from '../components/CompanyDepartmentSelect'

const { Text } = Typography

// 常用技能选项：下拉可直接勾选，也支持手动输入回车添加自定义技能
const SKILL_OPTIONS = [
  'Java', 'Spring Boot', 'Spring Cloud', 'MyBatis', 'MyBatis-Plus', 'MySQL',
  'Redis', 'Kafka', 'RabbitMQ', 'RocketMQ', 'Elasticsearch', 'MongoDB',
  'Docker', 'Kubernetes', 'K8s', 'Jenkins', 'CI/CD', 'Linux', 'Nginx',
  '微服务', '分布式', '高并发', 'JVM', '多线程', 'Netty',
  'Python', 'Go', 'C++', 'Rust',
  'JavaScript', 'TypeScript', 'React', 'Vue', 'Node.js', 'HTML/CSS',
  'LangChain', 'LangGraph', 'RAG', 'LLM', '大模型', 'Embedding', '向量数据库',
  '算法', '数据结构', '机器学习', '深度学习', 'PyTorch', 'TensorFlow',
  'Git', 'Maven', 'Gradle', '单元测试', '性能调优',
].map((s) => ({ value: s }))

// 创建岗位表单
function PositionFormModal({ open, onClose, onCreated, positions = [] }) {
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const salaryMin = Form.useWatch('salaryMin', form)
  const minimumUpper = Number.isInteger(salaryMin) ? salaryMin + 1 : 1
  const suggestions = (field, defaults) => [...new Set([...defaults, ...positions.map(p => p[field])]
    .filter(value => typeof value === 'string' && value.trim()).map(value => value.trim()))].map(value => ({ value }))
  const cities = suggestions('city', ['北京', '上海', '广州', '深圳', '杭州', '南京', '苏州', '成都', '武汉', '西安', '重庆', '天津', '郑州', '长沙', '厦门', '合肥'])
  const filterOption = (input, option) => option.value.toLowerCase().includes(input.trim().toLowerCase())

  const handleOk = async () => {
    let values
    try { values = await form.validateFields() }
    catch { return }
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
      destroyOnHidden
    >
      <Form form={form} layout="vertical" initialValues={{ minYears: 3 }} onValuesChange={(changed, values) => {
        if (changed.salaryMin != null && values.salaryMax != null && values.salaryMax <= changed.salaryMin) {
          form.setFieldValue('salaryMax', changed.salaryMin + 1)
        }
      }}>
        <Form.Item name="title" label="岗位名称" rules={[{ required: true, message: '请输入岗位名称' }]}>
          <Input placeholder="如：Java 后端工程师" />
        </Form.Item>
        <Space size="middle" style={{ display: 'flex', flexWrap: 'wrap' }}>
          <Form.Item name="departmentId" label="部门（选填）" style={{ width: 190 }}>
            {open && <CompanyDepartmentSelect refreshKey={open} />}
          </Form.Item>
          <Form.Item name="city" label="城市" style={{ width: 190 }}>
            <AutoComplete options={cities} filterOption={filterOption} allowClear>
              <Input placeholder="选择或输入城市" suffix={<DownOutlined />} />
            </AutoComplete>
          </Form.Item>
          <Form.Item label="薪资 (k)" required style={{ width: 200 }}>
            <Space.Compact>
              <Form.Item name="salaryMin" noStyle rules={[{ required: true, message: '请输入薪资下限' }, { type: 'integer', min: 0, message: '下限需为非负整数' }]}>
                <InputNumber min={0} precision={0} step={1} placeholder="下限" style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="salaryMax" noStyle dependencies={['salaryMin']} rules={[
                { required: true, message: '请输入薪资上限' },
                { type: 'integer', min: 1, message: '上限需为正整数' },
                ({ getFieldValue }) => ({ validator(_, value) {
                  const lower = getFieldValue('salaryMin')
                  return value == null || lower == null || value >= lower + 1
                    ? Promise.resolve() : Promise.reject(new Error('薪资上限至少比下限高 1k'))
                } }),
              ]}>
                <InputNumber min={minimumUpper} precision={0} step={1} placeholder={`上限 ≥ ${minimumUpper}`} style={{ width: '100%' }} />
              </Form.Item>
            </Space.Compact>
          </Form.Item>
        </Space>
        <Form.Item
          name="requiredSkills"
          label="必须技能"
          rules={[{ required: true, message: '请选择或输入至少一个必须技能' }]}
        >
          <Select
            mode="tags"
            options={SKILL_OPTIONS}
            placeholder="下拉选择或输入后回车添加，如 Java、Spring Boot"
          />
        </Form.Item>
        <Form.Item name="preferredSkills" label="加分技能">
          <Select
            mode="tags"
            options={SKILL_OPTIONS}
            placeholder="下拉选择或输入后回车添加，如 Docker、K8s"
          />
        </Form.Item>
        <Space size="middle" style={{ display: 'flex', flexWrap: 'wrap' }}>
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
      destroyOnHidden
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
          支持 PDF / TXT / Markdown。自动整理岗位名称、技能与经验要求，创建后请核对岗位信息。
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
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('ALL')
  const [loadError, setLoadError] = useState(false)

  const loadPositions = async () => {
    setLoading(true)
    setLoadError(false)
    try {
      const resp = await recruitmentApi.listPositions()
      setPositions(resp.data || [])
    } catch (err) {
      setLoadError(true)
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
    { title: '岗位名称', dataIndex: 'title', render: (t, r) => <button className="position-title-link" onClick={() => navigate(`/recruitment/positions/${r.id}`)}>{t}</button> },
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
            查看候选人
          </Button>
          {!record.canManage && <Tag>{record.canOperate ? '招聘协作' : '只读协作'}</Tag>}
          {record.canManage && record.status === 'OPEN' && (
            <Popconfirm title="关闭此岗位？" description="关闭后不再参与匹配，数据保留" onConfirm={() => handleClose(record.id)}>
              <Button type="link" size="small">关闭</Button>
            </Popconfirm>
          )}
          {record.canManage && <Popconfirm
            title="删除此岗位？"
            description="将连带删除岗位下全部简历、向量、匹配结果、面试题与报告，不可恢复"
            onConfirm={() => handleDelete(record.id)}
            okText="删除" cancelText="取消" okButtonProps={{ danger: true }}
          >
            <Button type="link" size="small" danger icon={<DeleteOutlined />} aria-label={`删除岗位 ${record.title}`} />
          </Popconfirm>}
        </Space>
      ),
    },
  ]

  const visiblePositions = positions.filter(p => (status === 'ALL' || p.status === status) && `${p.title || ''} ${p.department || ''} ${p.city || ''}`.toLowerCase().includes(query.trim().toLowerCase()))
  const openCount = positions.filter(p => p.status === 'OPEN').length
  const resumeCount = positions.reduce((total, p) => total + (p.resumeCount || 0), 0)
  const hasCandidates = positions.filter(p => p.status === 'OPEN' && p.resumeCount > 0).length
  const onCreated = (p) => { navigate(`/recruitment/positions/${p.id}`) }
  return (
    <div className="recruitment-home">
      <section className="page-heading"><div><span className="eyebrow">HIRING WORKSPACE</span><h1>让每一次筛选，都有依据。</h1><p>按岗位整理候选人，从简历初筛走向更有准备的面试。</p></div><Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>创建招聘岗位</Button></section>
      <section className="stats-strip" aria-label="招聘概况">
        {[[openCount, '招聘中的岗位', '当前仍在开放招聘'], [resumeCount, '已收录的简历', '所有岗位下的简历总数'], [hasCandidates, '已有候选人的岗位', '进入岗位可查看筛选进度']].map(([value, label, hint]) => <div key={label}><span>{label}</span><strong>{loading || loadError ? '—' : value.toString().padStart(2, '0')}</strong><small>{hint}</small></div>)}
        <div className="stats-note"><span className="small-dot" />招聘从清晰的标准开始<p>先确定必须技能，<br />再判断候选人的匹配依据。</p></div>
      </section>
      <section className="workflow-guide" aria-label="使用方法">
        {[['01', '建立岗位', '填写要求，或导入已有 JD'], ['02', '导入简历', '在岗位内上传候选人资料'], ['03', '辅助筛选', '查看技能匹配，人工确认'], ['04', '准备面试', '生成问题与评估报告']].map(([n, title, desc]) => <div key={n}><span className="step-number">{n}</span><div><strong>{title}</strong><p>{desc}</p></div></div>)}
      </section>
      {loadError && <Alert type="error" showIcon message="暂时无法加载岗位，请检查服务后重试。" action={<Button size="small" onClick={loadPositions}>重试</Button>} style={{ marginBottom: 16 }} />}
      <Card
        className="positions-panel"
        title={<span>可访问的招聘岗位 <span className="count-badge">{loadError ? '—' : positions.length}</span></span>}
        extra={
          <Space>
            <Button icon={<UploadOutlined />} onClick={() => setImportOpen(true)}>导入 JD</Button>
            <Button type="text" icon={<ReloadOutlined />} onClick={loadPositions} loading={loading} aria-label="刷新岗位列表" />
          </Space>
        }
      >
        {positions.length === 0 && !loading && !loadError ? (
          <div className="position-empty"><div className="empty-art"><FileSearchOutlined /><span>01</span></div><span className="eyebrow">你的第一个招聘计划</span><h2>先从一个岗位开始</h2><p>告诉我们你正在寻找怎样的人。<br />创建岗位后，即可导入简历、查看匹配依据并准备面试。</p><Space wrap><Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>创建第一个岗位</Button><Button icon={<UploadOutlined />} onClick={() => setImportOpen(true)}>已有 JD？直接导入</Button></Space><small>支持 PDF、TXT 和 Markdown 文档</small></div>
        ) : (
          <>
          <div className="position-filters"><Input prefix={<SearchOutlined />} value={query} onChange={e => setQuery(e.target.value)} placeholder="搜索岗位、部门或城市" allowClear aria-label="搜索岗位" /><Select aria-label="岗位状态" value={status} onChange={setStatus} options={[{ value: 'ALL', label: '全部状态' }, { value: 'OPEN', label: '招聘中' }, { value: 'CLOSED', label: '已关闭' }]} /></div>
          <Table
            columns={columns}
            dataSource={visiblePositions}
            rowKey="id"
            loading={loading}
            pagination={{ pageSize: 8, hideOnSinglePage: true }}
            scroll={{ x: 1050 }}
            locale={{ emptyText: loadError ? '岗位加载失败，请重试' : '没有符合条件的岗位，试试其他关键词' }}
            size="middle"
          />
          </>
        )}
      </Card>

      <PositionFormModal
        positions={positions}
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={onCreated}
      />
      <ImportJdModal
        open={importOpen}
        onClose={() => setImportOpen(false)}
        onImported={onCreated}
      />
    </div>
  )
}
