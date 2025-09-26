import { useState } from 'react'
import { Calendar, Clock, AlertCircle } from 'lucide-react'
import { usePlanning } from '../hooks/usePlanning.jsx'

export default function ConfigurationPage({ onNavigate }) {
  // Get today's date in YYYY-MM-DD format
  const today = new Date().toISOString().split('T')[0]
  
  const [config, setConfig] = useState({
    tradingDate: today,
    planningDays: 3
  })

  // Use planning hook
  const {
    startPlanning,
    isInitializing,
    canStart,
    error,
    hasError
  } = usePlanning()

  const handleStartPlanning = async () => {
    try {
      await startPlanning(config)
      console.log('Planning started successfully!')
      
      // Navigate to Planning tab
      if (onNavigate) {
        onNavigate('planning')
      }
    } catch (err) {
      console.error('Failed to start planning:', err)
      // Error is already handled by the hook
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
              disabled={isInitializing}
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
              disabled={isInitializing}
            >
              {Array.from({ length: 15 }, (_, i) => i + 1).map(day => (
                <option key={day} value={day}>{day} Day{day !== 1 ? 's' : ''}</option>
              ))}
            </select>
          </div>

          {/* Error Message */}
          {hasError && (
            <div className="flex items-center space-x-2 p-3 bg-red-50 border border-red-200 rounded-md">
              <AlertCircle className="w-4 h-4 text-red-500" />
              <span className="text-red-700 text-sm">{error}</span>
            </div>
          )}

          <button
            onClick={handleStartPlanning}
            disabled={!canStart || isInitializing}
            className="btn-primary w-full disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isInitializing ? 'Starting Planning...' : 'Start Planning'}
          </button>
        </div>
      </div>
    </div>
  )
}