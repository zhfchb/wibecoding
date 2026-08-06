import { useState, useEffect } from 'react'
import { Card, Upload, Table, Button, message, Typography, Space } from 'antd'
import { InboxOutlined, ReloadOutlined } from '@ant-design/icons'
import { knowledgeApi } from '../api'

const { Dragger } = Upload
const { Text } = Typography

export default function KnowledgePage() {
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

  // 自定义上传：手动调 API
  const handleUpload = async (options) => {
    const { file, onSuccess, onError } = options
    try {
      const resp = await knowledgeApi.upload(file)
      onSuccess(resp.data, file)
      message.success(`上传成功：${resp.data.fileName}，切片 ${resp.data.chunkCount} 个`)
      loadDocuments(pagination.current, pagination.pageSize)
    } catch (err) {
      onError(err)
      message.error(err.response?.data?.message || '上传失败')
    }
  }

  const columns = [
    { title: '文档 ID', dataIndex: 'documentId', key: 'documentId', ellipsis: true },
    { title: '文件名', dataIndex: 'fileName', key: 'fileName' },
    { title: '切片数', dataIndex: 'chunkCount', key: 'chunkCount', width: 80 },
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
  ]

  return (
    <div>
      <Card title="上传文档" style={{ marginBottom: 16 }}>
        <Dragger
          customRequest={handleUpload}
          accept=".txt,.md,.markdown"
          multiple={false}
          maxCount={1}
        >
          <p className="ant-upload-drag-icon">
            <InboxOutlined />
          </p>
          <p className="ant-upload-text">点击或拖拽文件上传</p>
          <p className="ant-upload-hint">
            支持 txt / md / markdown 格式，单文件最大 2MB。上传后自动切分、Embedding、存入向量库
          </p>
        </Dragger>
      </Card>

      <Card
        title="文档列表"
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
