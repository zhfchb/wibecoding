import { useState, useEffect } from 'react'
import { Card, Upload, Table, Button, message, Typography, Space, Popconfirm } from 'antd'
import { InboxOutlined, ReloadOutlined, DeleteOutlined } from '@ant-design/icons'
import { knowledgeApi } from '../api'
import PageIntro from '../components/PageIntro'

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
      // 兼容多种错误格式：业务异常 {message} / Spring 异常 {error} / Multipart 异常
      const errData = err.response?.data
      const errMsg = errData?.message || errData?.error || errData?.title || err.message || '上传失败'
      const displayError = errMsg.includes('/internal/v1/embeddings')
        ? '向量化服务暂时不可用，请检查模型连接后重试。'
        : errMsg
      message.error(`上传失败：${displayError}`)
      console.error('上传失败详情:', err)
    }
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
          multiple={false}
          maxCount={1}
        >
          <p className="ant-upload-drag-icon">
            <InboxOutlined />
          </p>
          <p className="ant-upload-text">点击或拖拽文件上传</p>
          <p className="ant-upload-hint">
            支持 PDF、TXT、Markdown 文档。处理完成后，可在资料问答中检索内容。
          </p>
        </Dragger>
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
