import { useEffect, useRef, useState } from 'react'
import { createChart, CandlestickSeries } from 'lightweight-charts'
import { Play, Pause, RotateCcw, SkipBack, SkipForward, Rewind, FastForward, TrendingUp } from 'lucide-react'

export default function PlanningChartPage() {
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

        // Sample candle data - 15 days of sample data
        const sampleData = [
          { time: '2024-01-01', open: 100.0, high: 105.0, low: 98.0, close: 103.0 },
          { time: '2024-01-02', open: 103.0, high: 108.0, low: 102.0, close: 106.5 },
          { time: '2024-01-03', open: 106.5, high: 109.0, low: 104.0, close: 104.5 },
          { time: '2024-01-04', open: 104.5, high: 107.0, low: 103.0, close: 105.5 },
          { time: '2024-01-05', open: 105.5, high: 110.0, low: 105.0, close: 108.0 },
          { time: '2024-01-08', open: 108.0, high: 112.0, low: 107.0, close: 111.0 },
          { time: '2024-01-09', open: 111.0, high: 113.0, low: 109.0, close: 110.0 },
          { time: '2024-01-10', open: 110.0, high: 112.0, low: 107.0, close: 108.5 },
          { time: '2024-01-11', open: 108.5, high: 111.0, low: 106.0, close: 109.5 },
          { time: '2024-01-12', open: 109.5, high: 114.0, low: 109.0, close: 113.0 },
          { time: '2024-01-15', open: 113.0, high: 116.0, low: 112.0, close: 115.0 },
          { time: '2024-01-16', open: 115.0, high: 117.0, low: 113.0, close: 114.0 },
          { time: '2024-01-17', open: 114.0, high: 116.0, low: 111.0, close: 112.5 },
          { time: '2024-01-18', open: 112.5, high: 115.0, low: 110.0, close: 114.5 },
          { time: '2024-01-19', open: 114.5, high: 118.0, low: 114.0, close: 117.0 },
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
    console.log(`Planning Chart: ${!isPlaying ? 'Playing' : 'Paused'}`)
  }

  const handleRewind = () => {
    // TODO: Implement rewind logic when charts are integrated
    console.log('Planning Chart: Rewind')
  }

  const handleStepBack = () => {
    // TODO: Implement step back logic when charts are integrated
    console.log('Planning Chart: Step Back')
  }

  const handleStepForward = () => {
    // TODO: Implement step forward logic when charts are integrated
    console.log('Planning Chart: Step Forward')
  }

  const handleFastForward = () => {
    // TODO: Implement fast forward logic when charts are integrated
    console.log('Planning Chart: Fast Forward')
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