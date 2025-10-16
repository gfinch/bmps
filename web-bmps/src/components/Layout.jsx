import { NavLink, useLocation } from 'react-router-dom'
import { TrendingUp, LineChart, BarChart3 } from 'lucide-react'
import { useState, useEffect } from 'react'
import restApiService from '../services/restApiService.jsx'

const navItems = [
  { path: '/planning', label: 'Planning Chart', icon: TrendingUp },
  { path: '/trading', label: 'Trading Chart', icon: LineChart },
  { path: '/results', label: 'Results', icon: BarChart3 }
]

export default function Layout({ children }) {
  const location = useLocation()
  const [isLiveConnected, setIsLiveConnected] = useState(false)
  const [isBacktestConnected, setIsBacktestConnected] = useState(false)

  useEffect(() => {
    const checkHealth = async () => {
      try {
        const [liveHealthy, backtestHealthy] = await Promise.all([
          restApiService.checkServerHealth(true),
          restApiService.checkServerHealth(false)
        ])
        setIsLiveConnected(liveHealthy)
        setIsBacktestConnected(backtestHealthy)
      } catch (err) {
        setIsLiveConnected(false)
        setIsBacktestConnected(false)
      }
    }
    
    checkHealth()
    const interval = setInterval(checkHealth, 10000)
    
    return () => clearInterval(interval)
  }, [])
  
  // Pages that should use full viewport height without padding
  const fullHeightPages = ['/planning', '/trading']
  const isFullHeightPage = fullHeightPages.includes(location.pathname)

  return (
    <div className={`bg-gray-50 flex flex-col ${isFullHeightPage ? 'h-screen' : 'min-h-screen'}`}>
      {/* Navigation Tabs */}
      <nav className="bg-white border-b flex-shrink-0">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex space-x-8 items-center justify-between">
            <div className="flex space-x-8">
              {navItems.map(({ path, label, icon: Icon }) => (
                <NavLink
                  key={path}
                  to={path}
                  className={({ isActive }) => 
                    `flex items-center px-1 py-4 text-sm font-medium border-b-2 transition-colors ${
                      isActive
                        ? 'text-blue-600 border-blue-600'
                        : 'text-gray-500 border-transparent hover:text-gray-700 hover:border-gray-300'
                    }`
                  }
                >
                  <Icon className="w-4 h-4 mr-2" />
                  {label}
                </NavLink>
              ))}
            </div>
            
            {/* Connection Status Indicators */}
            <div className="flex items-center gap-2">
              <div 
                className={`w-2 h-2 rounded-full ${
                  isBacktestConnected ? 'bg-green-500' : 'bg-red-500'
                }`}
                title={isBacktestConnected ? 'Backtest server connected' : 'Backtest server disconnected'}
              />
              <div 
                className={`w-2 h-2 rounded-full ${
                  isLiveConnected ? 'bg-green-500' : 'bg-red-500'
                }`}
                title={isLiveConnected ? 'Live server connected' : 'Live server disconnected'}
              />
            </div>
          </div>
        </div>
      </nav>

      {/* Main Content */}
      {isFullHeightPage ? (
        <main className="flex-1 flex flex-col">
          {children}
        </main>
      ) : (
        <main className="flex-1 py-6">
          <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
            {children}
          </div>
        </main>
      )}
    </div>
  )
}