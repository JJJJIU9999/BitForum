import React from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import App from './App.jsx'
import './styles/variables.css'
import './styles/layout.css'
import './styles/ai-panel.css'
import './styles/ai-trace.css'
import './style.css'
import './styles/topics.css'

const router = createBrowserRouter([{ path: '*', element: <App /> }])

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <RouterProvider router={router} />
  </React.StrictMode>,
)
