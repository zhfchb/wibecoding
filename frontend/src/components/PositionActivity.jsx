import { useState } from 'react'
import { Alert, Button, Modal, Table } from 'antd'
import api from '../api'

const labels = { INTERVIEW_SCHEDULED: '\u5b89\u6392\u9762\u8bd5\u65f6\u95f4', INTERVIEW_RESCHEDULED: '\u9762\u8bd5\u6539\u671f', INTERVIEW_CANCELLED: '\u53d6\u6d88\u6392\u671f', INTERVIEW_COMPLETED: '\u786e\u8ba4\u9762\u8bd5\u5b8c\u6210', INTERVIEW_NO_SHOW: '\u6807\u8bb0\u672a\u5230\u573a', INTERVIEW_ASSIGNED: '\u5206\u914d\u9762\u8bd5', INTERVIEW_REVOKED: '\u64a4\u9500\u9762\u8bd5', INTERVIEW_EVALUATED: '\u63d0\u4ea4\u9762\u8bd5\u8bc4\u4ef7', MATCH_SUBMITTED: '发起人岗匹配', PARSE_SUBMITTED: '发起简历重解析', MATERIAL_SUBMITTED: '发起面试资料生成', MATCH_CANCELLED: '取消人岗匹配', PARSE_CANCELLED: '取消简历重解析', MATERIAL_CANCELLED: '取消面试资料生成', CANDIDATE_APPROVED: '通过候选人', CANDIDATE_REJECTED: '淘汰候选人' }
export default function PositionActivity({ positionId }) {
  const [open, setOpen] = useState(false)
  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)
  const load = async () => {
    setLoading(true); setError(false)
    try { setRows((await api.get(`/recruitment/positions/${positionId}/activity`)).data) }
    catch { setRows([]); setError(true) }
    finally { setLoading(false) }
  }
  return <><Button onClick={() => { setOpen(true); load() }}>操作记录</Button>
    <Modal title="最近100条招聘操作" open={open} onCancel={() => setOpen(false)} footer={<Button onClick={load} loading={loading}>刷新记录</Button>} width={640}>
      {error ? <Alert type="warning" message="无法读取操作记录，请重试或确认协作权限" /> : <Table loading={loading} size="small" rowKey="id" dataSource={rows} pagination={{ pageSize: 10 }} scroll={{ x: 460 }} columns={[
        { title: '操作人', dataIndex: 'username', render: v => v || '历史账号' },
        { title: '操作', dataIndex: 'action', render: v => labels[v] || v },
        { title: '时间', dataIndex: 'createdAt', render: v => v?.replace('T', ' ') },
      ]} />}
    </Modal></>
}
