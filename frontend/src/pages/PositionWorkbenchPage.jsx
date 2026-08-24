import { useState, useEffect, useCallback } from 'react'
import {
  Card, Table, Button, message, Space, Tag, Popconfirm, Upload, Drawer,
  Descriptions, Progress, Typography, Tooltip, Empty, Spin,
} from 'antd'
import {
  ArrowLeftOutlined, ReloadOutlined, DeleteOutlined, UploadOutlined,
  ThunderboltOutlined, CheckOutlined, CloseOutlined, FileMarkdownOutlined,
} from '@ant-design/icons'
import { useParams, useNavigate } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { recruitmentApi } from '../api'

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
function QuestionsDrawer({ open, onClose, resumeId, candidateName }) {
  const [questions, setQuestions] = useState([])
  const [loading, setLoading] = useState(false)
  const [generating, setGenerating] = useState(false)

  const load = useCallback(async () => {
    if (!resumeId) return
    setLoading(true)
    try {
      const resp = await recruitmentApi.listQuestions(resumeId)
      setQuestions(resp.data || [])
    } catch (err) {
      if (err.response?.status !== 400) message.error('加载面试题失败')
      setQuestions([])
    } finally {
      setLoading(false)
    }
  }, [resumeId])

  useEffect(() => {
    if (open) load()
  }, [open, load])

  const handleGenerate = async () => {
    setGenerating(true)
    try {
      const resp = await recruitmentApi.generateQuestions(resumeId)
      setQuestions(resp.data || [])
      message.success(`已生成 ${resp.data.length} 道面试题`)
    } catch (err) {
      const errMsg = err.response?.data?.message || err.message || '生成失败'
      message.error(`生成失败：${errMsg}`)
    } finally {
      setGenerating(false)
    }
  }

  return (
    <Drawer
      title={`定制面试题 — ${candidateName || ''}`}
      open={open}
      onClose={onClose}
      width={640}
      extra={
        <Button
          type="primary" icon={<ThunderboltOutlined />}
          loading={generating} onClick={handleGenerate}
        >
          {generating ? 'LLM 出题中…' : '重新生成'}
        </Button>
      }
    >
      <Spin spinning={loading}>
        {questions.length === 0 && !loading ? (
          <Empty description="尚未生成面试题，点击右上角生成" />
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
function ReportDrawer({ open, onClose, resumeId, candidateName }) {
  const [report, setReport] = useState(null)
  const [loading, setLoading] = useState(false)
  const [generating, setGenerating] = useState(false)

  const load = useCallback(async () => {
    if (!resumeId) return
    setLoading(true)
    try {
      const resp = await recruitmentApi.getReport(resumeId)
      setReport(resp.data)
    } catch (err) {
      setReport(null)
      if (err.response?.status !== 400) message.error('加载报告失败')
    } finally {
      setLoading(false)
    }
  }, [resumeId])

  useEffect(() => {
    if (open) load()
  }, [open, load])

  const handleGenerate = async () => {
    setGenerating(true)
    try {
      const resp = await recruitmentApi.generateReport(resumeId)
      setReport(resp.data)
      message.success(`报告已生成：${resp.data.finalRecommend}`)
    } catch (err) {
      const errMsg = err.response?.data?.message || err.message || '生成失败'
      message.error(`生成失败：${errMsg}`)
    } finally {
      setGenerating(false)
    }
  }

  return (
    <Drawer
      title={`候选人评估报告 — ${candidateName || ''}`}
      open={open}
      onClose={onClose}
      width={720}
      extra={
        <Button
          type="primary" icon={<FileMarkdownOutlined />}
          loading={generating} onClick={handleGenerate}
        >
          {generating ? 'LLM 生成中…' : report ? '重新生成' : '生成报告'}
        </Button>
      }
    >
      <Spin spinning={loading}>
        {!report && !loading ? (
          <Empty description="尚未生成评估报告，点击右上角生成" />
        ) : report ? (
          <>
            <Descriptions bordered size="small" column={2} style={{ marginBottom: 16 }}>
              <Descriptions.Item label="匹配分" span={1}>
                <Text strong style={{ fontSize: 18 }}>{report.matchScore}</Text>
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
  const [resumes, setResumes] = useState([])
  const [matches, setMatches] = useState([])
  const [loadingResumes, setLoadingResumes] = useState(false)
  const [loadingMatches, setLoadingMatches] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [matching, setMatching] = useState(false)
  const [qDrawer, setQDrawer] = useState({ open: false, resumeId: null, name: null })
  const [rDrawer, setRDrawer] = useState({ open: false, resumeId: null, name: null })

  const loadResumes = useCallback(async () => {
    setLoadingResumes(true)
    try {
      const resp = await recruitmentApi.listResumes(id)
      setResumes(resp.data || [])
    } catch (err) {
      message.error('加载简历列表失败')
    } finally {
      setLoadingResumes(false)
    }
  }, [id])

  const loadMatches = useCallback(async () => {
    setLoadingMatches(true)
    try {
      const resp = await recruitmentApi.listMatchResults(id)
      setMatches(resp.data || [])
    } catch (err) {
      if (err.response?.status !== 400) message.error('加载匹配结果失败')
      setMatches([])
    } finally {
      setLoadingMatches(false)
    }
  }, [id])

  useEffect(() => {
    recruitmentApi.getPosition(id)
      .then((resp) => setPosition(resp.data))
      .catch(() => message.error('加载岗位信息失败'))
    loadResumes()
    loadMatches()
  }, [id, loadResumes, loadMatches])

  const handleUpload = async ({ file, onSuccess, onError }) => {
    setUploading(true)
    try {
      const resp = await recruitmentApi.uploadResumes(id, [file])
      const { successCount, failedCount } = resp.data
      message.success(`上传完成：成功 ${successCount} 份${failedCount ? `，失败 ${failedCount} 份` : ''}`)
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
    setMatching(true)
    try {
      const resp = await recruitmentApi.runMatch(id)
      setMatches(resp.data || [])
      message.success(`匹配完成，共 ${resp.data.length} 位候选人`)
    } catch (err) {
      const errMsg = err.response?.data?.message || err.message || '匹配失败'
      message.error(`人岗匹配失败：${errMsg}`)
    } finally {
      setMatching(false)
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
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '姓名', dataIndex: 'candidateName', width: 100, render: (v) => <Text strong>{v || '-'}</Text> },
    {
      title: '解析状态', dataIndex: 'parseStatus', width: 100,
      render: (s, r) => s === 'PARSED'
        ? <Tag color="green">已解析</Tag>
        : <Tooltip title={r.parseError}><Tag color={s === 'FAILED' ? 'red' : 'orange'}>{s === 'FAILED' ? '失败' : '处理中'}</Tag></Tooltip>,
    },
    { title: '技能', dataIndex: 'skills', ellipsis: true, render: (skills) => (skills || []).map((s) => <Tag key={s}>{s}</Tag>) },
    { title: '年限', dataIndex: 'yearsExp', width: 70, render: (v) => (v == null ? '-' : `${v}年`) },
    { title: '学历', dataIndex: 'education', width: 80, render: (v) => v || '-' },
    { title: '上家', dataIndex: 'lastCompany', width: 110, render: (v) => v || '-' },
    {
      title: '操作', key: 'action', width: 160,
      render: (_, record) => (
        <Space>
          <Button type="link" size="small" onClick={() => setQDrawer({ open: true, resumeId: record.id, name: record.candidateName })}>
            面试题
          </Button>
          <Button type="link" size="small" onClick={() => setRDrawer({ open: true, resumeId: record.id, name: record.candidateName })}>
            评估报告
          </Button>
          <Popconfirm title="删除此简历？" description="将连带删除向量数据与匹配结果" onConfirm={() => handleDeleteResume(record.id)}>
            <Button type="link" size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ]

  const matchColumns = [
    {
      title: '排名', dataIndex: 'rankNo', width: 70,
      render: (r) => (r === 1 ? <Tag color="gold">🥇 1</Tag> : r === 2 ? <Tag color="default">🥈 2</Tag> : r === 3 ? <Tag color="orange">🥉 3</Tag> : r),
    },
    { title: '候选人', dataIndex: 'candidateName', width: 110, render: (v) => <Text strong>{v || '-'}</Text> },
    {
      title: '综合分', dataIndex: 'matchScore', width: 140, sorter: (a, b) => a.matchScore - b.matchScore,
      render: (score, r) => (
        <Space>
          <Progress type="circle" percent={Number(score)} size={44}
            strokeColor={score >= 85 ? '#52c41a' : score >= 60 ? '#1677ff' : '#ff4d4f'} format={(p) => p} />
          {r.scoreDetail && (
            <Tooltip title={`规则分 ${r.scoreDetail.ruleScore} + LLM 语义分 ${r.scoreDetail.llmScore}（技能${r.scoreDetail.skillScore}/加分${r.scoreDetail.preferredScore}/年限${r.scoreDetail.yearsScore}/学历${r.scoreDetail.educationScore}）${r.scoreDetail.llmNotes ? `；LLM：${r.scoreDetail.llmNotes}` : ''}`}>
              <Text type="secondary" style={{ fontSize: 12 }}>明细</Text>
            </Tooltip>
          )}
        </Space>
      ),
    },
    { title: '年限/学历', width: 120, render: (_, r) => `${r.yearsExp ?? '-'}年 / ${r.education || '-'}` },
    {
      title: '技能命中', dataIndex: 'skillHits', ellipsis: true,
      render: (hits) => (hits || []).map((s) => <Tag key={s} color="green">{s}</Tag>),
    },
    {
      title: '技能缺失', dataIndex: 'skillGaps', ellipsis: true,
      render: (gaps) => (gaps || []).map((s) => <Tag key={s} color="red">{s}</Tag>),
    },
    { title: 'HR 决策', dataIndex: 'hrDecision', width: 100, render: decisionTag },
    {
      title: '操作', key: 'action', width: 230,
      render: (_, record) => (
        <Space>
          {record.hrDecision === 'PENDING' && (
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

  return (
    <div>
      <Card
        title={
          <Space>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/recruitment')} />
            <span>{position ? position.title : '岗位工作台'}</span>
            {position && <Tag color={position.status === 'OPEN' ? 'green' : 'default'}>{position.status === 'OPEN' ? '招聘中' : '已关闭'}</Tag>}
          </Space>
        }
        extra={
          <Space>
            <Upload accept=".pdf,.txt,.md,.markdown" multiple showUploadList={false} customRequest={handleUpload}>
              <Button icon={<UploadOutlined />} loading={uploading}>
                {uploading ? '解析中…' : '上传简历'}
              </Button>
            </Upload>
            <Button type="primary" icon={<ThunderboltOutlined />} loading={matching} onClick={handleRunMatch}>
              {matching ? '匹配中…' : '执行人岗匹配'}
            </Button>
          </Space>
        }
      >
        {position && (
          <Descriptions bordered size="small" column={4} style={{ marginBottom: 16 }}>
            <Descriptions.Item label="部门">{position.department || '-'}</Descriptions.Item>
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
          title={`候选人匹配榜（${matches.length}）`}
          style={{ marginBottom: 16 }}
          extra={<Button icon={<ReloadOutlined />} onClick={loadMatches} loading={loadingMatches} size="small">刷新</Button>}
        >
          {matches.length === 0 && !loadingMatches ? (
            <Empty description="尚未执行匹配，点击右上角「执行人岗匹配」" />
          ) : (
            <Table columns={matchColumns} dataSource={matches} rowKey="id"
              loading={loadingMatches} pagination={false} size="middle" />
          )}
        </Card>

        <Card
          type="inner"
          title={`简历库（${resumes.length}）`}
          extra={<Button icon={<ReloadOutlined />} onClick={loadResumes} loading={loadingResumes} size="small">刷新</Button>}
        >
          <Table columns={resumeColumns} dataSource={resumes} rowKey="id"
            loading={loadingResumes} pagination={false} size="middle" />
        </Card>
      </Card>

      <QuestionsDrawer
        open={qDrawer.open}
        onClose={() => setQDrawer({ ...qDrawer, open: false })}
        resumeId={qDrawer.resumeId}
        candidateName={qDrawer.name}
      />
      <ReportDrawer
        open={rDrawer.open}
        onClose={() => setRDrawer({ ...rDrawer, open: false })}
        resumeId={rDrawer.resumeId}
        candidateName={rDrawer.name}
      />
    </div>
  )
}
