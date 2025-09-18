import { useState, useEffect } from 'react'
import { BarChart3, TrendingUp, TrendingDown, DollarSign, Clock, Target } from 'lucide-react'

export default function ResultsPage() {
  const [config, setConfig] = useState(null)
  const [activeTab, setActiveTab] = useState('overview')

  useEffect(() => {
    // Load configuration
    const savedConfig = localStorage.getItem('tradingConfig')
    if (savedConfig) {
      setConfig(JSON.parse(savedConfig))
    }
  }, [])

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

  return (
    <div className="space-y-6">
      {/* Summary Cards */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
        <div className="card p-6">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-gray-600">Total P&L</p>
              <p className="text-2xl font-semibold text-green-600">
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
              <p className="text-2xl font-semibold text-blue-600">
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
              <p className="text-2xl font-semibold text-gray-900">
                {tradingResults.totalTrades}
              </p>
            </div>
            <Clock className="w-8 h-8 text-gray-500" />
          </div>
        </div>

        <div className="card p-6">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-gray-600">Return</p>
              <p className="text-2xl font-semibold text-green-600">
                +{tradingResults.totalReturn}%
              </p>
            </div>
            <TrendingUp className="w-8 h-8 text-green-500" />
          </div>
        </div>
      </div>

      {/* Tab Navigation */}
      <div className="border-b border-gray-200">
        <nav className="-mb-px flex space-x-8">
          {tabs.map(({ id, label, icon: Icon }) => (
            <button
              key={id}
              onClick={() => setActiveTab(id)}
              className={`flex items-center py-2 px-1 border-b-2 text-sm font-medium ${
                activeTab === id
                  ? 'text-blue-600 border-blue-600'
                  : 'text-gray-500 border-transparent hover:text-gray-700 hover:border-gray-300'
              }`}
            >
              <Icon className="w-4 h-4 mr-2" />
              {label}
            </button>
          ))}
        </nav>
      </div>

      {/* Tab Content */}
      {activeTab === 'overview' && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <div className="card p-6">
            <h3 className="text-lg font-medium text-gray-900 mb-4">Performance Metrics</h3>
            <div className="space-y-4">
              <div className="flex justify-between items-center py-2 border-b border-gray-100">
                <span className="text-sm text-gray-600">Winning Trades</span>
                <span className="text-sm font-medium text-green-600">
                  {tradingResults.winningTrades}
                </span>
              </div>
              <div className="flex justify-between items-center py-2 border-b border-gray-100">
                <span className="text-sm text-gray-600">Losing Trades</span>
                <span className="text-sm font-medium text-red-600">
                  {tradingResults.losingTrades}
                </span>
              </div>
              <div className="flex justify-between items-center py-2 border-b border-gray-100">
                <span className="text-sm text-gray-600">Average Win</span>
                <span className="text-sm font-medium text-green-600">
                  ${tradingResults.avgWin}
                </span>
              </div>
              <div className="flex justify-between items-center py-2 border-b border-gray-100">
                <span className="text-sm text-gray-600">Average Loss</span>
                <span className="text-sm font-medium text-red-600">
                  ${tradingResults.avgLoss}
                </span>
              </div>
              <div className="flex justify-between items-center py-2 border-b border-gray-100">
                <span className="text-sm text-gray-600">Max Drawdown</span>
                <span className="text-sm font-medium text-red-600">
                  ${tradingResults.maxDrawdown}
                </span>
              </div>
              <div className="flex justify-between items-center py-2">
                <span className="text-sm text-gray-600">Sharpe Ratio</span>
                <span className="text-sm font-medium text-blue-600">
                  {tradingResults.sharpeRatio}
                </span>
              </div>
            </div>
          </div>

          <div className="card p-6">
            <h3 className="text-lg font-medium text-gray-900 mb-4">Risk Analysis</h3>
            <div className="space-y-4">
              <div className="p-4 bg-green-50 border border-green-200 rounded-lg">
                <div className="flex items-center">
                  <TrendingUp className="w-5 h-5 text-green-600 mr-2" />
                  <span className="text-sm font-medium text-green-800">Strong Performance</span>
                </div>
                <p className="text-xs text-green-700 mt-1">
                  Win rate above 70% with positive risk-reward ratio
                </p>
              </div>
              
              <div className="p-4 bg-blue-50 border border-blue-200 rounded-lg">
                <div className="flex items-center">
                  <Target className="w-5 h-5 text-blue-600 mr-2" />
                  <span className="text-sm font-medium text-blue-800">Good Risk Management</span>
                </div>
                <p className="text-xs text-blue-700 mt-1">
                  Low drawdown relative to total returns
                </p>
              </div>

              <div className="p-4 bg-yellow-50 border border-yellow-200 rounded-lg">
                <div className="flex items-center">
                  <Clock className="w-5 h-5 text-yellow-600 mr-2" />
                  <span className="text-sm font-medium text-yellow-800">Monitor Volatility</span>
                </div>
                <p className="text-xs text-yellow-700 mt-1">
                  Consider position sizing for high volatility periods
                </p>
              </div>
            </div>
          </div>
        </div>
      )}

      {activeTab === 'trades' && (
        <div className="card p-6">
          <h3 className="text-lg font-medium text-gray-900 mb-4">Trade History</h3>
          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                    Time
                  </th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                    Type
                  </th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                    Price
                  </th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                    Quantity
                  </th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                    P&L
                  </th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                    Status
                  </th>
                </tr>
              </thead>
              <tbody className="bg-white divide-y divide-gray-200">
                {trades.map((trade) => (
                  <tr key={trade.id}>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                      {trade.time}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap">
                      <span className={`inline-flex px-2 py-1 text-xs font-semibold rounded-full ${
                        trade.type === 'Buy' 
                          ? 'bg-green-100 text-green-800' 
                          : 'bg-red-100 text-red-800'
                      }`}>
                        {trade.type}
                      </span>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                      ${trade.price}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                      {trade.quantity}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm">
                      <span className={trade.pnl > 0 ? 'text-green-600' : 'text-red-600'}>
                        {trade.pnl > 0 ? '+' : ''}${trade.pnl}
                      </span>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap">
                      <span className="inline-flex px-2 py-1 text-xs font-semibold rounded-full bg-gray-100 text-gray-800">
                        {trade.status}
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
        <div className="space-y-6">
          <div className="card p-6">
            <h3 className="text-lg font-medium text-gray-900 mb-4">Strategy Analysis</h3>
            <div className="prose max-w-none">
              <p className="text-gray-600 mb-4">
                Today's trading session demonstrated strong execution of the planned strategy:
              </p>
              <ul className="space-y-2 text-sm text-gray-600">
                <li>• Successfully identified and capitalized on morning breakout pattern</li>
                <li>• Maintained disciplined risk management with proper stop-loss levels</li>
                <li>• Volume confirmation aligned with price movements</li>
                <li>• Avoided emotional trading during volatile periods</li>
              </ul>
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            <div className="card p-6">
              <h4 className="text-md font-medium text-gray-900 mb-3">Strengths</h4>
              <ul className="space-y-2 text-sm text-gray-600">
                <li>• High win rate (75%)</li>
                <li>• Good risk-reward ratio</li>
                <li>• Consistent position sizing</li>
                <li>• Quick adaptation to market conditions</li>
              </ul>
            </div>

          <div className="card p-6">
            <h4 className="text-md font-medium text-gray-900 mb-3">Areas for Improvement</h4>
              <ul className="space-y-2 text-sm text-gray-600">
                <li>• Consider wider stops in volatile conditions</li>
                <li>• Monitor for overtrading patterns</li>
                <li>• Implement partial profit-taking strategy</li>
                <li>• Review exit timing on winning trades</li>
              </ul>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}