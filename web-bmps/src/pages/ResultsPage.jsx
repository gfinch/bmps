import { useState, useEffect } from 'react'
import { BarChart3, TrendingUp, TrendingDown, DollarSign, Clock } from 'lucide-react'
import resultsService from '../services/resultsService.jsx'
import PerformanceChart from '../components/PerformanceChart.jsx'

export default function ResultsPage() {
  const [activeTab, setActiveTab] = useState('overview')
  const [results, setResults] = useState(null)

  // Subscribe to results service updates
  useEffect(() => {
    // Get initial results
    setResults(resultsService.getResults())

    // Listen for updates
    const handleResultsUpdate = (newResults) => {
      setResults(newResults)
    }

    resultsService.addListener(handleResultsUpdate)

    return () => {
      resultsService.removeListener(handleResultsUpdate)
    }
  }, [])

  // Use results data or fallback to empty state
  const tradingResults = results || {
    totalPnL: 0,
    totalTrades: 0,
    winningTrades: 0,
    losingTrades: 0,
    winRate: 0,
    avgWin: 0,
    avgLoss: 0,
    totalReturn: 0,
    maxDrawdown: 0,
    sharpeRatio: 0
  }

  const trades = results ? resultsService.getTradeHistory() : []

  const tabs = [
    { id: 'overview', label: 'Overview', icon: BarChart3 },
    { id: 'trades', label: 'Trade History', icon: Clock }
  ]

  const handleTabClick = (tabId) => {
    setActiveTab(tabId)
    console.log(`Tab ${tabId} clicked - content will be implemented`)
  }

  return (
    <div className="space-y-6">
      {/* Summary Cards */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
        <div className="card p-6">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-gray-600">Total P&L</p>
              <p className="text-2xl font-bold text-green-600">
                ${tradingResults.totalPnL.toLocaleString()}
              </p>
            </div>
            <DollarSign className="w-8 h-8 text-green-500" />
          </div>
        </div>

        <div className="card p-6">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-gray-600">Win Rate</p>
              <p className="text-2xl font-bold text-blue-600">
                {tradingResults.winRate}%
              </p>
            </div>
            <TrendingUp className="w-8 h-8 text-blue-500" />
          </div>
        </div>

        <div className="card p-6">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-gray-600">Total Trades</p>
              <p className="text-2xl font-bold text-gray-900">
                {tradingResults.totalTrades}
              </p>
            </div>
            <BarChart3 className="w-8 h-8 text-gray-500" />
          </div>
        </div>

        <div className="card p-6">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-gray-600">Total Return</p>
              <p className="text-2xl font-bold text-green-600">
                {tradingResults.totalReturn}%
              </p>
            </div>
            <TrendingUp className="w-8 h-8 text-green-500" />
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div className="card">
        <div className="border-b border-gray-200">
          <nav className="flex space-x-8 px-6">
            {tabs.map(({ id, label, icon: Icon }) => (
              <button
                key={id}
                onClick={() => handleTabClick(id)}
                className={`flex items-center py-4 px-1 border-b-2 font-medium text-sm transition-colors ${
                  activeTab === id
                    ? 'border-blue-500 text-blue-600 bg-white'
                    : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300 bg-white'
                }`}
              >
                <Icon className="w-4 h-4 mr-2" />
                {label}
              </button>
            ))}
          </nav>
        </div>

        {/* Tab Content */}
        <div className="p-6">
          {activeTab === 'overview' && (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              <div className="space-y-4">
                <h3 className="text-lg font-medium text-gray-900">Performance Metrics</h3>
                <div className="space-y-3">
                  <div className="flex justify-between">
                    <span className="text-gray-600">Winning Trades:</span>
                    <span className="font-medium">{tradingResults.winningTrades}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-gray-600">Losing Trades:</span>
                    <span className="font-medium">{tradingResults.losingTrades}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-gray-600">Average Win:</span>
                    <span className="font-medium text-green-600">${tradingResults.avgWin}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-gray-600">Average Loss:</span>
                    <span className="font-medium text-red-600">${tradingResults.avgLoss}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-gray-600">Max Drawdown:</span>
                    <span className="font-medium text-red-600">${tradingResults.maxDrawdown}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-gray-600">Sharpe Ratio:</span>
                    <span className="font-medium">{tradingResults.sharpeRatio}</span>
                  </div>
                </div>
              </div>
              <div className="bg-gray-50 rounded-lg p-4">
                <h4 className="text-sm font-medium text-gray-900 mb-4">P&L Performance</h4>
                {results && results.pnlHistory.length > 0 ? (
                  <div className="h-48">
                    <PerformanceChart data={results.pnlHistory} />
                  </div>
                ) : (
                  <div className="h-48 flex items-center justify-center">
                    <div className="text-center">
                      <BarChart3 className="w-12 h-12 text-gray-300 mx-auto mb-2" />
                      <p className="text-gray-500">No trading data yet</p>
                      <p className="text-sm text-gray-400">P&L chart will appear when trades are executed</p>
                    </div>
                  </div>
                )}
              </div>
            </div>
          )}

          {activeTab === 'trades' && (
            <div>
              <h3 className="text-lg font-medium text-gray-900 mb-4">Trade History</h3>
              {trades.length > 0 ? (
                <div className="overflow-x-auto">
                  <table className="min-w-full">
                    <thead>
                      <tr className="border-b border-gray-200">
                        <th className="text-left py-3 px-4 font-medium text-gray-900">Time</th>
                        <th className="text-left py-3 px-4 font-medium text-gray-900">Type</th>
                        <th className="text-left py-3 px-4 font-medium text-gray-900">Entry Price</th>
                        <th className="text-left py-3 px-4 font-medium text-gray-900">Contracts</th>
                        <th className="text-left py-3 px-4 font-medium text-gray-900">P&L</th>
                      </tr>
                    </thead>
                    <tbody>
                      {trades.map((trade) => (
                        <tr key={trade.id} className="border-b border-gray-100">
                          <td className="py-3 px-4 text-sm text-gray-600">{trade.time}</td>
                          <td className="py-3 px-4 text-sm">
                            <span className={`px-2 py-1 rounded-full text-xs font-medium bg-white border ${
                              trade.type === 'Long' 
                                ? 'border-green-200 text-green-800' 
                                : 'border-red-200 text-red-800'
                            }`}>
                              {trade.type}
                            </span>
                          </td>
                          <td className="py-3 px-4 text-sm text-gray-900">${trade.price.toFixed(2)}</td>
                          <td className="py-3 px-4 text-sm text-gray-900">{trade.quantity}</td>
                          <td className="py-3 px-4 text-sm">
                            <span className={`font-medium ${
                              trade.pnl >= 0 ? 'text-green-600' : 'text-red-600'
                            }`}>
                              ${trade.pnl > 0 ? '+' : ''}{Math.round(trade.pnl)}
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div className="text-center py-12">
                  <Clock className="w-16 h-16 text-gray-300 mx-auto mb-4" />
                  <h3 className="text-lg font-medium text-gray-500 mb-2">No Completed Trades</h3>
                  <p className="text-gray-400">Trade history will appear when orders are filled and closed</p>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}