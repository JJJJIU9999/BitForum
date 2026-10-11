import React from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import App from './App.jsx'
import './styles/variables.css'
import './styles/layout.css'
import './styles/ai-panel.css'
import './styles/ai-trace.css'
import './style.css'
import './styles/topics.css'

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>,
)
