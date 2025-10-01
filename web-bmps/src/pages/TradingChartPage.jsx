import { useRef, useState, useEffect } from 'react'
import { createChart, CandlestickSeries } from 'lightweight-charts'
import { Play, Pause, SkipBack, SkipForward, Rewind, FastForward, Scissors } from 'lucide-react'
import { useEventPlayback } from '../hooks/useEventPlayback.jsx'
import { ChartRenderingService } from '../services/chartRenderingService.jsx'
import { CandlestickRenderer, DaytimeExtremeRenderer, PlanZoneRenderer, OrderRenderer, TradingDirectionRenderer } from '../renderers/index.js'

export default function TradingChartPage() {
  const chartContainerRef = useRef()
  const chartRef = useRef()
  const chartServiceRef = useRef()
  
  // Use event playback hook for trading phase
  const playback = useEventPlayback('trading')

  // Layer visibility state
  const [layerVisibility, setLayerVisibility] = useState({
    planZones: true,
    daytimeExtremes: true,
    orders: true,
    tradingDirection: true,
  })

  // Clip tool state
  const [isClipToolActive, setIsClipToolActive] = useState(false)

  // Initialize playback to first timestamp when page loads
  useEffect(() => {
    // Only rewind if we don't already have a current timestamp
    if (playback.currentTimestamp === null && playback.totalTimestamps > 0) {
      playback.rewind()
    }
  }, [playback.totalTimestamps]) // Depend on totalTimestamps to trigger when events are available

  // Initialize chart
  useEffect(() => {
    if (!chartContainerRef.current || chartRef.current) return

    const initChart = () => {
      try {
        const container = chartContainerRef.current
        if (!container || container.clientWidth === 0 || container.clientHeight === 0) {
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

        // Create chart rendering service
        chartServiceRef.current = new ChartRenderingService(chart, 'trading')
        
        // Create candlestick renderer with swing point options
        const candlestickRenderer = new CandlestickRenderer(chart, {
          showSwingPoints: true, // Enable swing point markers
          swingPointColors: {
            up: '#26a69a',   // Green for up swings (swing lows)
            down: '#ef5350'  // Red for down swings (swing highs)  
          }
        })
        chartServiceRef.current.addRenderer('Candle', candlestickRenderer)

        // Create daytime extreme renderer for horizontal lines with labels
        const daytimeExtremeRenderer = new DaytimeExtremeRenderer(chart, {
          lineColor: '#000000',    // Black lines
          lineWidth: 2,
          labelColor: '#000000',   // Black labels
          labelSize: 12,
          labelOffset: 15
        })
        chartServiceRef.current.addRenderer('DaytimeExtreme', daytimeExtremeRenderer)

        // Create plan zone renderer for demand/supply zones
        const planZoneRenderer = new PlanZoneRenderer(chart, {
          demandColor: '#22C55E',  // Green for demand zones
          supplyColor: '#EF4444',  // Red for supply zones
          grayColor: '#6B7280',    // Gray for secondary lines
          lineWidth: 2,
          labelColor: '#374151',
          labelSize: 12,
          labelOffset: 15
        })
        chartServiceRef.current.addRenderer('PlanZone', planZoneRenderer)

        // Create order renderer for trading orders
        const orderRenderer = new OrderRenderer(chart, {
          plannedColor: '#7C3AED',    // Purple for planned state
          placedColor: '#2563EB',     // Blue for placed state
          filledColor: '#2563EB',     // Blue for filled state  
          profitBoxFill: 'rgba(34, 197, 94, 0.15)',   // Green with transparency
          profitBoxStroke: '#22C55E',  // Green stroke
          lossBoxFill: 'rgba(239, 68, 68, 0.15)',     // Red with transparency  
          lossBoxStroke: '#EF4444',    // Red stroke
          greyBoxFill: 'rgba(107, 114, 128, 0.12)',   // Grey with transparency
          greyBoxStroke: '#6B7280',    // Grey stroke
          cancelledColor: '#6B7280',   // Grey for cancelled
          lineWidth: 2,
          labelColor: '#374151',
          labelSize: 12,
          labelOffset: 15
        })
        chartServiceRef.current.addRenderer('Order', orderRenderer)

        // Create trading direction renderer for direction change markers
        const tradingDirectionRenderer = new TradingDirectionRenderer(chart, {
          showTradingDirection: true,
          colors: {
            up: '#26a69a',   // Green for up direction
            down: '#ef5350'  // Red for down direction
          },
          size: 2 // Larger size for visibility
        })
        chartServiceRef.current.addRenderer('TradingDirection', tradingDirectionRenderer)

        // Store chart reference
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
          if (chartServiceRef.current) {
            chartServiceRef.current.destroy()
            chartServiceRef.current = null
          }
          if (chart) {
            chart.remove()
          }
          chartRef.current = null
        }
      } catch (error) {
        console.error('Error creating chart:', error)
      }
    }

    const cleanup = initChart()
    return cleanup
  }, [])

  // Update chart when visible events change
  useEffect(() => {
    if (chartServiceRef.current) {
      chartServiceRef.current.updateVisibleEvents(playback.visibleEvents, playback.currentTimestamp)
    }
  }, [playback.visibleEvents, playback.currentTimestamp])

  // Update renderer visibility when layer visibility state changes
  useEffect(() => {
    if (chartServiceRef.current) {
      // Update plan zones visibility
      chartServiceRef.current.setRendererVisibility('PlanZone', layerVisibility.planZones)
      
      // Update daytime extremes visibility
      chartServiceRef.current.setRendererVisibility('DaytimeExtreme', layerVisibility.daytimeExtremes)
      
      // Update orders visibility
      chartServiceRef.current.setRendererVisibility('Order', layerVisibility.orders)
      
      // Update trading direction visibility
      chartServiceRef.current.setRendererVisibility('TradingDirection', layerVisibility.tradingDirection)
      
      console.log('Trading chart layer visibility updated:', layerVisibility)
    }
  }, [layerVisibility])

  // Event handlers using playback service
  const handlePlay = () => {
    playback.togglePlayPause()
    console.log(`Trading playback ${playback.isPlaying ? 'paused' : 'playing'}`)
  }

  const handleStepBackward = () => {
    // Pause if currently playing
    if (playback.isPlaying) {
      playback.pause()
    }
    playback.stepBackward()
    console.log(`Trading stepped backward to timestamp: ${playback.currentTimestamp}`)
  }

  const handleStepForward = () => {
    // Pause if currently playing
    if (playback.isPlaying) {
      playback.pause()
    }
    playback.stepForward()
    console.log(`Trading stepped forward to timestamp: ${playback.currentTimestamp}`)
  }

  const handleRewind = () => {
    // Pause if currently playing
    if (playback.isPlaying) {
      playback.pause()
    }
    playback.rewind()
    console.log(`Trading rewound to timestamp: ${playback.currentTimestamp}`)
  }

  const handleFastForward = () => {
    // Pause if currently playing
    if (playback.isPlaying) {
      playback.pause()
    }
    playback.fastForward()
    console.log(`Trading fast forwarded to timestamp: ${playback.currentTimestamp}`)
  }

  // Clip tool handlers
  const handleClipToolToggle = () => {
    setIsClipToolActive(!isClipToolActive)
    console.log(`Trading clip tool ${!isClipToolActive ? 'activated' : 'deactivated'}`)
  }

  const handleChartClick = (event) => {
    if (!isClipToolActive || !chartRef.current) return
    
    const chart = chartRef.current
    const containerRect = chartContainerRef.current.getBoundingClientRect()
    const x = event.clientX - containerRect.left
    
    try {
      // Convert pixel coordinate to time coordinate
      const logicalIndex = chart.timeScale().coordinateToLogical(x)
      if (logicalIndex !== null && logicalIndex !== undefined) {
        const timestamp = chart.timeScale().coordinateToTime(x)
        if (timestamp !== null && timestamp !== undefined) {
          // Convert seconds to milliseconds for our playback system
          const timestampMs = timestamp * 1000
          playback.jumpToTimestamp(timestampMs)
          console.log(`Trading clipped to timestamp: ${timestampMs}`)
          
          // Deactivate clip tool after use
          setIsClipToolActive(false)
        }
      }
    } catch (error) {
      console.error('Error converting chart click to timestamp:', error)
    }
  }

  return (
    <div className="flex-1 flex flex-col">
      {/* Chart Container - fills remaining space */}
      <div className="flex-1 min-h-0">
        <div 
          ref={chartContainerRef} 
          className="w-full h-full min-h-[300px]"
          onClick={handleChartClick}
          style={{ cursor: isClipToolActive ? 'crosshair' : 'default' }}
        />
      </div>

      {/* Layer Controls - between chart and media controls */}
      <div className="flex-shrink-0 py-2 border-b border-gray-200">
        <div className="flex items-center justify-center space-x-4">
          <label className="flex items-center space-x-2">
            <input
              type="checkbox"
              checked={layerVisibility.planZones}
              onChange={(e) => {
                const newVisibility = {
                  ...layerVisibility,
                  planZones: e.target.checked
                }
                setLayerVisibility(newVisibility)
              }}
              className="w-4 h-4 text-blue-600 bg-gray-100 border-gray-300 rounded focus:ring-blue-500"
            />
            <span className="text-sm font-medium text-gray-700">Plan Zones</span>
          </label>
          <label className="flex items-center space-x-2">
            <input
              type="checkbox"
              checked={layerVisibility.daytimeExtremes}
              onChange={(e) => {
                const newVisibility = {
                  ...layerVisibility,
                  daytimeExtremes: e.target.checked
                }
                setLayerVisibility(newVisibility)
              }}
              className="w-4 h-4 text-blue-600 bg-gray-100 border-gray-300 rounded focus:ring-blue-500"
            />
            <span className="text-sm font-medium text-gray-700">Extreme Lines</span>
          </label>
          <label className="flex items-center space-x-2">
            <input
              type="checkbox"
              checked={layerVisibility.orders}
              onChange={(e) => {
                const newVisibility = {
                  ...layerVisibility,
                  orders: e.target.checked
                }
                setLayerVisibility(newVisibility)
              }}
              className="w-4 h-4 text-blue-600 bg-gray-100 border-gray-300 rounded focus:ring-blue-500"
            />
            <span className="text-sm font-medium text-gray-700">Orders</span>
          </label>
          <label className="flex items-center space-x-2">
            <input
              type="checkbox"
              checked={layerVisibility.tradingDirection}
              onChange={(e) => {
                const newVisibility = {
                  ...layerVisibility,
                  tradingDirection: e.target.checked
                }
                setLayerVisibility(newVisibility)
              }}
              className="w-4 h-4 text-blue-600 bg-gray-100 border-gray-300 rounded focus:ring-blue-500"
            />
            <span className="text-sm font-medium text-gray-700">Trading Direction</span>
          </label>
        </div>
      </div>

      {/* Media Controls - fixed at bottom */}
      <div className="flex-shrink-0 py-2">
        <div className="flex items-center justify-center space-x-3">
          <button
            onClick={handleRewind}
            className="p-3 text-gray-600 hover:text-gray-800 bg-white border border-gray-300 rounded-md hover:bg-gray-50"
            title="Rewind"
          >
            <Rewind className="w-5 h-5" />
          </button>
          <button
            onClick={handleStepBackward}
            className="p-3 text-gray-600 hover:text-gray-800 bg-white border border-gray-300 rounded-md hover:bg-gray-50"
            title="Step Back"
          >
            <SkipBack className="w-5 h-5" />
          </button>
          <button
            onClick={handlePlay}
            className="p-3 btn-primary"
            title={playback.isPlaying ? 'Pause' : 'Play'}
          >
            {playback.isPlaying ? (
              <Pause className="w-5 h-5" />
            ) : (
              <Play className="w-5 h-5" />
            )}
          </button>
          <button
            onClick={handleStepForward}
            className="p-3 text-gray-600 hover:text-gray-800 bg-white border border-gray-300 rounded-md hover:bg-gray-50"
            title="Step Forward"
          >
            <SkipForward className="w-5 h-5" />
          </button>
          <button
            onClick={handleFastForward}
            className="p-3 text-gray-600 hover:text-gray-800 bg-white border border-gray-300 rounded-md hover:bg-gray-50"
            title="Fast Forward"
          >
            <FastForward className="w-5 h-5" />
          </button>
          <button
            onClick={handleClipToolToggle}
            className={`p-3 border border-gray-300 rounded-md ${isClipToolActive 
              ? 'bg-blue-500 text-white hover:bg-blue-600' 
              : 'text-gray-600 hover:text-gray-800 bg-white hover:bg-gray-50'
            }`}
            title="Clip Tool - Click to activate, then click on chart to jump to timestamp"
          >
            <Scissors className="w-5 h-5" />
          </button>
        </div>
      </div>
    </div>
  )
}