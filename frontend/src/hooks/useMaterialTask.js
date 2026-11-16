import { useEffect, useRef, useState } from 'react'
import { message } from 'antd'
import { recruitmentApi } from '../api'

const isActive = task => ['QUEUED', 'RUNNING'].includes(task?.status)

export default function useMaterialTask(open, resumeId, kind, onComplete) {
  const [task, setTask] = useState(null)
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const [version, setVersion] = useState(0)
  const requestKey = useRef(null)
  const inFlight = useRef(false)
  const visible = useRef(open)
  const revision = useRef(0)
  const cancellingRef = useRef(false)
  const [cancelling, setCancelling] = useState(false)
  const completed = useRef(onComplete)
  completed.current = onComplete
  visible.current = open

  useEffect(() => {
    if (!open || !resumeId) { setLoading(false); return }
    let mounted = true
    let timer
    let lastSuccess = ''
    const controller = new AbortController()
    setLoading(true)
    const poll = async () => {
      try {
        const snapshot = revision.current
        const { data } = await recruitmentApi.latestMaterialTask(resumeId, kind, { signal: controller.signal, timeout: 10000 })
        if (!mounted || snapshot !== revision.current) return
        setTask(data || null)
        setError('')
        if (data?.status === 'SUCCEEDED' && data.id !== lastSuccess) {
          lastSuccess = data.id
          completed.current()
        }
        if (data && !isActive(data) && data.requestKey === requestKey.current) requestKey.current = null
      } catch {
        if (mounted) setError('暂时无法获取生成任务状态，正在重试，请勿重复提交。')
      } finally {
        if (mounted) { setLoading(false); timer = setTimeout(poll, 2000) }
      }
    }
    poll()
    return () => { mounted = false; clearTimeout(timer); controller.abort() }
  }, [open, resumeId, kind, version])

  const submit = async () => {
    if (inFlight.current || loading || error || isActive(task)) return
    inFlight.current = true
    revision.current++
    setSubmitting(true)
    requestKey.current ||= globalThis.crypto?.randomUUID?.() || `material_${Date.now()}_${Math.random().toString(36).slice(2)}`
    try {
      const { data } = await recruitmentApi.submitMaterialTask(resumeId, kind, requestKey.current)
      requestKey.current = null
      if (!visible.current) return
      setTask(data)
      if (data.status === 'SUCCEEDED') onComplete()
    } catch (err) {
      if (!visible.current) return
      if (!err.response || err.response.status >= 500) message.warning('生成提交结果尚未确认，再试会复用请求标识')
      else { requestKey.current = null; message.error(err.response.data?.message || '无法提交生成任务') }
    } finally { inFlight.current = false; setSubmitting(false) }
  }
  const cancel = async () => {
    if (!task?.id || cancellingRef.current) return
    cancellingRef.current = true
    setCancelling(true)
    revision.current++
    try {
      const { data } = await recruitmentApi.cancelMaterialTask(task.id)
      if (visible.current) setTask(data)
    } catch { message.error('取消结果尚未确认，请查看最新状态') }
    finally {
      revision.current++
      cancellingRef.current = false
      setCancelling(false)
      if (visible.current) setVersion(v => v + 1)
    }
  }
  return { task, loading, submitting, cancelling, error, active: isActive(task), submit, cancel,
    refresh: () => setVersion(v => v + 1) }
}
