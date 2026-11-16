import { useEffect, useState } from 'react'
import { Alert, Button, Card, Space, Tag, Typography } from 'antd'
import api from '../../../shared/api/client.js'
export default function HiringNextSteps({ positionId, resumes, matches, canOperate, closed, revision }) {
  const [assignments, setAssignments] = useState(null)
  const [error, setError] = useState(false)
  const [refresh, setRefresh] = useState(0)
  useEffect(() => {
    let active = true
    setAssignments(null); setError(false)
    if (!canOperate) return
    api.get(`/recruitment/positions/${positionId}/interviews`).then(({ data }) => { if (active) setAssignments(data) })
      .catch(() => { if (active) setError(true) })
    return () => { active = false }
  }, [positionId, canOperate, revision, refresh])
  const parsed = resumes.filter(r => r.parseStatus === 'PARSED').length
  const approved = matches.filter(m => m.hrDecision === 'APPROVED')
  const live = (assignments || []).filter(a => a.status !== 'REVOKED')
  const unassigned = approved.filter(m => !live.some(a => a.resumeId === m.resumeId)).length
  const unscheduled = live.filter(a => a.status !== 'SUBMITTED' && !a.schedule).length
  const pendingEvaluation = live.filter(a => a.status !== 'SUBMITTED' && a.schedule && !['CANCELLED', 'NO_SHOW'].includes(a.schedule.status) && Date.parse(a.schedule.endAt) <= Date.now()).length
  const hint = closed ? '岗位已关闭。可核对历史评价、补充跟进并整理招聘结果；新增或修改面试排期前请核对岗位状态。'
    : !resumes.length ? '先上传收到的简历。候选人资料解析后，再查看匹配依据。'
    : parsed < resumes.length ? '先核对解析失败或仍在处理的简历，失败项可重新解析。'
      : !matches.length ? '简历已收录，下一步执行人岗匹配，再由HR核实证据并作初筛决定。'
        : matches.some(m => m.hrDecision === 'PENDING') ? '有候选人等待初筛：查看证据后，人工决定是否进入面试。'
          : unassigned ? '有已通过初筛的候选人尚未分配面试官。在简历列表点击“分配面试”。'
            : unscheduled ? '有面试尚未排期。在“分配面试”中安排时间，并另行联系候选人。'
              : pendingEvaluation ? '有已到结束时间的面试尚未提交评价，请联系面试官完成评价。'
                : '继续跟进面试与评价；在招聘进度总览核对评价后，人工记录最终结果。'
  return <Card title="招聘流程 · 下一步" style={{ marginBottom: 20 }} extra={canOperate && <Button onClick={() => setRefresh(v => v + 1)}>更新流程提示</Button>}>
    <Typography.Paragraph>收录简历 → HR初筛 → 分配面试与排期 → 面试官提交评价 → HR汇总并记录结果</Typography.Paragraph>
    {canOperate ? <>
      {error ? <Alert type="warning" message="面试进度读取失败，请更新流程提示后再核对" />
        : assignments === null ? <Typography.Text type="secondary">正在核对面试进度…</Typography.Text>
          : <Alert type="info" showIcon message={hint} />}
      <Space wrap style={{ marginTop: 12 }}><Tag>已解析简历 {parsed}/{resumes.length}</Tag><Tag>初筛通过 {approved.length}</Tag>
        {assignments !== null && <><Tag>待分配 {unassigned}</Tag><Tag>待排期 {unscheduled}</Tag><Tag>待提交评价 {pendingEvaluation}</Tag></>}
      </Space>
    </> : <Typography.Paragraph type="secondary">你可查看当前岗位资料；上传、初筛和面试分配由创建人或获招聘协作权限的HR处理。</Typography.Paragraph>}
    <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>这些提示不自动推动招聘状态。保存排期不代表已通知候选人；记录录用不代表已发Offer或完成入职。</Typography.Paragraph>
  </Card>
}
