import { useState, useRef, useEffect, useCallback } from 'react'
import { Card, Input, Button, Space, Typography, Tag, Divider, Switch, message, List, Popconfirm } from 'antd'
import { SendOutlined, ReloadOutlined, PlusOutlined, MessageOutlined, DeleteOutlined } from '@ant-design/icons'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { chatApi, streamChat } from '../api'

const { TextArea } = Input
const { Text, Paragraph } = Typography

// 生成随机 sessionId
const genSessionId = () => `web-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`

// 截取预览文本
const preview = (text, max = 30) => {
  if (!text) return '(空)'
  return text.length > max ? text.slice(0, max) + '...' : text
}

export default function ChatPage() {
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [streamMode, setStreamMode] = useState(true)
  // sessionId 持久化到 localStorage，刷新不丢失
  const [sessionId, setSessionId] = useState(() => {
    return localStorage.getItem('currentSessionId') || genSessionId()
  })
  const [sessions, setSessions] = useState([])
  const msgEndRef = useRef(null)

  // sessionId 变化时存 localStorage
  useEffect(() => {
    localStorage.setItem('currentSessionId', sessionId)
  }, [sessionId])

  // 自动滚动到底部
  useEffect(() => {
    msgEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  // 加载会话列表
  const loadSessions = useCallback(() => {
    chatApi.sessions().then((resp) => {
      setSessions(resp.data || [])
    }).catch(() => {/* 忽略 */})
  }, [])

  useEffect(() => {
    loadSessions()
  }, [loadSessions])

  // 切换会话时加载历史
  useEffect(() => {
    chatApi.history(sessionId).then((resp) => {
      const history = resp.data || []
      setMessages(history.map((h) => ({ role: h.role, content: h.content })))
    }).catch(() => {/* 新会话无历史，忽略 */})
  }, [sessionId])

  const handleNewSession = () => {
    const newId = genSessionId()
    setSessionId(newId)
    setMessages([])
  }

  const handleSelectSession = (sid) => {
    setSessionId(sid)
  }

  const handleDeleteSession = async (sid, e) => {
    // 阻止冒泡到 List.Item 的 onClick
    if (e) e.stopPropagation()
    try {
      await chatApi.deleteSession(sid)
      message.success('会话已删除')
      // 如果删的是当前会话，切到新会话
      if (sid === sessionId) {
        const newId = genSessionId()
        setSessionId(newId)
        setMessages([])
      }
      loadSessions()
    } catch (err) {
      message.error(err.response?.data?.message || '删除失败')
    }
  }

  const handleSend = async () => {
    if (!input.trim() || loading) return
    const userMsg = input.trim()
    setInput('')
    setMessages((prev) => [...prev, { role: 'user', content: userMsg }])
    setLoading(true)

    if (streamMode) {
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
        () => {
          setLoading(false)
          loadSessions() // 刷新会话列表
        },
        (err) => {
          message.error('流式对话失败: ' + err.message)
          setLoading(false)
        },
      )
    } else {
      try {
        const resp = await chatApi.ragChat(userMsg, sessionId)
        setMessages((prev) => [...prev, {
          role: 'assistant',
          content: resp.data.answer,
          sources: resp.data.sources,
        }])
        loadSessions() // 刷新会话列表
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
    <div style={{ display: 'flex', gap: 16, height: 'calc(100vh - 140px)' }}>
      {/* 左侧：会话列表 */}
      <Card
        size="small"
        style={{ width: 260, flexShrink: 0, overflow: 'auto' }}
        title="会话列表"
        extra={
          <Button
            type="primary"
            size="small"
            icon={<PlusOutlined />}
            onClick={handleNewSession}
          >
            新建
          </Button>
        }
      >
        <List
          size="small"
          dataSource={sessions}
          locale={{ emptyText: '暂无历史会话' }}
          renderItem={(item) => (
            <List.Item
              style={{
                cursor: 'pointer',
                padding: '8px 12px',
                borderRadius: 6,
                background: item.sessionId === sessionId ? '#e6f7ff' : 'transparent',
              }}
              onClick={() => handleSelectSession(item.sessionId)}
            >
              <div style={{ width: '100%' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <MessageOutlined style={{ color: '#999', fontSize: 12 }} />
                  <Text
                    ellipsis
                    style={{ fontSize: 13, flex: 1 }}
                  >
                    {item.role === 'user' ? preview(item.content) : preview(item.content)}
                  </Text>
                  <Popconfirm
                    title="删除此会话？"
                    description="将清除该会话的所有历史消息"
                    onConfirm={(e) => handleDeleteSession(item.sessionId, e)}
                    onCancel={(e) => e?.stopPropagation()}
                    okText="删除"
                    cancelText="取消"
                    okButtonProps={{ danger: true }}
                  >
                    <Button
                      type="text"
                      size="small"
                      danger
                      icon={<DeleteOutlined />}
                      onClick={(e) => e.stopPropagation()}
                    />
                  </Popconfirm>
                </div>
                <Text type="secondary" style={{ fontSize: 11 }}>
                  {item.createdAt ? new Date(item.createdAt).toLocaleString('zh-CN', {
                    month: '2-digit', day: '2-digit',
                    hour: '2-digit', minute: '2-digit',
                  }) : ''}
                </Text>
              </div>
            </List.Item>
          )}
        />
      </Card>

      {/* 右侧：聊天区域 */}
      <Card
        title="RAG 对话"
        extra={
          <Space>
            <Text type="secondary">流式</Text>
            <Switch checked={streamMode} onChange={setStreamMode} />
            <Button icon={<ReloadOutlined />} onClick={handleClear}>清空</Button>
          </Space>
        }
        style={{ flex: 1, display: 'flex', flexDirection: 'column' }}
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
              className={msg.role === 'assistant' ? 'markdown-body' : ''}
              style={{
                maxWidth: '75%',
                padding: '8px 16px',
                borderRadius: 8,
                background: msg.role === 'user' ? '#e6f7ff' : '#f6ffed',
                marginTop: 4,
                wordBreak: 'break-word',
              }}
            >
              {msg.role === 'assistant' ? (
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{msg.content}</ReactMarkdown>
              ) : (
                <span style={{ whiteSpace: 'pre-wrap' }}>{msg.content}</span>
              )}
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
    </div>
  )
}
