import { useState, useEffect, useCallback } from 'react'
import { Button, Card, Drawer, Descriptions, Typography, Tag, Empty, Spin, Alert } from 'antd'
import { FileMarkdownOutlined } from '@ant-design/icons'
import { interviewApi } from '../api.js'
import useMaterialTask from '../hooks/useMaterialTask.js'
import MaterialTaskStatus from './MaterialTaskStatus.jsx'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

const { Text, Paragraph } = Typography
const recommendTag = (r) => {
  if (r === 'STRONG_YES') return <Tag color="green">强烈推荐</Tag>
  if (r === 'YES') return <Tag color="blue">推荐</Tag>
  if (r === 'NO') return <Tag color="red">不推荐</Tag>
  return null
}

export default function ReportDrawer({ open, onClose, resumeId, candidateName, canManage, onDenied }) {
  const [report, setReport] = useState(null)
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState('')

  const load = useCallback(async () => {
    if (!resumeId) return
    setLoading(true)
    try {
      const resp = await interviewApi.getReport(resumeId)
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
      footer={<Button onClick={onClose}>关闭窗口</Button>}
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

