import { useState, useEffect, useCallback, useRef } from 'react'
import {
  Card, Table, Button, message, Space, Tag, Popconfirm, Upload, Drawer,
  Descriptions, Progress, Typography, Tooltip, Empty, Spin, Alert,
} from 'antd'
import {
  ArrowLeftOutlined, ReloadOutlined, DeleteOutlined, UploadOutlined,
  ThunderboltOutlined, CheckOutlined, CloseOutlined, FileMarkdownOutlined,
} from '@ant-design/icons'
import { useParams, useNavigate } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { recruitmentApi } from '../api'
import PageIntro from '../components/PageIntro'
import PositionDepartmentEditor from '../components/PositionDepartmentEditor'
import PositionCollaborators from '../components/PositionCollaborators'
import PositionActivity from '../components/PositionActivity'
import CandidateInterviews from '../components/CandidateInterviews'
import CandidateOutcome from '../components/CandidateOutcome'
import RecruitmentProgress from '../components/RecruitmentProgress'
import ResumeParseTaskPanel from '../components/ResumeParseTaskPanel'
import useResumeParseTask from '../hooks/useResumeParseTask'
import useMaterialTask from '../hooks/useMaterialTask'
import MaterialTaskStatus from '../components/MaterialTaskStatus'

const { Text, Paragraph, Title } = Typography

// 决策标签
const decisionTag = (d) => {
  if (d === 'APPROVED') return <Tag color="green">已通过</Tag>
  if (d === 'REJECTED') return <Tag color="red">已淘汰</Tag>
  return <Tag color="orange">待决策</Tag>
}

// 推荐标签
const recommendTag = (r) => {
  if (r === 'STRONG_YES') return <Tag color="green">强烈推荐</Tag>
  if (r === 'YES') return <Tag color="blue">推荐</Tag>
  if (r === 'NO') return <Tag color="red">不推荐</Tag>
  return null
}

const difficultyTag = (d) => ({
  EASY: <Tag color="green">简单</Tag>,
  MEDIUM: <Tag color="orange">中等</Tag>,
  HARD: <Tag color="red">困难</Tag>,
}[d] || <Tag>{d}</Tag>)

