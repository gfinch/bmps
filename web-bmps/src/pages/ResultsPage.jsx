import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { BarChart3, TrendingUp, TrendingDown, DollarSign, Clock, RefreshCw, AlertCircle, Calendar, ChevronLeft, ChevronRight } from 'lucide-react'
import { DayPicker } from 'react-day-picker'
import 'react-day-picker/dist/style.css'
import { createChart, BaselineSeries } from 'lightweight-charts'
import restApiService from '../services/restApiService.jsx'
import phaseService from '../services/phaseService.jsx'

/**
 * Converts a UTC timestamp (in milliseconds) to New York local time (DST-safe).
 * Returns a Unix timestamp in seconds (offset from UTC).
 */
function utcMsToNewYorkSeconds(utcMs) {
  const utcDate = new Date(utcMs);

  // Format the UTC instant as if it were in New York time
  const fmt = new Intl.DateTimeFormat('en-US', {
    timeZone: 'America/New_York',
    hour12: false,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });

  // Break down the parts and reconstruct a New York-local time in UTC
  const parts = fmt.formatToParts(utcDate);
  const get = (t) => parts.find(p => p.type === t)?.value ?? '00';

  // Build ISO string and parse as UTC — this "shifts" the time correctly
  const localStr = `${get('year')}-${get('month')}-${get('day')}T${get('hour')}:${get('minute')}:${get('second')}Z`;
  const localDate = new Date(localStr);

  // Return seconds (TradingView wants seconds, not milliseconds)
  return Math.floor(localDate.getTime() / 1000);
}

