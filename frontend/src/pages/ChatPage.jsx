import { useState, useRef, useEffect } from 'react'
import { Card, Input, Button, Space, Typography, Tag, Divider, Switch, message } from 'antd'
import { SendOutlined, ReloadOutlined } from '@ant-design/icons'
import { chatApi, streamChat } from '../api'

const { TextArea } = Input
const { Text, Paragraph } = Typography

// 生成随机 sessionId
const genSessionId = () => `web-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`

export default function ChatPage() {
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [streamMode, setStreamMode] = useState(true)
  const [sessionId] = useState(genSessionId)
  const msgEndRef = useRef(null)

  // 自动滚动到底部
  useEffect(() => {
    msgEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  // 加载历史
  useEffect(() => {
    chatApi.history(sessionId).then((resp) => {
      const history = resp.data || []
      if (history.length > 0) {
        setMessages(history.map((h) => ({ role: h.role, content: h.content })))
      }
    }).catch(() => {/* 新会话无历史，忽略 */})
  }, [sessionId])

  const handleSend = async () => {
    if (!input.trim() || loading) return
    const userMsg = input.trim()
    setInput('')
    setMessages((prev) => [...prev, { role: 'user', content: userMsg }])
    setLoading(true)

    if (streamMode) {
      // SSE 流式：逐 token 追加到 assistant 消息
      setMessages((prev) => [...prev, { role: 'assistant', content: '' }])
      let idx = -1
      setMessages((prev) => {
        idx = prev.length - 1
        return prev
      })
      await streamChat(
        userMsg,
        sessionId,
        (token) => {
          setMessages((prev) => {
            const next = [...prev]
            if (idx >= 0 && next[idx]) {
              next[idx] = { ...next[idx], content: next[idx].content + token }
            }
            return next
          })
        },
        () => setLoading(false),
        (err) => {
          message.error('流式对话失败: ' + err.message)
          setLoading(false)
        },
      )
    } else {
      // 非流式：一次性返回
      try {
        const resp = await chatApi.ragChat(userMsg, sessionId)
        setMessages((prev) => [...prev, {
          role: 'assistant',
          content: resp.data.answer,
          sources: resp.data.sources,
        }])
      } catch (err) {
        message.error(err.response?.data?.message || '对话失败')
      } finally {
        setLoading(false)
      }
    }
  }

  const handleClear = () => {
    setMessages([])
  }

  return (
    <Card
      title="RAG 对话"
      extra={
        <Space>
          <Text type="secondary">流式</Text>
          <Switch checked={streamMode} onChange={setStreamMode} />
          <Button icon={<ReloadOutlined />} onClick={handleClear}>清空</Button>
        </Space>
      }
      style={{ height: 'calc(100vh - 140px)', display: 'flex', flexDirection: 'column' }}
      bodyStyle={{ flex: 1, overflow: 'auto', padding: 24 }}
    >
      <div style={{ marginBottom: 16 }}>
        <Text type="secondary">会话 ID：{sessionId}</Text>
      </div>
      {messages.length === 0 && (
        <div style={{ textAlign: 'center', color: '#999', padding: 48 }}>
          输入问题开始对话，支持基于知识库的 RAG 检索增强生成
        </div>
      )}
      {messages.map((msg, i) => (
        <div
          key={i}
          style={{
            marginBottom: 16,
            display: 'flex',
            flexDirection: 'column',
            alignItems: msg.role === 'user' ? 'flex-end' : 'flex-start',
          }}
        >
          <Tag color={msg.role === 'user' ? 'blue' : 'green'}>
            {msg.role === 'user' ? '我' : '助手'}
          </Tag>
          <div
            style={{
              maxWidth: '75%',
              padding: '8px 16px',
              borderRadius: 8,
              background: msg.role === 'user' ? '#e6f7ff' : '#f6ffed',
              marginTop: 4,
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
            }}
          >
            {msg.content}
            {msg.sources && msg.sources.length > 0 && (
              <>
                <Divider style={{ margin: '8px 0' }} />
                <Text type="secondary" style={{ fontSize: 12 }}>来源：</Text>
                {msg.sources.map((s, j) => (
                  <Tag key={j} style={{ fontSize: 11 }}>
                    {s.fileName}#{s.chunkIndex}（{s.score?.toFixed?.(2) || s.score}）
                  </Tag>
                ))}
              </>
            )}
          </div>
        </div>
      ))}
      <div ref={msgEndRef} />
      <div style={{ marginTop: 16, display: 'flex', gap: 8 }}>
        <TextArea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onPressEnter={(e) => {
            if (!e.shiftKey) {
              e.preventDefault()
              handleSend()
            }
          }}
          placeholder="输入问题，回车发送（Shift+回车换行）"
          autoSize={{ minRows: 1, maxRows: 4 }}
          disabled={loading}
        />
        <Button
          type="primary"
          icon={<SendOutlined />}
          onClick={handleSend}
          loading={loading}
        >
          发送
        </Button>
      </div>
    </Card>
  )
}
