import { useState, useEffect, useRef } from 'react'
import { Card, Upload, Table, Button, message, Typography, Space, Popconfirm, Tag } from 'antd'
import { InboxOutlined, ReloadOutlined, DeleteOutlined } from '@ant-design/icons'
import { knowledgeApi } from '../api.js'
import PageIntro from '../../../shared/components/PageIntro.jsx'
const { Dragger } = Upload
const { Text } = Typography

export default function KnowledgePage() {
  const [uploads, setUploads] = useState([])
  const queue = useRef([])
  const running = useRef(false)
  const jobs = useRef(new Map())
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; queue.current = [] } }, [])
  const updateUpload = (uid, change) => { if (mounted.current) setUploads(rows => rows.map(row => row.uid === uid ? { ...row, ...change } : row)) }
  const [documents, setDocuments] = useState([])
  const [loading, setLoading] = useState(false)
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 })

  const loadDocuments = async (page = 1, size = 10) => {
    setLoading(true)
    try {
      const resp = await knowledgeApi.listDocuments(page, size)
      const data = resp.data
      setDocuments(data.records || [])
      setPagination({
        current: data.current || page,
        pageSize: data.size || size,
        total: data.total || 0,
      })
    } catch (err) {
      message.error('加载文档列表失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadDocuments()
  }, [])

  const handleDelete = async (documentId) => {
    try {
      await knowledgeApi.deleteDocument(documentId)
      message.success('文档已删除')
      loadDocuments(pagination.current, pagination.pageSize)
    } catch (err) {
      const errData = err.response?.data
      const errMsg = errData?.message || errData?.error || err.message || '删除失败'
      message.error(`删除失败：${errMsg}`)
    }
  }

  // One active request protects the embedding provider; failures do not stop the queue.
  const processQueue = async () => {
    if (running.current) return
    running.current = true
    try {
      while (queue.current.length && mounted.current) {
        const job = queue.current.shift()
        const { file } = job
        job.phase = 'uploading'
        updateUpload(file.uid, { status: 'uploading', error: '' })
        try {
          const resp = await knowledgeApi.upload(file)
          job.phase = 'success'
          updateUpload(file.uid, { status: 'success', chunks: resp.data.chunkCount })
          job.onSuccess?.(resp.data, file)
          if (mounted.current) {
            message.success(`上传成功：${resp.data.fileName}，切片 ${resp.data.chunkCount} 个`)
            await loadDocuments()
          }
        } catch (err) {
          const data = err.response?.data
          const error = data?.message || data?.error || err.message || '上传失败'
          const unknown = !err.response || err.response.status >= 500 && err.response.status !== 503
          const displayError = unknown
            ? '连接中断或处理超时，请先刷新资料列表核对是否上传完成，再决定是否重试'
            : error.includes('/internal/v1/embeddings') ? '向量化服务暂时不可用，请检查模型连接后重试。' : error
          job.phase = unknown ? 'unknown' : 'error'
          updateUpload(file.uid, { status: unknown ? 'unknown' : 'error', error: displayError })
          job.onError?.(err)
          if (mounted.current) message.error(`上传失败：${displayError}`)
        }
      }
    } finally { running.current = false }
  }
  const handleUpload = options => {
    const { file } = options
    options.phase = 'queued'
    jobs.current.set(file.uid, options)
    setUploads(rows => [...rows, { uid: file.uid, name: file.name, status: 'queued' }])
    queue.current.push(options)
    processQueue()
  }
  const retry = uid => {
    const job = jobs.current.get(uid)
    if (!job || !['error', 'unknown'].includes(job.phase)) return
    job.phase = 'queued'
    updateUpload(uid, { status: 'queued', error: '' })
    queue.current.push(job)
    processQueue()
  }
  const beforeUpload = (file, files) => {
    if (files.length > 20) {
      if (file.uid === files[0].uid) message.warning('每批最多选择20份资料')
      return Upload.LIST_IGNORE
    }
    if (file.size > 50 * 1024 * 1024 || !/\.(txt|md|markdown|pdf)$/i.test(file.name)) {
      message.warning(`${file.name}：仅支持50MB以内的PDF、TXT、Markdown文件`)
      return Upload.LIST_IGNORE
    }
    const duplicate = [...jobs.current.values()].some(job => job.file.name === file.name && job.file.size === file.size && job.file.lastModified === file.lastModified)
      || files.slice(0, files.indexOf(file)).some(other => other.name === file.name && other.size === file.size && other.lastModified === file.lastModified)
    if (duplicate) { message.warning(`${file.name}已在本次上传记录中，请查看结果或使用重试按钮`); return Upload.LIST_IGNORE }
    return true
  }

  const columns = [
    { title: '资料名称', dataIndex: 'fileName', key: 'fileName' },
    {
      title: '大小',
      dataIndex: 'fileSize',
      key: 'fileSize',
      width: 100,
      render: (size) => `${(size / 1024).toFixed(1)} KB`,
    },
    {
      title: '上传时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (t) => (t ? new Date(t).toLocaleString('zh-CN') : '-'),
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      render: (_, record) => (
        <Popconfirm
          title="删除此文档？"
          description="资料及其检索内容将被永久删除，不可恢复"
          onConfirm={() => handleDelete(record.documentId)}
          okText="删除"
          cancelText="取消"
          okButtonProps={{ danger: true }}
        >
          <Button type="text" size="small" danger icon={<DeleteOutlined />} />
        </Popconfirm>
      ),
    },
  ]

  return (
    <div>
      <PageIntro eyebrow="HIRING LIBRARY" title="把招聘资料，放在一起。">上传岗位说明、技术评价标准或招聘制度，在「资料问答」中查找依据。候选人简历请进入对应岗位上传。</PageIntro>
      <Card title="添加参考资料" style={{ marginBottom: 20 }}>
        <Dragger
          customRequest={handleUpload}
          accept=".txt,.md,.markdown,.pdf"
          multiple
          showUploadList={false}
          beforeUpload={beforeUpload}
        >
          <p className="ant-upload-drag-icon">
            <InboxOutlined />
          </p>
          <p className="ant-upload-text">点击或拖拽多个文件上传</p>
          <p className="ant-upload-hint">
            支持 PDF、TXT、Markdown，每批最多20份、每份50MB，逐份处理。离开页面会停止尚未开始的上传；已发出的请求请刷新列表核对结果。
          </p>
        </Dragger>
        {uploads.length > 0 && <Table style={{ marginTop: 16 }} size="small" rowKey="uid" pagination={false} dataSource={uploads} columns={[
          { title: '文件', dataIndex: 'name' },
          { title: '状态', render: (_, row) => <><Tag color={{ success: 'success', error: 'error', unknown: 'warning', uploading: 'processing' }[row.status]}>{({ queued: '等待中', uploading: '处理中', success: '成功', error: '失败', unknown: '结果待核对' })[row.status]}</Tag>{row.status === 'success' && `切片 ${row.chunks} 个`}<Text type="secondary">{row.error}</Text></> },
          { title: '操作', render: (_, row) => row.status === 'error' ? <Button size="small" onClick={() => retry(row.uid)}>重试此文件</Button> : row.status === 'unknown' ? <Popconfirm title="已刷新资料列表并确认该文件未上传成功？" description="若已存在，请勿重试，以免重复。" onConfirm={() => retry(row.uid)} okText="确认未成功，重试" cancelText="取消"><Button size="small">核对后重试</Button></Popconfirm> : null },
        ]} />}

      </Card>

      <Card
        title="我的参考资料"
        extra={
          <Button
            icon={<ReloadOutlined />}
            onClick={() => loadDocuments(pagination.current, pagination.pageSize)}
            loading={loading}
          >
            刷新
          </Button>
        }
      >
        <Table
          columns={columns}
          dataSource={documents}
          rowKey="id"
          loading={loading}
          pagination={{
            ...pagination,
            onChange: (page, size) => loadDocuments(page, size),
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 条`,
          }}
          size="middle"
        />
      </Card>
    </div>
  )
}
