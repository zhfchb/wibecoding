import { Alert, Button, Popconfirm } from 'antd'

export default function MaterialTaskStatus({ state, hasContent }) {
  const task = state.task
  return <>
    {state.error && <Alert style={{ marginBottom: 16 }} type="warning" showIcon message={state.error}
      action={<Button size="small" onClick={state.refresh}>刷新生成状态</Button>} />}
    {task && <Alert style={{ marginBottom: 16 }} showIcon
      type={task.status === 'FAILED' ? 'error' : task.stale ? 'warning' : task.status === 'SUCCEEDED' ? 'success' : 'info'}
      message={state.active ? ({ QUEUED: '生成任务已排队', PREPARING: '正在准备资料', SEARCHING: '正在查找参考资料', GENERATING: '正在生成内容', SAVING: '正在保存内容' })[task.phase] || '生成任务执行中'
        : task.status === 'FAILED' ? task.errorMessage || '生成失败，原内容保留，可重新生成'
          : task.status === 'CANCELLED' ? '生成任务已取消，原内容保留'
            : task.stale ? '岗位、简历或匹配结果已变化，下方内容来自旧版本，请重新生成'
              : '生成完成'}
      description={state.active ? `关闭窗口后任务继续，重新打开可查看进度。${hasContent ? '下方保留上一次内容，成功后更新。' : ''}` : null}
      action={state.active ? <Popconfirm title="取消本轮生成？" description="已发出的模型请求可能仍会计费，原内容保留。"
        okText="取消本轮" cancelText="继续执行" onConfirm={state.cancel}>
        <Button size="small">取消生成</Button>
      </Popconfirm> : null} />}
  </>
}