export default function ResultsPage() {
  const navigate = useNavigate()
  const chartContainerRef = useRef()
  const chartRef = useRef()
  const baselineSeriesRef = useRef()
  const [activeTab, setActiveTab] = useState('overview')
  const [orderReport, setOrderReport] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [availableDates, setAvailableDates] = useState([])
  const [showCalendar, setShowCalendar] = useState(false)
  const [isAllTime, setIsAllTime] = useState(false)
  const [allTimePnL, setAllTimePnL] = useState(null)
  const [tradingDate, setTradingDate] = useState(() => {
    const today = new Date().toISOString().split('T')[0]
    return phaseService.currentConfig?.tradingDate || today
  })
  
  // Convert trading date string to Date object for DayPicker
  const selectedDate = new Date(tradingDate + 'T00:00:00')

  // Fetch available dates with profitability when component mounts
  useEffect(() => {
    const fetchAvailableDates = async () => {
      try {
        const dateInfos = await restApiService.getAvailableDates(tradingDate)
        setAvailableDates(dateInfos)
      } catch (err) {
        console.error('Failed to fetch available dates:', err)
      }
    }
    fetchAvailableDates()
  }, [tradingDate])

  // Fetch all-time P&L when component mounts
  useEffect(() => {
    const fetchAllTimePnL = async () => {
      try {
        const report = await restApiService.getAggregateOrderReport(tradingDate)
        setAllTimePnL(report.totalPnL)
      } catch (err) {
        console.error('Failed to fetch all-time P&L:', err)
      }
    }
    fetchAllTimePnL()
  }, [tradingDate])

  useEffect(() => {
    fetchOrderReport()
  }, [tradingDate, isAllTime])

  // Close calendar when clicking outside
  useEffect(() => {
    const handleClickOutside = (event) => {
      if (showCalendar && !event.target.closest('.relative')) {
        setShowCalendar(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [showCalendar])

  const fetchOrderReport = async () => {
    setLoading(true)
    setError(null)
    try {
      const report = isAllTime 
        ? await restApiService.getAggregateOrderReport(tradingDate)
        : await restApiService.getOrderReport(tradingDate)
      setOrderReport(report)
      
      // Always refresh the all-time P&L display
      const allTimeReport = await restApiService.getAggregateOrderReport(tradingDate)
      setAllTimePnL(allTimeReport.totalPnL)
      
      // Refresh available dates to update calendar colors
      const dateInfos = await restApiService.getAvailableDates(tradingDate)
      setAvailableDates(dateInfos)
    } catch (err) {
      console.error('Failed to fetch order report:', err)
      setError(err.message)
      setOrderReport(null)
    } finally {
      setLoading(false)
    }
  }

  // Initialize chart when container becomes available
  useEffect(() => {
    // Don't initialize if no data to show
    if (!orderReport) {
      return
    }

    if (!chartContainerRef.current) {
      return
    }

    // If chart already exists, don't recreate
    if (chartRef.current) {
      return
    }

    const container = chartContainerRef.current
    
    // Wait for container to have dimensions
    if (container.clientWidth === 0 || container.clientHeight === 0) {
      return
    }

    // Clear container
    container.innerHTML = ''

    // Create the chart
    const chart = createChart(container, {
      width: container.clientWidth,
      height: 400,
      layout: {
        background: { color: '#ffffff' },
        textColor: '#333',
      },
      grid: {
        vertLines: { color: '#f0f0f0' },
        horzLines: { color: '#f0f0f0' },
      },
      crosshair: {
        mode: 1, // Magnet mode - snap to data points
      },
      rightPriceScale: {
        borderColor: '#cccccc',
      },
      timeScale: {
        borderColor: '#cccccc',
        timeVisible: true,
        secondsVisible: false,
      },
    })

    chartRef.current = chart

    // Handle resize
    const handleResize = () => {
      if (chartRef.current && container) {
        chartRef.current.applyOptions({ width: container.clientWidth })
      }
    }
    window.addEventListener('resize', handleResize)

    // Cleanup function
    return () => {
      window.removeEventListener('resize', handleResize)
      if (chartRef.current) {
        chartRef.current.remove()
        chartRef.current = null
      }
      baselineSeriesRef.current = null
    }
  }, [orderReport])

  // Update chart data when orderReport changes
  useEffect(() => {
    // Add a small delay to ensure chart is fully initialized
    const timer = setTimeout(() => {
      // Make sure we have both chart and data before proceeding
      if (!chartRef.current || !orderReport || !orderReport.orders) {
        return
      }

      // Remove existing series if it exists
      if (baselineSeriesRef.current && chartRef.current) {
        try {
          chartRef.current.removeSeries(baselineSeriesRef.current)
          baselineSeriesRef.current = null
        } catch (e) {
          console.error('Error removing baseline series:', e)
        }
      }

      // Process trades into cumulative P&L data
      const completedOrders = orderReport.orders
        .filter(order => {
          const status = typeof order.status === 'object' ? Object.keys(order.status)[0] : order.status
          return status === 'Profit' || status === 'Loss'
        })
        .sort((a, b) => (a.closeTimestamp || 0) - (b.closeTimestamp || 0))

      // If no completed orders, don't create series
      if (completedOrders.length === 0) {
        return
      }

            // Calculate cumulative P&L
      let cumulativePnL = 0
      const pnlData = completedOrders.map(order => {
        const status = typeof order.status === 'object' ? Object.keys(order.status)[0] : order.status
        const pnl = status === 'Profit' ? order.potential : -order.atRisk
        cumulativePnL += pnl
        
        return {
          time: utcMsToNewYorkSeconds(order.closeTimestamp),
          value: cumulativePnL
        }
      })

      // Add starting point at 0 at market open (9:30 AM NY time)
      if (pnlData.length > 0) {
        // Get the date of the first trade and set time to 9:30 AM NY
        const firstTradeDate = new Date(pnlData[0].time * 1000)
        const marketOpenDate = new Date(firstTradeDate)
        marketOpenDate.setUTCHours(13, 30, 0, 0) // 9:30 AM NY = 13:30 UTC (or 14:30 during DST)
        
        // Convert market open time to NY timezone seconds
        const marketOpenMs = marketOpenDate.getTime()
        const marketOpenSeconds = utcMsToNewYorkSeconds(marketOpenMs)
        
        pnlData.unshift({ time: marketOpenSeconds, value: 0 })
      }

            // Create baseline series
      try {
        baselineSeriesRef.current = chartRef.current.addSeries(BaselineSeries, {
          baseValue: { type: 'price', price: 0 },
          topLineColor: '#22c55e', // Green for profits
          topFillColor1: 'rgba(34, 197, 94, 0.28)',
          topFillColor2: 'rgba(34, 197, 94, 0.05)',
          bottomLineColor: '#ef4444', // Red for losses
          bottomFillColor1: 'rgba(239, 68, 68, 0.05)',
          bottomFillColor2: 'rgba(239, 68, 68, 0.28)',
          lineWidth: 2,
          crosshairMarkerVisible: true,
          lastValueVisible: true,
          priceLineVisible: true,
        })

        baselineSeriesRef.current.setData(pnlData)

        // Fit content
        chartRef.current.timeScale().fitContent()
      } catch (e) {
        console.error('Error creating chart series:', e)
      }
    }, 50) // 50ms delay to ensure chart is ready

    return () => clearTimeout(timer)
  }, [orderReport])

  // Get sorted list of available date strings for navigation
  const availableDateStrings = availableDates
    .map(dateInfo => dateInfo.date)
    .sort()

  // Navigate to previous available date
  const goToPreviousDate = () => {
    const currentIndex = availableDateStrings.indexOf(tradingDate)
    if (currentIndex > 0) {
      setTradingDate(availableDateStrings[currentIndex - 1])
    }
  }

  // Navigate to next available date
  const goToNextDate = () => {
    const currentIndex = availableDateStrings.indexOf(tradingDate)
    if (currentIndex >= 0 && currentIndex < availableDateStrings.length - 1) {
      setTradingDate(availableDateStrings[currentIndex + 1])
    }
  }

  // Check if we can navigate
  const canGoPrevious = !isAllTime && availableDateStrings.indexOf(tradingDate) > 0
  const canGoNext = !isAllTime && availableDateStrings.indexOf(tradingDate) < availableDateStrings.length - 1

  // Convert available dates to Date objects and categorize by profitability
  const profitableDates = []
  const unprofitableDates = []
  const neutralDates = []
  
  availableDates.forEach(dateInfo => {
    const [year, month, day] = dateInfo.date.split('-').map(Number)
    const dateObj = new Date(year, month - 1, day) // month is 0-indexed
    
    if (dateInfo.profitable === true) {
      profitableDates.push(dateObj)
    } else if (dateInfo.profitable === false) {
      unprofitableDates.push(dateObj)
    } else {
      neutralDates.push(dateObj)
    }
  })
  
  // Handle date selection from calendar
  const handleDateSelect = (date) => {
    if (date) {
      const year = date.getFullYear()
      const month = String(date.getMonth() + 1).padStart(2, '0')
      const day = String(date.getDate()).padStart(2, '0')
      const dateString = `${year}-${month}-${day}`
      setTradingDate(dateString)
      setShowCalendar(false)
    }
  }

  const tradingResults = orderReport ? {
    totalTrades: orderReport.winning + orderReport.losing,
    winningTrades: orderReport.winning,
    losingTrades: orderReport.losing,
    winRate: orderReport.winning + orderReport.losing > 0 
      ? Math.round((orderReport.winning / (orderReport.winning + orderReport.losing)) * 100)
      : 0,
    avgWin: Math.round(orderReport.averageWinDollars),
    avgLoss: Math.round(orderReport.averageLossDollars),
    maxDrawdown: Math.round(orderReport.maxDrawdownDollars),
    totalPnL: Math.round(orderReport.totalPnL)  // Use totalPnL from backend
  } : {
    totalPnL: 0,
    totalTrades: 0,
    winningTrades: 0,
    losingTrades: 0,
    winRate: 0,
    avgWin: 0,
    avgLoss: 0,
    maxDrawdown: 0
  }

  const trades = orderReport ? orderReport.orders
    .filter(order => {
      const status = typeof order.status === 'object' ? Object.keys(order.status)[0] : order.status
      return status === 'Profit' || status === 'Loss'
    })
    .sort((a, b) => (a.closeTimestamp || 0) - (b.closeTimestamp || 0))
    .map((order, index) => {
      const status = typeof order.status === 'object' ? Object.keys(order.status)[0] : order.status
      const orderType = typeof order.orderType === 'object' ? Object.keys(order.orderType)[0] : order.orderType
      const pnl = status === 'Profit' ? order.potential : -order.atRisk
      const closeDate = new Date(order.closeTimestamp)
      
      // Format date as YYYY-MM-DD for navigation
      const year = closeDate.getFullYear()
      const month = String(closeDate.getMonth() + 1).padStart(2, '0')
      const day = String(closeDate.getDate()).padStart(2, '0')
      const dateISO = `${year}-${month}-${day}`
      
      return {
        id: index + 1,
        date: closeDate.toLocaleDateString('en-US', { timeZone: 'America/New_York' }),
        dateISO: dateISO,
        time: closeDate.toLocaleTimeString('en-US', { timeZone: 'America/New_York' }),
        type: orderType,
        price: order.entryPoint,
        quantity: order.contracts,
        pnl: pnl
      }
    }) : []

  // Navigate to planning page with a specific date
  const handleDateClick = (dateISO) => {
    navigate('/planning', { state: { tradingDate: dateISO, autoStart: true } })
  }

  const tabs = [
    { id: 'overview', label: 'Overview', icon: BarChart3 },
    { id: 'trades', label: 'Trade History', icon: Clock }
  ]

  return (
    <div className="space-y-6">
      <div className="card p-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-4">
            <label className="text-sm font-medium text-gray-700">
              Trading Date:
            </label>
            
            {/* Previous Date Button */}
            <button
              onClick={goToPreviousDate}
              disabled={!canGoPrevious}
              className="p-2 bg-white border border-gray-300 rounded-md hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
              title="Previous date"
            >
              <ChevronLeft className="w-4 h-4 text-gray-700" />
            </button>

            {/* Date Picker */}
            <div className="relative">
              <button
                onClick={() => setShowCalendar(!showCalendar)}
                disabled={isAllTime}
                className="px-3 py-2 bg-white border border-gray-300 rounded-md hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-blue-500 flex items-center space-x-2 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <Calendar className="w-4 h-4 text-gray-700" />
                <span className="text-gray-900">{isAllTime ? 'All Time' : tradingDate}</span>
              </button>
            {showCalendar && (
              <div className="absolute top-full mt-2 z-50 bg-white border border-gray-300 rounded-lg shadow-lg p-3">
                <DayPicker
                  mode="single"
                  selected={selectedDate}
                  onSelect={handleDateSelect}
                  modifiers={{
                    profitable: profitableDates,
                    unprofitable: unprofitableDates,
                    neutral: neutralDates
                  }}
                  modifiersClassNames={{
                    profitable: 'profitable-date',
                    unprofitable: 'unprofitable-date',
                    neutral: 'neutral-date'
                  }}
                  styles={{
                    caption: { color: '#374151' },
                    day: { margin: '2px' }
                  }}
                />
                <style>
                  {`
                    .profitable-date {
                      background-color: #22c55e !important;
                      color: white !important;
                      font-weight: bold !important;
                      border-radius: 4px !important;
                    }
                    .profitable-date:hover {
                      background-color: #16a34a !important;
                    }
                    .unprofitable-date {
                      background-color: #ef4444 !important;
                      color: white !important;
                      font-weight: bold !important;
                      border-radius: 4px !important;
                    }
                    .unprofitable-date:hover {
                      background-color: #dc2626 !important;
                    }
                    .neutral-date {
                      background-color: #eab308 !important;
                      color: white !important;
                      font-weight: bold !important;
                      border-radius: 4px !important;
                    }
                    .neutral-date:hover {
                      background-color: #ca8a04 !important;
                    }
                  `}
                </style>
                <div className="mt-2 pt-2 border-t border-gray-200 text-xs text-gray-600 space-y-1">
                  <div className="flex items-center space-x-2">
                    <div className="w-3 h-3 bg-green-500 rounded"></div>
                    <span>Profitable days ({profitableDates.length})</span>
                  </div>
                  <div className="flex items-center space-x-2">
                    <div className="w-3 h-3 bg-red-500 rounded"></div>
                    <span>Unprofitable days ({unprofitableDates.length})</span>
                  </div>
                  <div className="flex items-center space-x-2">
                    <div className="w-3 h-3 bg-yellow-500 rounded"></div>
                    <span>Neutral/No trades ({neutralDates.length})</span>
                  </div>
                </div>
              </div>
            )}
            </div>

            {/* Next Date Button */}
            <button
              onClick={goToNextDate}
              disabled={!canGoNext}
              className="p-2 bg-white border border-gray-300 rounded-md hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
              title="Next date"
            >
              <ChevronRight className="w-4 h-4 text-gray-700" />
            </button>

            {/* Refresh Button */}
            <button
              onClick={fetchOrderReport}
              disabled={loading}
              className="p-2 bg-blue-500 text-white rounded-md hover:bg-blue-600 disabled:bg-gray-400"
              title="Refresh"
            >
              <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
            </button>
          </div>

          {/* All Time Checkbox with P&L */}
          <div className="flex items-center space-x-4">
            {allTimePnL !== null && (
              <div className={`text-2xl font-bold ${allTimePnL >= 0 ? 'text-green-600' : 'text-red-600'}`}>
                ${Math.round(allTimePnL).toLocaleString()}
              </div>
            )}
            <div className="flex items-center space-x-2">
              <input
                type="checkbox"
                id="allTime"
                checked={isAllTime}
                onChange={(e) => setIsAllTime(e.target.checked)}
                className="w-4 h-4 text-blue-600 border-gray-300 rounded focus:ring-blue-500"
              />
              <label htmlFor="allTime" className="text-sm font-medium text-gray-700 cursor-pointer">
                All Time
              </label>
            </div>
          </div>
        </div>
        {error && (
          <div className="flex items-center space-x-2 text-red-600 mt-3">
            <AlertCircle className="w-5 h-5" />
            <span className="text-sm">{error}</span>
          </div>
        )}
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
        <div className="card p-6">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-gray-600">Total P&L</p>
              <p className={`text-2xl font-bold ${tradingResults.totalPnL >= 0 ? 'text-green-600' : 'text-red-600'}`}>
                ${tradingResults.totalPnL.toLocaleString()}
              </p>
            </div>
            <DollarSign className={`w-8 h-8 ${tradingResults.totalPnL >= 0 ? 'text-green-500' : 'text-red-500'}`} />
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
              <p className="text-sm text-gray-600">Max Drawdown</p>
              <p className="text-2xl font-bold text-red-600">
                ${tradingResults.maxDrawdown.toLocaleString()}
              </p>
            </div>
            <TrendingDown className="w-8 h-8 text-red-500" />
          </div>
        </div>
      </div>

      <div className="card">
        <div className="border-b border-gray-200">
          <nav className="flex space-x-8 px-6">
            {tabs.map(({ id, label, icon: Icon }) => (
              <button
                key={id}
                onClick={() => setActiveTab(id)}
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

        <div className="p-6">
          {activeTab === 'overview' && (
            <div className="space-y-6">
              <h3 className="text-lg font-medium text-gray-900">Performance Metrics</h3>
              {loading ? (
                <div className="flex items-center justify-center py-12">
                  <RefreshCw className="w-8 h-8 text-blue-500 animate-spin" />
                </div>
              ) : orderReport ? (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                  <div className="space-y-3">
                    <div className="flex justify-between">
                      <span className="text-gray-600">Winning Trades:</span>
                      <span className="font-medium text-green-600">{tradingResults.winningTrades}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-gray-600">Losing Trades:</span>
                      <span className="font-medium text-red-600">{tradingResults.losingTrades}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-gray-600">Win Rate:</span>
                      <span className="font-medium">{tradingResults.winRate}%</span>
                    </div>
                  </div>
                  <div className="space-y-3">
                    <div className="flex justify-between">
                      <span className="text-gray-600">Average Win:</span>
                      <span className="font-medium text-green-600">${tradingResults.avgWin.toLocaleString()}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-gray-600">Average Loss:</span>
                      <span className="font-medium text-red-600">${tradingResults.avgLoss.toLocaleString()}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-gray-600">Max Drawdown:</span>
                      <span className="font-medium text-red-600">${tradingResults.maxDrawdown.toLocaleString()}</span>
                    </div>
                  </div>
                </div>
              ) : (
                <div className="text-center py-12">
                  <BarChart3 className="w-16 h-16 text-gray-300 mx-auto mb-4" />
                  <h3 className="text-lg font-medium text-gray-500 mb-2">No Trading Data</h3>
                  <p className="text-gray-400">Select a trading date and click Refresh to view results</p>
                </div>
              )}
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
                        <th className="text-left py-3 px-4 font-medium text-gray-900">Date</th>
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
                          <td className="py-3 px-4 text-sm">
                            <button
                              onClick={() => handleDateClick(trade.dateISO)}
                              className="text-gray-600 hover:text-blue-600 hover:underline cursor-pointer text-left bg-transparent border-0 p-0 font-normal"
                            >
                              {trade.date}
                            </button>
                          </td>
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

      {/* P&L Chart */}
      {orderReport && trades.length > 0 && (
        <div className="card p-6">
          <div ref={chartContainerRef} className="w-full bg-white" style={{ height: '400px' }} />
        </div>
      )}
    </div>
  )
}
