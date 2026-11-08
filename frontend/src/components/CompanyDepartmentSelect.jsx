import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Select, Space, Typography } from 'antd'
import api from '../api'

export default function CompanyDepartmentSelect({ value, onChange, refreshKey, placeholder = '选择公司部门（选填）', ...props }) {
  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)
  const version = useRef(0)
  const load = async () => {
    const current = ++version.current
    setLoading(true); setError(false)
    try {
      const { data } = await api.get('/company/departments', { params: { activeOnly: true } })
      if (current !== version.current) return
      setRows(data)
      // A previously selected department may have been disabled while the form stayed open.
      if (value != null && !data.some(row => row.id === value)) onChange?.(undefined)
    } catch { if (current === version.current) { setError(true); setRows([]); onChange?.(undefined) } }
    finally { if (current === version.current) setLoading(false) }
  }
  useEffect(() => { load(); return () => { version.current++ } }, [refreshKey])
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Select {...props} aria-label="公司部门" value={value} onChange={onChange} style={{ width: '100%' }}
      allowClear showSearch optionFilterProp="label" loading={loading} disabled={loading || error}
      placeholder={placeholder} options={rows.map(row => ({ value: row.id, label: row.name }))}
      notFoundContent={loading ? '加载中' : '暂无已启用部门'} />
    {error ? <Alert type="error" message="部门加载失败" action={<Button size="small" onClick={load}>重试</Button>} />
      : !loading && !rows.length ? <Typography.Text type="secondary">暂无已启用部门，请联系公司管理员维护。</Typography.Text> : null}
    {!error && <Button type="link" size="small" disabled={loading} onClick={load}>刷新可用部门</Button>}
  </Space>
}
