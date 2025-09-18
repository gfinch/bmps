import { useEffect, useRef, useState } from 'react'
import { createChart, CandlestickSeries } from 'lightweight-charts'
import { Play, Pause, RotateCcw, SkipBack, SkipForward, Rewind, FastForward, LineChart } from 'lucide-react'

export default function TradingChartPage() {
  const chartContainerRef = useRef()
  const chartRef = useRef()
  const [isPlaying, setIsPlaying] = useState(false)
  const [currentTime, setCurrentTime] = useState(0)
  const [config, setConfig] = useState(null)

  useEffect(() => {
    // Load configuration
    const savedConfig = localStorage.getItem('tradingConfig')
    if (savedConfig) {
      setConfig(JSON.parse(savedConfig))
    }
  }, [])

  useEffect(() => {
    if (!chartContainerRef.current || chartRef.current) return

    // Add a small delay to ensure container has proper dimensions
    const initChart = () => {
      try {
        const container = chartContainerRef.current
        if (!container || container.clientWidth === 0 || container.clientHeight === 0) {
          console.log('Container not ready, retrying...', { 
            width: container?.clientWidth, 
            height: container?.clientHeight 
          })
          setTimeout(initChart, 100)
          return
        }

        // Clear any existing content
        container.innerHTML = ''

        // Create the chart
        const chart = createChart(container, {
          width: container.clientWidth,
          height: container.clientHeight,
          layout: {
            background: { color: '#ffffff' },
            textColor: '#333',
          },
          grid: {
            vertLines: { color: '#f0f0f0' },
            horzLines: { color: '#f0f0f0' },
          },
          crosshair: {
            mode: 1,
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

        console.log('Chart created with dimensions:', {
          width: container.clientWidth,
          height: container.clientHeight
        })

        // Create candlestick series
        const candlestickSeries = chart.addSeries(CandlestickSeries, {
          upColor: '#26a69a',
          downColor: '#ef5350',
          borderVisible: false,
          wickUpColor: '#26a69a',
          wickDownColor: '#ef5350',
        })

        // Sample candle data - 15 days of sample data (Trading session data)
        const sampleData = [
          { time: '2024-01-15', open: 113.0, high: 115.0, low: 112.0, close: 114.0 },
          { time: '2024-01-16', open: 114.0, high: 118.0, low: 113.5, close: 117.2 },
          { time: '2024-01-17', open: 117.2, high: 119.0, low: 116.0, close: 116.5 },
          { time: '2024-01-18', open: 116.5, high: 118.5, low: 115.0, close: 118.0 },
          { time: '2024-01-19', open: 118.0, high: 120.0, low: 117.5, close: 119.5 },
          { time: '2024-01-22', open: 119.5, high: 122.0, low: 119.0, close: 121.0 },
          { time: '2024-01-23', open: 121.0, high: 123.0, low: 120.0, close: 120.5 },
          { time: '2024-01-24', open: 120.5, high: 122.0, low: 118.0, close: 119.0 },
          { time: '2024-01-25', open: 119.0, high: 121.0, low: 117.0, close: 120.0 },
          { time: '2024-01-26', open: 120.0, high: 124.0, low: 119.5, close: 123.0 },
          { time: '2024-01-29', open: 123.0, high: 126.0, low: 122.0, close: 125.0 },
          { time: '2024-01-30', open: 125.0, high: 127.0, low: 123.0, close: 124.0 },
          { time: '2024-01-31', open: 124.0, high: 126.0, low: 121.0, close: 122.5 },
          { time: '2024-02-01', open: 122.5, high: 125.0, low: 120.0, close: 124.5 },
          { time: '2024-02-02', open: 124.5, high: 128.0, low: 124.0, close: 127.0 },
        ]

        candlestickSeries.setData(sampleData)

        // Store chart reference for cleanup
        chartRef.current = chart

        // Handle resize
        const handleResize = () => {
          if (chartContainerRef.current && chart) {
            chart.applyOptions({ 
              width: chartContainerRef.current.clientWidth,
              height: chartContainerRef.current.clientHeight
            })
          }
        }

        window.addEventListener('resize', handleResize)

        return () => {
          window.removeEventListener('resize', handleResize)
          if (chart) {
            chart.remove()
          }
          chartRef.current = null
        }
      } catch (error) {
        console.error('Error creating chart:', error)
        // Fallback placeholder
        if (chartContainerRef.current) {
          chartContainerRef.current.innerHTML = `
            <div style="width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #f8fafc 0%, #e2e8f0 100%); border-radius: 8px; border: 2px dashed #cbd5e1;">
              <div style="text-align: center; color: #64748b;">
                <div style="font-size: 48px; margin-bottom: 16px;">⚠️</div>
                <div style="font-size: 18px; font-weight: 600; margin-bottom: 8px;">Chart Error</div>
                <div style="font-size: 14px;">Unable to load chart: ${error.message}</div>
              </div>
            </div>
          `
        }
      }
    }

    // Start initialization
    const cleanup = initChart()

    // Cleanup function
    return () => {
      if (typeof cleanup === 'function') {
        cleanup()
      }
      if (chartRef.current) {
        chartRef.current.remove()
        chartRef.current = null
      }
    }
  }, [])

  const handlePlayPause = () => {
    setIsPlaying(!isPlaying)
    // TODO: Implement playback logic when charts are integrated
    console.log(`Trading Chart: ${!isPlaying ? 'Playing' : 'Paused'}`)
  }

  const handleRewind = () => {
    // TODO: Implement rewind logic when charts are integrated
    console.log('Trading Chart: Rewind')
  }

  const handleStepBack = () => {
    // TODO: Implement step back logic when charts are integrated
    console.log('Trading Chart: Step Back')
  }

  const handleStepForward = () => {
    // TODO: Implement step forward logic when charts are integrated
    console.log('Trading Chart: Step Forward')
  }

  const handleFastForward = () => {
    // TODO: Implement fast forward logic when charts are integrated
    console.log('Trading Chart: Fast Forward')
  }

  return (
    <div className="flex-1 flex flex-col">
      {/* Chart Container - fills remaining space */}
      <div className="flex-1 min-h-0">
        <div ref={chartContainerRef} className="w-full h-full min-h-[300px]" />
      </div>

      {/* Media Controls - fixed at bottom */}
      <div className="flex-shrink-0 py-2">
        <div className="flex items-center justify-center space-x-3">
          <button
            onClick={handleRewind}
            className="p-3 text-gray-600 hover:text-gray-800 border border-gray-300 rounded-md hover:bg-gray-50"
            title="Rewind"
          >
            <Rewind className="w-5 h-5" />
          </button>
          <button
            onClick={handleStepBack}
            className="p-3 text-gray-600 hover:text-gray-800 border border-gray-300 rounded-md hover:bg-gray-50"
            title="Step Back"
          >
            <SkipBack className="w-5 h-5" />
          </button>
          <button
            onClick={handlePlayPause}
            className="p-3 btn-primary"
            title={isPlaying ? 'Pause' : 'Play'}
          >
            {isPlaying ? (
              <Pause className="w-5 h-5" />
            ) : (
              <Play className="w-5 h-5" />
            )}
          </button>
          <button
            onClick={handleStepForward}
            className="p-3 text-gray-600 hover:text-gray-800 border border-gray-300 rounded-md hover:bg-gray-50"
            title="Step Forward"
          >
            <SkipForward className="w-5 h-5" />
          </button>
          <button
            onClick={handleFastForward}
            className="p-3 text-gray-600 hover:text-gray-800 border border-gray-300 rounded-md hover:bg-gray-50"
            title="Fast Forward"
          >
            <FastForward className="w-5 h-5" />
          </button>
        </div>
      </div>
    </div>
  )
}