// ==================== 面试题抽屉 ====================
function QuestionsDrawer({ open, onClose, resumeId, candidateName, canManage, onDenied }) {
  const [questions, setQuestions] = useState([])
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState('')

  const load = useCallback(async () => {
    if (!resumeId) return
    setLoading(true)
    try {
      const resp = await recruitmentApi.listQuestions(resumeId)
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

// ==================== 评估报告抽屉 ====================
function ReportDrawer({ open, onClose, resumeId, candidateName, canManage, onDenied }) {
  const [report, setReport] = useState(null)
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState('')

  const load = useCallback(async () => {
    if (!resumeId) return
    setLoading(true)
    try {
      const resp = await recruitmentApi.getReport(resumeId)
      setReport(resp.data)
      setLoadError('')
    } catch (err) {
      if ([403, 404].includes(err.response?.status)) { setReport(null); onDenied(); return }
      if (err.response?.status === 400) { setReport(null); setLoadError('') }
      else setLoadError('无法加载报告，已有内容保留，请重新加载')
    } finally {
      setLoading(false)
    }
  }, [resumeId, onDenied])

  useEffect(() => {
    if (open) load()
  }, [open, load])

  const generation = useMaterialTask(open && canManage, resumeId, 'REPORT', load)

  return (
    <Drawer
      title={`候选人评估报告 — ${candidateName || ''}`}
      open={open}
      onClose={onClose}
      width={720}
      extra={
        canManage && <Button
          type="primary" icon={<FileMarkdownOutlined />}
          loading={generation.loading || generation.submitting || generation.active} disabled={!!generation.error} onClick={generation.submit}
        >
          {generation.active ? '正在生成报告…' : generation.task?.status === 'FAILED' ? '重试生成报告' : report ? '重新生成报告' : '生成报告'}
        </Button>
      }
    >
      {canManage && <MaterialTaskStatus state={generation} hasContent={!!report} />}
      {loadError && <Alert style={{ marginBottom: 16 }} type="warning" showIcon message={loadError} action={<Button size="small" onClick={load}>重新加载报告</Button>} />}
      <Spin spinning={loading}>
        {!report && !loading && !loadError ? (
          <Empty description={canManage ? '尚未生成评估报告，点击右上角生成' : '创建人尚未生成评估报告'} />
        ) : report ? (
          <>
            <Descriptions bordered size="small" column={{ xs: 1, sm: 2 }} style={{ marginBottom: 16 }}>
              <Descriptions.Item label="生成时匹配分" span={1}>
                <Text strong style={{ fontSize: 18 }}>{report.matchScore ?? '未记录'}</Text>
              </Descriptions.Item>
              <Descriptions.Item label="最终推荐">{recommendTag(report.finalRecommend)}</Descriptions.Item>
              <Descriptions.Item label="建议薪资" span={2}>
                <Text strong>{report.salarySuggest || '-'}</Text>
              </Descriptions.Item>
              <Descriptions.Item label="总评" span={2}>{report.summary}</Descriptions.Item>
              <Descriptions.Item label="优势" span={2}>
                <Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>{report.strength}</Paragraph>
              </Descriptions.Item>
              <Descriptions.Item label="风险提示" span={2}>
                <Paragraph type="warning" style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>{report.risk}</Paragraph>
              </Descriptions.Item>
            </Descriptions>
            {report.marketRefs?.length > 0 && (
              <Card size="small" title="市场行情引用" style={{ marginBottom: 16 }}>
                {report.marketRefs.map((ref, i) => (
                  <Paragraph key={i} style={{ marginBottom: 4 }}>
                    <a href={ref.url} target="_blank" rel="noreferrer">{ref.title || ref.url}</a>
                  </Paragraph>
                ))}
              </Card>
            )}
            <Card size="small" title="完整报告">
              <div className="markdown-body">
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{report.reportMd || ''}</ReactMarkdown>
              </div>
            </Card>
          </>
        ) : null}
      </Spin>
    </Drawer>
  )
}

// ==================== 工作台主页面 ====================
export default function PositionWorkbenchPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const [position, setPosition] = useState(null)
  const canManage = position?.canManage === true
  const canOperate = canManage || position?.canOperate === true
  const [accessDenied, setAccessDenied] = useState(false)
  const [resumes, setResumes] = useState([])
  const [selectedResumes, setSelectedResumes] = useState([])
  const [matches, setMatches] = useState([])
  const [loadingResumes, setLoadingResumes] = useState(false)
  const [loadingMatches, setLoadingMatches] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [submittingMatch, setSubmittingMatch] = useState(false)
  const [matchTask, setMatchTask] = useState(null)
  const [taskLoading, setTaskLoading] = useState(true)
  const [taskError, setTaskError] = useState('')
  const [pollVersion, setPollVersion] = useState(0)
  const requestKey = useRef(null)
  const submitInFlight = useRef(false)
  const taskActive = ['QUEUED', 'RUNNING'].includes(matchTask?.status)
  const matching = submittingMatch || taskActive
  const [qDrawer, setQDrawer] = useState({ open: false, resumeId: null, name: null })
  const [rDrawer, setRDrawer] = useState({ open: false, resumeId: null, name: null })

  const denyAccess = useCallback(() => {
    setAccessDenied(true); setPosition(null); setResumes([]); setMatches([])
    setQDrawer({ open: false }); setRDrawer({ open: false })
  }, [])

  const loadResumes = useCallback(async () => {
    setLoadingResumes(true)
    try {
      const resp = await recruitmentApi.listResumes(id)
      setResumes(resp.data || [])
    } catch (err) {
      if ([403, 404].includes(err.response?.status)) denyAccess()
      message.error('加载简历列表失败')
    } finally {
      setLoadingResumes(false)
    }
  }, [id])

  const resumeParse = useResumeParseTask(id, loadResumes, canOperate)
  useEffect(() => { setSelectedResumes([]) }, [id])
  useEffect(() => {
    setSelectedResumes(ids => ids.filter(key => resumes.some(r => r.id === key && r.retryable)))
  }, [resumes])

  const loadMatches = useCallback(async () => {
    setLoadingMatches(true)
    try {
      const resp = await recruitmentApi.listMatchResults(id)
      setMatches(resp.data || [])
    } catch (err) {
      if ([403, 404].includes(err.response?.status)) denyAccess()
      if (err.response?.status !== 400) message.error('加载匹配结果失败')
      setMatches([])
    } finally {
      setLoadingMatches(false)
    }
  }, [id])

  useEffect(() => {
    setAccessDenied(false); setPosition(null)
    recruitmentApi.getPosition(id)
      .then((resp) => setPosition(resp.data))
      .catch(() => { denyAccess(); message.error('加载岗位信息失败或没有访问权限') })
    loadResumes()
    loadMatches()
  }, [id, loadResumes, loadMatches])

  // The server is the source of truth; no localStorage task IDs or overlapping interval requests.
  useEffect(() => {
    if (!canOperate) { setMatchTask(null); setTaskLoading(false); setTaskError(''); return }
    let active = true
    let timer
    let lastTerminal = ''
    setMatchTask(null)
    setTaskLoading(true)
    requestKey.current = null
    const poll = async () => {
      try {
        const { data } = await recruitmentApi.latestMatchTask(id)
        if (!active) return
        setMatchTask(data || null)
        setTaskError('')
        const terminal = data && !['QUEUED', 'RUNNING'].includes(data.status)
        if (terminal && data.requestKey === requestKey.current) requestKey.current = null
        if (terminal && lastTerminal !== data.id) {
          lastTerminal = data.id
          loadMatches()
        }
      } catch {
        if (active) setTaskError('暂时无法获取匹配任务状态，正在重试。请勿重复提交。')
      } finally {
        if (active) {
          setTaskLoading(false)
          timer = setTimeout(poll, 2000)
        }
      }
    }
    poll()
    return () => { active = false; clearTimeout(timer) }
  }, [id, loadMatches, pollVersion, canOperate])

  // Recheck a read grant while the page remains open; never poll owner-only task APIs as a viewer.
  useEffect(() => {
    if (!position || canManage) return
    let active = true
    const timer = setInterval(() => recruitmentApi.getPosition(id).then(resp => { if(active) setPosition(resp.data) }).catch(err => {
      if (active && [403, 404].includes(err.response?.status)) denyAccess()
    }), 10000)
    return () => { active = false; clearInterval(timer) }
  }, [id, !!position, canManage, denyAccess])

  const handleUpload = async ({ file, onSuccess, onError }) => {
    setUploading(true)
    try {
      const resp = await recruitmentApi.uploadResumes(id, [file])
      const { successCount, failedCount } = resp.data
      const summary = `上传完成：解析成功 ${successCount} 份${failedCount ? `，失败 ${failedCount} 份；已保存原文的简历可在列表重新解析` : ''}`
      if (failedCount) message.warning(summary)
      else message.success(summary)
      onSuccess(resp.data)
      loadResumes()
    } catch (err) {
      onError(err)
      const errMsg = err.response?.data?.message || err.message || '上传失败'
      message.error(`简历上传失败：${errMsg}`)
    } finally {
      setUploading(false)
    }
  }

  const handleRunMatch = async () => {
    if (submitInFlight.current || taskActive || taskLoading || taskError) return
    submitInFlight.current = true
    setSubmittingMatch(true)
    requestKey.current ||= globalThis.crypto?.randomUUID?.() || `match_${Date.now()}_${Math.random().toString(36).slice(2)}`
    try {
      const resp = await recruitmentApi.submitMatchTask(id, requestKey.current)
      setMatchTask(resp.data)
      requestKey.current = null
      setTaskError('')
      if (resp.data.status === 'SUCCEEDED') loadMatches()
    } catch (err) {
      if (!err.response || err.response.status >= 500) {
        message.warning('提交结果尚未确认，系统会继续查询。重试将复用请求标识。')
      } else {
        requestKey.current = null
        message.error(err.response.data?.message || '无法提交匹配任务')
      }
    } finally {
      submitInFlight.current = false
      setSubmittingMatch(false)
    }
  }

  const handleCancelMatch = async () => {
    try {
      const { data } = await recruitmentApi.cancelMatchTask(matchTask.id)
      setMatchTask(data)
    } catch {
      message.error('取消结果尚未确认，请查看任务状态')
    }
  }

  const handleDecide = async (matchId, approved) => {
    try {
      await recruitmentApi.decide(matchId, approved)
      message.success(approved ? '已通过，进入面试流程' : '已淘汰')
      loadMatches()
    } catch (err) {
      const errMsg = err.response?.data?.message || err.message || '操作失败'
      message.error(`决策失败：${errMsg}`)
    }
  }

  const handleDeleteResume = async (resumeId) => {
    try {
      await recruitmentApi.deleteResume(resumeId)
      message.success('简历已删除')
      loadResumes()
      loadMatches()
    } catch (err) {
      const errMsg = err.response?.data?.message || err.message || '删除失败'
      message.error(`删除失败：${errMsg}`)
    }
  }

  const resumeColumns = [
    { title: '姓名/文件', dataIndex: 'candidateName', width: 130, render: (v, r) => <Text strong>{v || r.fileName || `简历 #${r.id}`}</Text> },
    {
      title: '解析状态', dataIndex: 'parseStatus', width: 100,
      render: (s, r) => s === 'PARSED'
        ? r.parseError?.startsWith('DEMO_FIXTURE:')
          ? <Tooltip title="虚构结构化资料直接导入，未调用 AI 解析或向量化"><Tag>演示预设</Tag></Tooltip>
          : <Tag color="green">已解析</Tag>
        : <Tooltip title={r.parseError}><Tag color={s === 'FAILED' ? 'red' : 'orange'}>{s === 'FAILED' ? '失败' : '处理中'}</Tag></Tooltip>,
    },
    { title: '技能', dataIndex: 'skills', ellipsis: true, render: (skills) => (skills || []).map((s) => <Tag key={s}>{s}</Tag>) },
    { title: '年限', dataIndex: 'yearsExp', width: 70, render: (v) => (v == null ? '-' : `${v}年`) },
    { title: '学历', dataIndex: 'education', width: 80, render: (v) => v || '-' },
    { title: '上家', dataIndex: 'lastCompany', width: 110, render: (v) => v || '-' },
    {
      title: '操作', key: 'action', width: 260,
      render: (_, record) => (
        <Space>
          {canOperate && record.retryable && <Button type="link" size="small" disabled={resumeParse.busy || position?.status === 'CLOSED'} onClick={() => resumeParse.submit([record.id])}>重新解析</Button>}
          {canOperate && <CandidateInterviews positionId={id} resume={record} />}
          <CandidateOutcome resume={record} canOperate={canOperate} onChanged={loadResumes} />
          <Button type="link" size="small" onClick={() => setQDrawer({ open: true, resumeId: record.id, name: record.candidateName })}>
            面试题
          </Button>
          <Button type="link" size="small" onClick={() => setRDrawer({ open: true, resumeId: record.id, name: record.candidateName })}>
            评估报告
          </Button>
          {canManage && <Popconfirm title="删除此简历？" description="将删除关联资料与匹配结果，向量将在后台清理" onConfirm={() => handleDeleteResume(record.id)}>
            <Button type="link" size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>}
        </Space>
      ),
    },
  ]

  const matchColumns = [
    {
      title: '排名', dataIndex: 'rankNo', width: 70,
      render: (r) => <span className="count-badge">{String(r).padStart(2, '0')}</span>,
    },
    { title: '候选人', dataIndex: 'candidateName', width: 110, render: (v) => <Text strong>{v || '-'}</Text> },
    {
      title: '匹配参考分', dataIndex: 'matchScore', width: 140, sorter: (a, b) => a.matchScore - b.matchScore,
      render: (score, r) => (
        <Space>
          <Progress type="circle" percent={Number(score)} size={44}
            strokeColor={score >= 85 ? '#52c41a' : score >= 60 ? '#1677ff' : '#ff4d4f'} format={(p) => p} />
          {r.scoreDetail && (
            <Tooltip title={`${r.scoreDetail.scoringStatus === 'COMPLETE' ? `规则分 ${r.scoreDetail.ruleScore} × 70% + 语义分 ${r.scoreDetail.llmScore} × 30%` : r.scoreDetail.scoringStatus === 'LEGACY' ? '历史评分，建议重新匹配确认评分状态' : `仅规则分 ${r.scoreDetail.ruleScore}，未计入语义评分`}（技能${r.scoreDetail.skillScore}/加分${r.scoreDetail.preferredScore}/年限${r.scoreDetail.yearsScore}/学历${r.scoreDetail.educationScore}）${r.scoreDetail.llmNotes ? `；${r.scoreDetail.llmNotes}` : ''}`}>
              <Text type="secondary" style={{ fontSize: 12 }}>明细</Text>
            </Tooltip>
          )}
          {r.scoreDetail?.scoringStatus === 'DEGRADED' && <Tag color="warning">语义评分未完成</Tag>}
          {r.scoreDetail?.scoringStatus === 'RULE_ONLY' && <Tag>仅规则评分</Tag>}
          {r.scoreDetail?.scoringStatus === 'LEGACY' && <Tag>历史评分待确认</Tag>}
        </Space>
      ),
    },
    { title: '年限/学历', width: 120, render: (_, r) => `${r.yearsExp ?? '-'}年 / ${r.education || '-'}` },
    {
      title: '技能命中', dataIndex: 'skillHits', ellipsis: true,
      render: (hits) => (hits || []).map((s) => <Tag key={s} color="green">{s}</Tag>),
    },
    {
      title: '待核实技能', dataIndex: 'skillGaps', ellipsis: true,
      render: (gaps) => (gaps || []).map((s) => <Tag key={s} color="red">{s}</Tag>),
    },
    { title: 'HR 决策', dataIndex: 'hrDecision', width: 100, render: decisionTag },
    {
      title: '操作', key: 'action', width: 230,
      render: (_, record) => (
        <Space>
          {canOperate && record.hrDecision === 'PENDING' && (
            <>
              <Popconfirm title="通过该候选人？" description="通过后可生成面试题与评估报告" onConfirm={() => handleDecide(record.id, true)}>
                <Button type="primary" size="small" icon={<CheckOutlined />}>通过</Button>
              </Popconfirm>
              <Popconfirm title="淘汰该候选人？" onConfirm={() => handleDecide(record.id, false)}>
                <Button danger size="small" icon={<CloseOutlined />}>淘汰</Button>
              </Popconfirm>
            </>
          )}
          {record.hrDecision === 'APPROVED' && (
            <>
              <Button size="small" onClick={() => setQDrawer({ open: true, resumeId: record.resumeId, name: record.candidateName })}>面试题</Button>
              <Button size="small" onClick={() => setRDrawer({ open: true, resumeId: record.resumeId, name: record.candidateName })}>评估报告</Button>
            </>
          )}
        </Space>
      ),
    },
  ]

  if (accessDenied) return <Alert type="warning" showIcon message="岗位不可访问或授权已撤销" description="请联系岗位创建人确认授权。" action={<Button onClick={() => navigate('/recruitment')}>返回岗位列表</Button>} />

  return (
    <div className="recruitment-workbench">
      {position && !canManage && canOperate && <Alert style={{ marginBottom: 16 }} type="info" showIcon message="招聘协作岗位" description="可筛选决策并发起AI任务，模型调用可能计费。你仅可取消自己发起的任务；岗位修改、上传删除和授权由创建人负责。" />}
      {position && !canOperate && <Alert style={{ marginBottom: 16 }} type="info" showIcon message="只读协作岗位" description="你可以查看候选人与已有评估资料。上传、删除、筛选决策、部门修改及AI任务由创建人操作。" />}
      <PageIntro eyebrow="CANDIDATE WORKSPACE" title={position?.title || '岗位候选人工作台'}>{canManage ? '先上传简历，再查看匹配依据并进行人工筛选；通过初筛后，为候选人准备面试题与评估报告。' : canOperate ? '协同完成候选人筛选、匹配及面试资料准备；操作人和任务发起人可在操作记录中查看。' : '查看创建人分享的候选人与已有评估资料，辅助招聘讨论；资料维护和流程操作由创建人负责。'}</PageIntro>
      <div className="workflow-guide workbench-guide">
        {[[resumes.length > 0, '01', '上传简历', `${resumes.length} 份已收录`], [matches.length > 0, '02', '查看匹配', `${matches.length} 位已评分`], [matches.some(m => m.hrDecision === 'APPROVED'), '03', '准备面试', '人工通过后生成面试资料']].map(([done, n, title, hint]) => <div key={n}><span className={`step-number ${done ? 'step-done' : ''}`}>{done ? <CheckOutlined /> : n}</span><div><strong>{title}</strong><p>{hint}</p></div></div>)}
      </div>
      <Card
        title={
          <Space>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/recruitment')} aria-label="返回岗位列表" />
            <span>{position ? position.title : '岗位工作台'}</span>
            {position && <Tag color={position.status === 'OPEN' ? 'green' : 'default'}>{position.status === 'OPEN' ? '招聘中' : '已关闭'}</Tag>}
          </Space>
        }
        extra={
          canOperate && <Space wrap>
            {canManage && <PositionCollaborators key={id} positionId={id} />}
            <PositionActivity key={`activity-${id}`} positionId={id} />
            {canManage && <Upload accept=".pdf,.txt,.md,.markdown" multiple showUploadList={false} customRequest={handleUpload}>
              <Button icon={<UploadOutlined />} loading={uploading}>
                {uploading ? '解析中…' : '上传简历'}
              </Button>
            </Upload>}
            <Button type="primary" icon={<ThunderboltOutlined />} loading={matching || taskLoading} disabled={!!taskError || !resumes.some(r => r.parseStatus === 'PARSED') || position?.status === 'CLOSED'} onClick={handleRunMatch}>
              {taskLoading ? '查询匹配状态…' : taskActive ? (matchTask.status === 'QUEUED' ? '等待匹配…' : '匹配中…') : matchTask?.status === 'FAILED' ? '重试人岗匹配' : '执行人岗匹配'}
            </Button>
          </Space>
        }
      >
        {taskError && <Alert type="warning" showIcon message={taskError} style={{ marginBottom: 16 }}
          action={<Button size="small" onClick={() => setPollVersion(v => v + 1)}>刷新状态</Button>} />}
        {matchTask && <Alert style={{ marginBottom: 16 }} showIcon
          type={matchTask.status === 'FAILED' ? 'error' : matchTask.status === 'SUCCEEDED' ? (matchTask.degradedCount ? 'warning' : 'success') : 'info'}
          message={taskActive ? (matchTask.status === 'QUEUED' ? '匹配任务已排队，刷新页面后仍可查看' : '正在执行匹配，刷新页面后仍可查看')
            : matchTask.status === 'FAILED' ? (matchTask.errorMessage || '匹配失败，可重新匹配')
              : matchTask.status === 'CANCELLED' ? '匹配任务已取消，原结果保留'
                : matchTask.degradedCount ? `匹配完成，${matchTask.degradedCount} 位候选人的语义评分未完成，仅使用规则分` : `匹配完成，共 ${matchTask.candidateCount} 位候选人`}
          description={taskActive ? <>
            <div>{({ QUEUED: '等待执行', RULE_SCORING: '计算规则分', SEMANTIC_SCORING: '评估技能匹配', SAVING: '保存结果' })[matchTask.phase] || '处理中'} · 已处理 {matchTask.completedBatches}/{matchTask.totalBatches} 批</div>
            <Progress percent={Math.min(99, Math.round(100 * matchTask.completedBatches / Math.max(1, matchTask.totalBatches)))} size="small" />
            {matches.length > 0 && <div>下方暂时保留上一轮匹配结果，任务成功后更新。</div>}
          </> : null}
          action={taskActive && matchTask.canCancel !== false ? <Popconfirm title="取消本轮匹配？" description="已发出的模型请求可能仍会计费；任务终止后不写入结果。" okText="取消本轮" cancelText="继续执行" onConfirm={handleCancelMatch}>
            <Button size="small">取消匹配</Button>
          </Popconfirm> : null} />}
        {position && (
          <Descriptions bordered size="small" column={{ xs: 1, sm: 2, lg: 4 }} style={{ marginBottom: 16 }}>
            <Descriptions.Item label="部门">{canManage ? <PositionDepartmentEditor position={position} onUpdated={setPosition} /> : position.department || '未指定部门'}</Descriptions.Item>
            <Descriptions.Item label="城市">{position.city || '-'}</Descriptions.Item>
            <Descriptions.Item label="薪资">{position.salaryMin ?? '-'}~{position.salaryMax ?? '-'}k</Descriptions.Item>
            <Descriptions.Item label="最低年限">{position.minYears ?? '-'} 年 / {position.education || '学历不限'}</Descriptions.Item>
            <Descriptions.Item label="必须技能" span={2}>
              {(position.requiredSkills || []).map((s) => <Tag key={s} color="blue">{s}</Tag>)}
            </Descriptions.Item>
            <Descriptions.Item label="加分技能" span={2}>
              {(position.preferredSkills || []).map((s) => <Tag key={s}>{s}</Tag>)}
            </Descriptions.Item>
          </Descriptions>
        )}

        <Card
          type="inner"
          title={`辅助筛选 · 候选人匹配（${matches.length}）`}
          style={{ marginBottom: 16 }}
          extra={<Button icon={<ReloadOutlined />} onClick={loadMatches} loading={loadingMatches} size="small">刷新</Button>}
        >
          {matches.length === 0 && !loadingMatches ? (
            <Empty description={!canOperate ? '创建人尚未完成人岗匹配。' : resumes.some(r => r.parseStatus === 'PARSED') ? '简历已就绪。点击「执行人岗匹配」，查看技能命中、待核实项与评分依据。' : canManage ? '请先上传简历；成功解析后，即可执行人岗匹配。' : '请联系创建人上传简历，或重新解析失败的简历。'} />
          ) : (
            <Table columns={matchColumns} dataSource={matches} rowKey="id"
              loading={loadingMatches} pagination={false} scroll={{ x: 1100 }} size="middle" />
          )}
        </Card>

        <RecruitmentProgress key={id} positionId={id} canOperate={canOperate} revision={resumes} />
        <Card
          type="inner"
          title={`简历库（${resumes.length}）`}
          extra={<Button icon={<ReloadOutlined />} onClick={loadResumes} loading={loadingResumes} size="small">刷新</Button>}
        >
          {canOperate && <ResumeParseTaskPanel state={resumeParse} selectedIds={selectedResumes} onSubmitted={() => setSelectedResumes([])} closed={position?.status === 'CLOSED'} />}
          <Table columns={resumeColumns} dataSource={resumes} rowKey="id"
            rowSelection={canOperate ? { selectedRowKeys: selectedResumes, onChange: setSelectedResumes,
              getCheckboxProps: r => ({ disabled: !r.retryable || resumeParse.busy || position?.status === 'CLOSED' }) } : undefined}
            loading={loadingResumes} pagination={false} scroll={{ x: 1000 }} size="middle" locale={{ emptyText: canManage ? '还没有候选人。点击上方「上传简历」，把人选整理到这个岗位。' : '创建人尚未上传候选人。' }} />
        </Card>
      </Card>

      <QuestionsDrawer key={qDrawer.resumeId || "questions"}
        onDenied={denyAccess}
        canManage={canOperate}
        open={qDrawer.open}
        onClose={() => setQDrawer({ ...qDrawer, open: false })}
        resumeId={qDrawer.resumeId}
        candidateName={qDrawer.name}
      />
      <ReportDrawer key={rDrawer.resumeId || "report"}
        onDenied={denyAccess}
        canManage={canOperate}
        open={rDrawer.open}
        onClose={() => setRDrawer({ ...rDrawer, open: false })}
        resumeId={rDrawer.resumeId}
        candidateName={rDrawer.name}
      />
    </div>
  )
}
