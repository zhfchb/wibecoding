import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import App from './App.jsx'
import { AuthProvider } from './context/AuthContext.jsx'
import './index.css'

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN} theme={{ token: { colorPrimary: '#245b49', colorInfo: '#245b49', colorSuccess: '#3d7b60', colorText: '#23362e', colorTextSecondary: '#738079', colorBorder: '#dce3dc', colorBgLayout: '#f5f5f0', borderRadius: 8, fontFamily: '"PingFang SC", "Microsoft YaHei", sans-serif' }, components: { Button: { primaryShadow: 'none', controlHeight: 38 }, Card: { headerFontSize: 16 }, Table: { headerBg: '#f6f7f3', cellPaddingBlock: 18 }, Menu: { itemSelectedBg: '#e9f0e9', itemSelectedColor: '#245b49', itemHeight: 46 } } }}>
      <BrowserRouter>
        <AuthProvider>
          <App />
        </AuthProvider>
      </BrowserRouter>
    </ConfigProvider>
  </React.StrictMode>,
)
