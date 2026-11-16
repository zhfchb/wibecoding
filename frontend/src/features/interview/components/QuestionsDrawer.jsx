import { useState, useEffect, useCallback } from 'react'
import { Button, Card, Drawer, Typography, Space, Tag, Empty, Spin, Alert } from 'antd'
import { ThunderboltOutlined } from '@ant-design/icons'
import { interviewApi } from '../api.js'
import useMaterialTask from '../hooks/useMaterialTask.js'
import MaterialTaskStatus from './MaterialTaskStatus.jsx'
const { Paragraph } = Typography

const difficultyTag = (d) => ({
  EASY: <Tag color="green">简单</Tag>,
  MEDIUM: <Tag color="orange">中等</Tag>,
  HARD: <Tag color="red">困难</Tag>,
}[d] || <Tag>{d}</Tag>)

export default function QuestionsDrawer({ open, onClose, resumeId, candidateName, canManage, onDenied }) {
  const [questions, setQuestions] = useState([])
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState('')

  const load = useCallback(async () => {
    if (!resumeId) return
    setLoading(true)
    try {
      const resp = await interviewApi.listQuestions(resumeId)
      setQuestions(resp.data || [])
      setLoadError('')
    } catch (err) {
      if ([403, 404].includes(err.response?.status)) { setQuestions([]); onDenied(); return }
      setLoadError('无法加载面试题，已有内容保留，请重新加载')
    } finally {
      setLoading(false)
    }
  }, [resumeId, onDenied])

  useEffect(() => {
    if (open) load()
  }, [open, load])

  const generation = useMaterialTask(open && canManage, resumeId, 'QUESTIONS', load)

  return (
    <Drawer
      title={`定制面试题 — ${candidateName || ''}`}
      footer={<Button onClick={onClose}>关闭窗口</Button>}
      open={open}
      onClose={onClose}
      width={640}
      extra={
        canManage && <Button
          type="primary" icon={<ThunderboltOutlined />}
          loading={generation.loading || generation.submitting || generation.active} disabled={!!generation.error} onClick={generation.submit}
        >
              {generation.active ? '正在准备面试题…' : generation.task?.status === 'FAILED' ? '重试生成面试题' : questions.length ? '重新生成面试题' : '生成面试题'}
        </Button>
      }
    >
      {canManage && <MaterialTaskStatus state={generation} hasContent={questions.length > 0} />}
      {loadError && <Alert style={{ marginBottom: 16 }} type="warning" showIcon message={loadError} action={<Button size="small" onClick={load}>重新加载面试题</Button>} />}
      <Spin spinning={loading}>
        {questions.length === 0 && !loading && !loadError ? (
          <Empty description={canManage ? '尚未生成面试题，点击右上角生成' : '创建人尚未生成面试题'} />
        ) : (
          questions.map((q, i) => (
            <Card key={q.id} size="small" style={{ marginBottom: 12 }}
              title={<Space>{`第 ${i + 1} 题`}<Tag color="geekblue">{q.category}</Tag>{difficultyTag(q.difficulty)}</Space>}
            >
              <Paragraph strong>{q.question}</Paragraph>
              {q.assessPoint && (
                <Paragraph type="secondary">考察点：{q.assessPoint}</Paragraph>
              )}
              {q.referenceAnswer && (
                <Paragraph>
                  <Text type="secondary">参考答案要点：</Text>
                  {q.referenceAnswer}
                </Paragraph>
              )}
            </Card>
          ))
        )}
      </Spin>
    </Drawer>
  )
}

