import { useState } from 'react'
import { Calendar, Clock } from 'lucide-react'

export default function ConfigurationPage() {
  const [config, setConfig] = useState({
    tradingDate: '2024-01-15',
    planningDays: 3
  })



  const handleStartPlanning = () => {
    // Store configuration for other pages to use
    localStorage.setItem('tradingConfig', JSON.stringify(config))
    alert('Planning started successfully!')
  }

  return (
    <div className="max-w-2xl mx-auto">
      <div className="card p-6">
        <div className="space-y-6">
          {/* Trading Date */}
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

          {/* Planning Days */}
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

          {/* Start Planning Button */}
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