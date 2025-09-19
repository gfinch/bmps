import { useEffect, useRef, useState } from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import Layout from './components/Layout'
import ConfigurationPage from './pages/ConfigurationPage'
import PlanningChartPage from './pages/PlanningChartPage'
import TradingChartPage from './pages/TradingChartPage'
import ResultsPage from './pages/ResultsPage'
import { useEventStore } from './stores/eventStore'

function App() {
  const wsRef = useRef(null)
  const [isConnected, setIsConnected] = useState(false)
  const reconnectTimeoutRef = useRef(null)
  const { addEvent } = useEventStore()

  const connectWebSocket = () => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      return // Already connected
    }

    try {
      console.log('Connecting to WebSocket...')
      const ws = new WebSocket('ws://localhost:8080/ws')
      wsRef.current = ws
      window.globalWebSocket = ws // Make available globally

      ws.onopen = () => {
        console.log('WebSocket connected')
        console.log('Setting window.globalWebSocket to:', ws)
        setIsConnected(true)
        window.globalWebSocket = ws // Make available globally
        
        // Clear any pending reconnect
        if (reconnectTimeoutRef.current) {
          clearTimeout(reconnectTimeoutRef.current)
          reconnectTimeoutRef.current = null
        }
      }

      ws.onmessage = (event) => {
        console.log('Raw WebSocket message:', event.data)
        try {
          const parsed = JSON.parse(event.data)
          console.log('Parsed message:', parsed)
          
          // Log specific event types for debugging
          if (parsed.eventType) {
            console.log('Event type:', parsed.eventType)
          }
          if (parsed.phase) {
            console.log('Phase:', parsed.phase)
          }
          if (parsed.event) {
            console.log('Nested event:', parsed.event)
          }

          // Add event to store for processing
          if (parsed.phase && parsed.event) {
            addEvent({ phase: parsed.phase, event: parsed.event })
          } else if (parsed.eventType) {
            // Direct event format
            addEvent({ phase: 'Planning', event: parsed })
          }
        } catch (parseError) {
          console.error('Failed to parse WebSocket message:', parseError)
        }
      }

      ws.onerror = (error) => {
        console.error('WebSocket error:', error)
        setIsConnected(false)
      }

      ws.onclose = (event) => {
        console.log('WebSocket closed:', event.code, event.reason)
        setIsConnected(false)
        wsRef.current = null
        window.globalWebSocket = null

        // Auto-reconnect after 2 seconds
        if (!reconnectTimeoutRef.current) {
          reconnectTimeoutRef.current = setTimeout(() => {
            reconnectTimeoutRef.current = null
            connectWebSocket()
          }, 2000)
        }
      }
    } catch (error) {
      console.error('Failed to create WebSocket:', error)
      setIsConnected(false)
    }
  }

  // Connect when app loads
  useEffect(() => {
    connectWebSocket()

    // Cleanup on unmount
    return () => {
      if (reconnectTimeoutRef.current) {
        clearTimeout(reconnectTimeoutRef.current)
      }
      if (wsRef.current) {
        wsRef.current.close()
      }
    }
  }, [])

  return (
    <BrowserRouter>
      <div className="min-h-screen bg-gray-50">
        {/* Connection Status Indicator */}
        <div className="fixed top-4 right-4 z-50">
          <div 
            className={`w-3 h-3 rounded-full ${
              isConnected ? 'bg-green-500' : 'bg-red-500'
            }`}
            title={isConnected ? 'Connected' : 'Disconnected'}
          />
        </div>
        
        <Layout>
          <Routes>
            <Route path="/" element={<Navigate to="/config" replace />} />
            <Route path="/config" element={<ConfigurationPage />} />
            <Route path="/planning" element={<PlanningChartPage />} />
            <Route path="/trading" element={<TradingChartPage />} />
            <Route path="/results" element={<ResultsPage />} />
          </Routes>
        </Layout>
      </div>
    </BrowserRouter>
  )
}

export default App
