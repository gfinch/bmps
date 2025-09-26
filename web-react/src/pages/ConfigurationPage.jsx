import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Calendar, Clock } from 'lucide-react'
import { useEventStore } from '../stores/eventStore'

export default function ConfigurationPage() {
  // Get today's date in YYYY-MM-DD format
  const today = new Date().toISOString().split('T')[0]
  
  const [config, setConfig] = useState({
    tradingDate: today,
    planningDays: 3
  })

  const navigate = useNavigate()
  const { clearEvents } = useEventStore()

  const handleStartPlanning = () => {
    // Store configuration for other pages to use
    localStorage.setItem('tradingConfig', JSON.stringify(config))
    
    // Clear any existing events - complete reset
    clearEvents('Planning')
    clearEvents('Trading')
    
    // Send start command via global WebSocket
    console.log('Checking WebSocket connection...')
    console.log('window.globalWebSocket:', window.globalWebSocket)
    console.log('readyState:', window.globalWebSocket?.readyState)
    console.log('WebSocket.OPEN constant:', WebSocket.OPEN)
    
    if (window.globalWebSocket && window.globalWebSocket.readyState === WebSocket.OPEN) {
      // First, start the planning phase (this triggers processing)
      const startCommand = {
        command: 'startPhase',
        phase: 'planning',
        options: {
          tradingDate: config.tradingDate,
          planningDays: config.planningDays.toString()
        }
      }
      
      console.log('Sending start command:', startCommand)
      window.globalWebSocket.send(JSON.stringify(startCommand))
      
      // Then, subscribe to planning phase events (this starts receiving events)
      const subscribeCommand = {
        command: 'subscribePhase',
        phase: 'planning'
      }
      
      console.log('Sending subscribe command:', subscribeCommand)
      window.globalWebSocket.send(JSON.stringify(subscribeCommand))
      
      // Navigate to Planning page
      navigate('/planning')
    } else {
      console.error('WebSocket not connected')
      console.error('globalWebSocket exists:', !!window.globalWebSocket)
      console.error('readyState:', window.globalWebSocket?.readyState)
      console.error('Expected OPEN state:', WebSocket.OPEN)
      alert('Not connected to server. Please wait for connection.')
    }
  }

  return (
    <div className="max-w-2xl mx-auto">
      <div className="card p-6">
        <div className="space-y-6">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">
              <Calendar className="w-4 h-4 inline mr-2" />
              Trading Date
            </label>
            <input
              type="date"
              value={config.tradingDate}
              onChange={(e) => setConfig({...config, tradingDate: e.target.value})}
              className="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:ring-blue-500 focus:border-blue-500"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">
              <Clock className="w-4 h-4 inline mr-2" />
              Planning Days
            </label>
            <select
              value={config.planningDays}
              onChange={(e) => setConfig({...config, planningDays: parseInt(e.target.value)})}
              className="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:ring-blue-500 focus:border-blue-500"
            >
              {Array.from({ length: 15 }, (_, i) => i + 1).map(day => (
                <option key={day} value={day}>{day} Day{day !== 1 ? 's' : ''}</option>
              ))}
            </select>
          </div>

          <button
            onClick={handleStartPlanning}
            className="btn-primary w-full"
          >
            Start Planning
          </button>
        </div>
      </div>
    </div>
  )
}
