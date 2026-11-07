import { useCallback, useEffect, useRef, useState } from 'react'
import { message } from 'antd'
import { recruitmentApi } from '../api'

const active = task => ['QUEUED', 'RUNNING'].includes(task?.status)

export default function useResumeParseTask(positionId, onUpdated) {
  const [task, setTask] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [refresh, setRefresh] = useState(0)
  const pending = useRef(null)
  const submittingRef = useRef(false)
  const currentPosition = useRef(positionId)
  currentPosition.current = positionId

  useEffect(() => {
    let mounted = true
    let timer
    let signature = ''
    pending.current = null
    setTask(null)
    setLoading(true)
    const poll = async () => {
      try {
        const { data } = await recruitmentApi.latestResumeParseTask(positionId)
        if (!mounted) return
        setTask(data || null)
        setError('')
        const next = data ? `${data.id}:${data.status}:${data.items.map(i => i.status).join(',')}` : ''
        if (next && next !== signature) { signature = next; onUpdated() }
        if (data && !active(data) && data.requestKey === pending.current?.key) pending.current = null
      } catch {
        if (mounted) setError('暂时无法获取重新解析任务状态，正在重试，请勿重复提交。')
      } finally {
        if (mounted) { setLoading(false); timer = setTimeout(poll, 2000) }
      }
    }
    poll()
    return () => { mounted = false; clearTimeout(timer) }
  }, [positionId, onUpdated, refresh])

  const submit = useCallback(async ids => {
    if (loading || error || active(task) || submittingRef.current || ids.length === 0 || ids.length > 20) return false
    const sorted = [...ids].sort((a, b) => a - b)
    if (pending.current && JSON.stringify(pending.current.ids) !== JSON.stringify(sorted)) {
      message.warning('上次提交结果尚未确认，请先重试原来的选择或刷新任务状态')
      return false
    }
    pending.current ||= { key: globalThis.crypto?.randomUUID?.() || `resume_${Date.now()}_${Math.random().toString(36).slice(2)}`, ids: sorted }
    submittingRef.current = true
    setSubmitting(true)
    try {
      const { data } = await recruitmentApi.submitResumeParseTask(positionId, pending.current.key, sorted)
      if (currentPosition.current !== positionId) return false
      setTask(data)
      pending.current = null
      onUpdated()
      return true
    } catch (err) {
      if (currentPosition.current !== positionId) return false
      if (!err.response || err.response.status >= 500) {
        message.warning('重新解析提交结果尚未确认，重试相同选择会复用请求标识')
      } else {
        pending.current = null
        message.error(err.response.data?.message || '无法提交重新解析任务')
      }
      return false
    } finally { submittingRef.current = false; setSubmitting(false) }
  }, [positionId, loading, error, task, onUpdated])

  const cancel = async () => {
    try {
      const { data } = await recruitmentApi.cancelResumeParseTask(task.id)
      if (currentPosition.current !== positionId) return
      setTask(data)
      onUpdated()
    } catch { message.error('取消结果尚未确认，请查看最新状态') }
  }
  return { task, error, loading, submitting, active: active(task), busy: loading || submitting || active(task) || !!error,
    submit, cancel, refresh: () => setRefresh(v => v + 1) }
}
