import { useEffect, useRef, useState } from 'react'
import { createChart, CandlestickSeries } from 'lightweight-charts'
import { Play, Pause, RotateCcw, SkipBack, SkipForward, Rewind, FastForward, TrendingUp } from 'lucide-react'
import { useEventStore } from '../stores/eventStore'

export default function PlanningChartPage() {
  const chartContainerRef = useRef()
  const chartRef = useRef()
  const candlestickSeriesRef = useRef()
  const [config, setConfig] = useState(null)

  // Get event store data and actions
  const { 
    planningPlayback, 
    getVisiblePlanningCandles, 
    setPlanningPlaying,
    stepPlanningForward,
    stepPlanningBackward,
    resetPlanningPlayback,
    fastForwardToEnd
  } = useEventStore()

  const { isPlaying, currentTime } = planningPlayback
  const visibleCandles = getVisiblePlanningCandles()

  // Auto-playback effect
  useEffect(() => {
    if (!isPlaying) return

    const interval = setInterval(() => {
      // Step forward automatically during playback
      stepPlanningForward()
    }, 1000) // 1 second intervals

    return () => clearInterval(interval)
  }, [isPlaying, stepPlanningForward])

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

        // Store series reference for updates
        candlestickSeriesRef.current = candlestickSeries

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

  // Update chart data when visible candles change
  useEffect(() => {
    if (!candlestickSeriesRef.current) {
      console.log('Chart series not ready yet');
      return;
    }
    
    if (!visibleCandles || visibleCandles.length === 0) {
      console.log('No visible candles to display yet');
      return;
    }
    
    try {
      console.log('Updating chart with', visibleCandles.length, 'candles');
      console.log('First candle:', visibleCandles[0]);
      console.log('Last candle:', visibleCandles[visibleCandles.length - 1]);
      candlestickSeriesRef.current.setData(visibleCandles);
    } catch (error) {
      console.error('Error updating chart data:', error);
    }
  }, [visibleCandles])

  const handlePlayPause = () => {
    setPlanningPlaying(!isPlaying)
  }

  const handleRewind = () => {
    resetPlanningPlayback()
  }

  const handleStepBack = () => {
    stepPlanningBackward()
  }

  const handleStepForward = () => {
    stepPlanningForward()
  }

  const handleFastForward = () => {
    fastForwardToEnd()
    console.log('Planning Chart: Fast Forward to End')
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