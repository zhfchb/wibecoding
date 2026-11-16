import { useState } from 'react'
import { Button, Typography } from 'antd'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

export default function SourcePreview({ source, index }) {
  const [raw, setRaw] = useState(false)
  return (
    <details style={{ marginTop: 8 }}>
      <summary style={{ cursor: 'pointer', overflowWrap: 'anywhere' }}>
        [{index + 1}] {source.fileName || '未命名来源'} · 引用片段 {source.chunkIndex}
      </summary>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>以下为检索命中的引用片段，并非完整文档。</Typography.Text>
      <Button type="link" size="small" onClick={() => setRaw(value => !value)}>{raw ? '返回预览' : '查看原文'}</Button>
      <div className="source-preview" style={{ maxHeight: 280, overflow: 'auto', overflowWrap: 'anywhere', marginTop: 8, padding: 12, background: '#fff', border: '1px solid #e5ebe5', borderRadius: 6 }}>
        {raw ? <pre style={{ whiteSpace: 'pre-wrap', margin: 0, fontFamily: 'inherit' }}>{source.text || '未提供原文片段'}</pre>
          : <div className="markdown-body"><ReactMarkdown remarkPlugins={[remarkGfm]}>{source.text || '未提供原文片段'}</ReactMarkdown></div>}
      </div>
    </details>
  )
}
