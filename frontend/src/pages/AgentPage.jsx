import { useState, useEffect } from 'react'
import { Card, Input, Button, List, Tag, Typography, Space, Modal, Form, message, Empty, Badge } from 'antd'
import { PlusOutlined, CheckOutlined, CloseOutlined, ReloadOutlined } from '@ant-design/icons'
import { agentApi } from '../api'

const { TextArea } = Input
const { Text, Paragraph, Title } = Typography

// 状态颜色映射
const statusColor = {
  RUNNING: 'processing',
  AWAITING_REVIEW: 'warning',
  APPROVED: 'success',
  REJECTED: 'error',
  COMPLETED: 'success',
  FAILED: 'error',
}

const statusLabel = {
  RUNNING: '执行中',
  AWAITING_REVIEW: '待审核',
  APPROVED: '已通过',
  REJECTED: '已拒绝',
  COMPLETED: '已完成',
  FAILED: '失败',
}

export default function AgentPage() {
  const [question, setQuestion] = useState('')
  const [tasks, setTasks] = useState([])
  const [loading, setLoading] = useState(false)
  const [rejectModal, setRejectModal] = useState({ open: false, taskId: null })
  const [form] = Form.useForm()

  // 加载待审核任务列表（进程重启后也能恢复）
  const loadPending = async () => {
    setLoading(true)
    try {
      const resp = await agentApi.listPending()
      setTasks(resp.data || [])
    } catch (err) {
      message.error('加载任务列表失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadPending()
  }, [])

  const handleSubmit = async () => {
    if (!question.trim()) return
    setLoading(true)
    try {
      await agentApi.submit(question.trim())
      message.success('任务已提交，草稿已生成')
      setQuestion('')
      await loadPending()
    } catch (err) {
      message.error(err.response?.data?.error || '提交失败')
    } finally {
      setLoading(false)
    }
  }

  const handleApprove = async (taskId) => {
    try {
      await agentApi.approve(taskId)
      message.success('已审批通过')
      await loadPending()
    } catch (err) {
      message.error(err.response?.data?.error || '审批失败')
    }
  }

  const handleReject = async () => {
    const { feedback } = await form.validateFields()
    try {
      await agentApi.reject(rejectModal.taskId, feedback || '')
      message.success('已拒绝并反馈，触发重新生成')
      setRejectModal({ open: false, taskId: null })
      form.resetFields()
      await loadPending()
    } catch (err) {
      message.error(err.response?.data?.error || '拒绝失败')
    }
  }

  return (
    <div>
      <Card title="提交多 Agent 任务" style={{ marginBottom: 16 }}>
        <Space.Compact style={{ width: '100%' }}>
          <TextArea
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            placeholder="输入问题，检索 Agent 找资料、生成 Agent 写草稿，执行到审核节点前暂停等待人工审批"
            autoSize={{ minRows: 2, maxRows: 4 }}
            disabled={loading}
          />
        </Space.Compact>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={handleSubmit}
          loading={loading}
          style={{ marginTop: 8 }}
        >
          提交任务
        </Button>
      </Card>

      <Card
        title={
          <Space>
            <span>待审核任务</span>
            <Badge count={tasks.length} showZero color="#faad14" />
          </Space>
        }
        extra={
          <Button icon={<ReloadOutlined />} onClick={loadPending} loading={loading}>
            刷新
          </Button>
        }
      >
        {tasks.length === 0 ? (
          <Empty description="暂无待审核任务（进程重启后遗留任务也会显示在此）" />
        ) : (
          <List
            dataSource={tasks}
            loading={loading}
            renderItem={(task) => (
              <List.Item>
                <div style={{ width: '100%' }}>
                  <Space style={{ marginBottom: 8 }}>
                    <Tag color={statusColor[task.status]}>{statusLabel[task.status]}</Tag>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      拒绝次数：{task.rejectCount}
                    </Text>
                    {task.createdAt && (
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {new Date(task.createdAt).toLocaleString('zh-CN')}
                      </Text>
                    )}
                  </Space>
                  <Paragraph strong>问题：{task.question}</Paragraph>
                  {task.draft && (
                    <div style={{ background: '#fafafa', padding: 12, borderRadius: 6, margin: '8px 0' }}>
                      <Text type="secondary" style={{ fontSize: 12 }}>草稿：</Text>
                      <div style={{ whiteSpace: 'pre-wrap', marginTop: 4 }}>{task.draft}</div>
                    </div>
                  )}
                  {task.reviewFeedback && (
                    <div style={{ margin: '8px 0' }}>
                      <Text type="secondary" style={{ fontSize: 12 }}>上次拒绝反馈：</Text>
                      <Text type="danger">{task.reviewFeedback}</Text>
                    </div>
                  )}
                  {task.sources && task.sources.length > 0 && (
                    <div style={{ margin: '8px 0' }}>
                      <Text type="secondary" style={{ fontSize: 12 }}>来源：</Text>
                      {task.sources.map((s, j) => (
                        <Tag key={j} style={{ fontSize: 11 }}>
                          {s.fileName}#{s.chunkIndex}
                        </Tag>
                      ))}
                    </div>
                  )}
                  {task.status === 'AWAITING_REVIEW' && (
                    <Space style={{ marginTop: 8 }}>
                      <Button
                        type="primary"
                        icon={<CheckOutlined />}
                        onClick={() => handleApprove(task.taskId)}
                      >
                        审批通过
                      </Button>
                      <Button
                        danger
                        icon={<CloseOutlined />}
                        onClick={() => setRejectModal({ open: true, taskId: task.taskId })}
                      >
                        拒绝并反馈
                      </Button>
                    </Space>
                  )}
                </div>
              </List.Item>
            )}
          />
        )}
      </Card>

      <Modal
        title="拒绝原因与修改建议"
        open={rejectModal.open}
        onOk={handleReject}
        onCancel={() => {
          setRejectModal({ open: false, taskId: null })
          form.resetFields()
        }}
        okText="提交拒绝"
        cancelText="取消"
      >
        <Form form={form} layout="vertical">
          <Form.Item name="feedback" label="反馈内容">
            <TextArea
              placeholder="请填写拒绝原因或修改建议，生成 Agent 将据此重写草稿"
              autoSize={{ minRows: 3, maxRows: 6 }}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
