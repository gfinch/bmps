import { useState } from 'react'
import { BarChart3, TrendingUp, TrendingDown, DollarSign, Clock, Target } from 'lucide-react'

export default function ResultsPage() {
  const [activeTab, setActiveTab] = useState('overview')

  // Mock data for display purposes
  const tradingResults = {
    totalTrades: 8,
    winningTrades: 6,
    losingTrades: 2,
    winRate: 75,
    totalPnL: 4250,
    totalReturn: 3.8,
    avgWin: 850,
    avgLoss: -125,
    maxDrawdown: -250,
    sharpeRatio: 1.42
  }

  const trades = [
    { id: 1, time: '09:42:15', type: 'Buy', price: 115.20, quantity: 100, pnl: +850, status: 'closed' },
    { id: 2, time: '10:15:30', type: 'Sell', price: 117.80, quantity: 50, pnl: +420, status: 'closed' },
    { id: 3, time: '10:45:12', type: 'Buy', price: 116.50, quantity: 75, pnl: -125, status: 'closed' },
    { id: 4, time: '11:20:45', type: 'Sell', price: 118.20, quantity: 100, pnl: +980, status: 'closed' },
    { id: 5, time: '11:55:08', type: 'Buy', price: 117.10, quantity: 200, pnl: +1200, status: 'closed' },
  ]

  const tabs = [
    { id: 'overview', label: 'Overview', icon: BarChart3 },
    { id: 'trades', label: 'Trade History', icon: Clock },
    { id: 'analysis', label: 'Analysis', icon: Target }
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
            <Target className="w-8 h-8 text-blue-500" />
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
                    ? 'border-blue-500 text-blue-600'
                    : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
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
              <div className="bg-gray-50 rounded-lg p-4 flex items-center justify-center">
                <div className="text-center">
                  <BarChart3 className="w-12 h-12 text-gray-300 mx-auto mb-2" />
                  <p className="text-gray-500">Performance Chart</p>
                  <p className="text-sm text-gray-400">Coming soon</p>
                </div>
              </div>
            </div>
          )}

          {activeTab === 'trades' && (
            <div>
              <h3 className="text-lg font-medium text-gray-900 mb-4">Trade History</h3>
              <div className="overflow-x-auto">
                <table className="min-w-full">
                  <thead>
                    <tr className="border-b border-gray-200">
                      <th className="text-left py-3 px-4 font-medium text-gray-900">Time</th>
                      <th className="text-left py-3 px-4 font-medium text-gray-900">Type</th>
                      <th className="text-left py-3 px-4 font-medium text-gray-900">Price</th>
                      <th className="text-left py-3 px-4 font-medium text-gray-900">Quantity</th>
                      <th className="text-left py-3 px-4 font-medium text-gray-900">P&L</th>
                    </tr>
                  </thead>
                  <tbody>
                    {trades.map((trade) => (
                      <tr key={trade.id} className="border-b border-gray-100">
                        <td className="py-3 px-4 text-sm text-gray-600">{trade.time}</td>
                        <td className="py-3 px-4 text-sm">
                          <span className={`px-2 py-1 rounded-full text-xs font-medium ${
                            trade.type === 'Buy' 
                              ? 'bg-green-100 text-green-800' 
                              : 'bg-red-100 text-red-800'
                          }`}>
                            {trade.type}
                          </span>
                        </td>
                        <td className="py-3 px-4 text-sm text-gray-900">${trade.price}</td>
                        <td className="py-3 px-4 text-sm text-gray-900">{trade.quantity}</td>
                        <td className="py-3 px-4 text-sm">
                          <span className={`font-medium ${
                            trade.pnl >= 0 ? 'text-green-600' : 'text-red-600'
                          }`}>
                            ${trade.pnl > 0 ? '+' : ''}{trade.pnl}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {activeTab === 'analysis' && (
            <div className="text-center py-12">
              <Target className="w-16 h-16 text-gray-300 mx-auto mb-4" />
              <h3 className="text-lg font-medium text-gray-500 mb-2">Advanced Analysis</h3>
              <p className="text-gray-400">Detailed trading analysis will be implemented here</p>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}