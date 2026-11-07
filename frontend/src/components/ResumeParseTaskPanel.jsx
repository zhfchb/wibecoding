import { Alert, Button, List, Popconfirm, Progress, Space, Tag, Typography } from 'antd'

const { Text } = Typography
const labels = { QUEUED: '排队中', RUNNING: '解析中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消' }

export default function ResumeParseTaskPanel({ state, selectedIds, onSubmitted, closed }) {
  const task = state.task
  const submit = async () => { if (await state.submit(selectedIds)) onSubmitted() }
  return <div style={{ marginBottom: 16 }}>
    <Space wrap style={{ marginBottom: 12 }}>
      <Button onClick={submit} loading={state.submitting}
        disabled={closed || state.busy || selectedIds.length === 0 || selectedIds.length > 20}>
        批量重新解析（{selectedIds.length}）
      </Button>
      <Text type="secondary">选择已保存原文的失败简历，每次最多 20 份，无需重新上传。</Text>
    </Space>
    {state.error && <Alert type="warning" showIcon message={state.error}
      action={<Button size="small" onClick={state.refresh}>刷新解析状态</Button>} />}
    {task && <Alert showIcon
      type={task.status === 'FAILED' ? 'error' : task.status === 'PARTIAL' ? 'warning' : task.status === 'SUCCEEDED' ? 'success' : 'info'}
      message={state.active ? '重新解析任务进行中，刷新页面后仍可查看'
        : task.status === 'CANCELLED' ? '重新解析已取消，已完成结果保留'
          : task.errorMessage || `重新解析结束：成功 ${task.succeeded} 份，失败 ${task.failed} 份`}
      description={<>
        <Progress percent={Math.round(100 * (task.succeeded + task.failed + task.cancelled) / Math.max(1, task.total))} size="small"
          status={['FAILED', 'PARTIAL'].includes(task.status) ? 'exception' : state.active ? 'active' : 'normal'}
          strokeColor={task.status === 'CANCELLED' ? '#8c8c8c' : undefined} showInfo={task.status !== 'CANCELLED'} />
        <List size="small" dataSource={task.items} renderItem={item => <List.Item>
          <Space wrap><Text>{item.candidateName || `简历 #${item.resumeId}`}</Text>
            <Tag color={item.status === 'SUCCEEDED' ? 'green' : item.status === 'FAILED' ? 'red' : 'default'}>{labels[item.status]}</Tag>
            {item.errorMessage && <Text type="secondary">{item.errorMessage}</Text>}
          </Space>
        </List.Item>} />
      </>}
      action={state.active ? <Popconfirm title="取消未完成的解析？" description="已完成的结果保留；已发送的模型请求可能仍会计费。"
        okText="取消解析" cancelText="继续执行" onConfirm={state.cancel}>
        <Button size="small">取消重新解析</Button>
      </Popconfirm> : null} />}
  </div>
}
