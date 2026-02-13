import { useRef, useState, useEffect } from 'react'
import { useLocation } from 'react-router-dom'
import { createChart } from 'lightweight-charts'
import { Play, Pause, SkipBack, SkipForward, Rewind, FastForward, Scissors, Calendar, AlertCircle } from 'lucide-react'
import { useEventPlayback } from '../hooks/useEventPlayback.jsx'
import { usePlanning } from '../hooks/usePlanning.jsx'
import { ChartRenderingService } from '../services/chartRenderingService.jsx'
import { CandlestickRenderer, DaytimeExtremeRenderer, PlanZoneRenderer } from '../renderers/index.js'
import phaseService from '../services/phaseService.jsx'

export default function PlanningChartPage() {
  const location = useLocation()
  const chartContainerRef = useRef()
  const chartRef = useRef()
  const chartServiceRef = useRef()
  
  // Use event playback hook for planning phase
  const playback = useEventPlayback('planning')

  // Use planning hook for configuration and starting planning
  const {
    startPlanning,
    isInitializing,
    canStart,
    error,
    hasError,
    clearEvents
  } = usePlanning()

  // Get today's date in YYYY-MM-DD format
  const today = new Date().toISOString().split('T')[0]
  
  // Trading date state - use stored config date if available, otherwise default to today
  const [tradingDate, setTradingDate] = useState(() => {
    return phaseService.currentConfig?.tradingDate || today
  })

  // Layer visibility state
  const [layerVisibility, setLayerVisibility] = useState({
    planZones: true,
    daytimeExtremes: true,
    // Future layers can be added here
    // swingPoints: true,
  })

  // Clip tool state
  const [isClipToolActive, setIsClipToolActive] = useState(false)

  // Sync trading date with phaseService when component mounts or config changes
  useEffect(() => {
    if (phaseService.currentConfig?.tradingDate) {
      setTradingDate(phaseService.currentConfig.tradingDate)
    }
  }, []) // Run once on mount to pick up any existing config

  // Handle navigation from other pages (e.g., clicking date in Results page)
  useEffect(() => {
    if (location.state?.tradingDate) {
      const { tradingDate: navDate, autoStart } = location.state
      setTradingDate(navDate)
      
      // Auto-start planning if requested
      if (autoStart) {
        // Clear the navigation state to prevent re-triggering
        window.history.replaceState({}, document.title)
        
        // Start planning after a short delay to ensure state is updated
        setTimeout(async () => {
          try {
            clearEvents()
            await startPlanning({ tradingDate: navDate })
          } catch (err) {
            console.error('Failed to auto-start planning:', err)
          }
        }, 100)
      }
    }
  }, [location.state])

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
            mode: 0, // Normal mode - crosshairs follow mouse freely
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

        // Store chart reference
        chartRef.current = chart

        // Initialize chart rendering service
        chartServiceRef.current = new ChartRenderingService(chart, 'planning')
        
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
      chartServiceRef.current.updateVisibleEvents(playback.visibleEvents)
      
      // Auto-pan chart to show the latest candle
      if (chartRef.current && playback.visibleEvents.length > 0) {
        const candleEvents = playback.visibleEvents.filter(e => {
          const event = e.event || e
          return event.candle !== undefined
        })
        
        if (candleEvents.length > 0) {
          // Fit all candles in view
          chartRef.current.timeScale().fitContent()
        }
      }
    }
  }, [playback.visibleEvents, playback.newYorkOffset])

  // Update New York offset when it changes
  useEffect(() => {
    if (chartServiceRef.current && playback.newYorkOffset !== null) {
      chartServiceRef.current.setNewYorkOffset(playback.newYorkOffset)
    }
  }, [playback.newYorkOffset])

  // Update renderer visibility when layer visibility state changes
  useEffect(() => {
    if (chartServiceRef.current) {
      // Update plan zones visibility
      chartServiceRef.current.setRendererVisibility('PlanZone', layerVisibility.planZones)
      
      // Update daytime extremes visibility
      chartServiceRef.current.setRendererVisibility('DaytimeExtreme', layerVisibility.daytimeExtremes)
      
    }
  }, [layerVisibility])

  // Event handlers using playback service
  const handlePlay = () => {
    playback.togglePlayPause()
  }

  const handleStepBackward = () => {
    // Pause if currently playing
    if (playback.isPlaying) {
      playback.pause()
    }
    playback.stepBackward()
  }

  const handleStepForward = () => {
    // Pause if currently playing
    if (playback.isPlaying) {
      playback.pause()
    }
    playback.stepForward()
  }

  const handleRewind = () => {
    // Pause if currently playing
    if (playback.isPlaying) {
      playback.pause()
    }
    playback.rewind()
  }

  const handleFastForward = () => {
    // Pause if currently playing
    if (playback.isPlaying) {
      playback.pause()
    }
    playback.fastForward()
  }

  const handleClipToolToggle = () => {
    setIsClipToolActive(!isClipToolActive)
  }

  const handleStartPlanning = async () => {
    try {
      // Clear all buffers and reset playback
      clearEvents()
      
      // Start planning with the selected trading date
      await startPlanning({ tradingDate })
    } catch (err) {
      console.error('Failed to start planning:', err)
      // Error is already handled by the hook
    }
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
          // Convert seconds to milliseconds - this is in NY time (offset-adjusted)
          const timestampMs = timestamp * 1000
          // Convert back to UTC by subtracting the offset
          const utcTimestampMs = timestampMs - (playback.newYorkOffset || 0)
          playback.jumpToTimestamp(utcTimestampMs)
          
          // Center the chart view on the clicked timestamp
          // Calculate a logical range centered on the clicked point
          const currentRange = chart.timeScale().getVisibleLogicalRange()
          if (currentRange) {
            const rangeSize = currentRange.to - currentRange.from
            const halfRange = rangeSize / 2
            const newRange = {
              from: logicalIndex - halfRange,
              to: logicalIndex + halfRange
            }
            chart.timeScale().setVisibleLogicalRange(newRange)
          }
          
          // Auto-deactivate the clip tool after use
          setIsClipToolActive(false)
        }
      }
    } catch (error) {
      console.warn('Error converting chart coordinate to timestamp:', error)
    }
  }

  return (
    <div className="flex-1 flex flex-col">
      {/* Chart Container - fills remaining space */}
      <div className="flex-1 min-h-0">
        <div 
          ref={chartContainerRef} 
          className={`w-full h-full min-h-[300px] ${isClipToolActive ? 'cursor-crosshair' : ''}`}
          onClick={handleChartClick}
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
        </div>
      </div>

      {/* Media Controls and Configuration - fixed at bottom */}
      <div className="flex-shrink-0 py-3 border-t border-gray-200">
        <div className="flex items-center justify-between px-4">
          {/* Left: Trading Date Configuration */}
          <div className="flex items-center space-x-3">
            <label className="flex items-center space-x-2 text-sm font-medium text-gray-700">
              <Calendar className="w-4 h-4" />
              <span>Trading Date:</span>
            </label>
            <input
              type="date"
              value={tradingDate}
              onChange={(e) => setTradingDate(e.target.value)}
              className="px-3 py-2 border border-gray-300 rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              disabled={isInitializing}
            />
            <button
              onClick={handleStartPlanning}
              disabled={!canStart || isInitializing}
              className="px-4 py-2 btn-primary text-sm disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {isInitializing ? 'Starting...' : 'Start Planning'}
            </button>
            {hasError && (
              <div className="flex items-center space-x-1 text-red-600">
                <AlertCircle className="w-4 h-4" />
                <span className="text-sm">{error}</span>
              </div>
            )}
          </div>

          {/* Center: Playback Controls */}
          <div className="flex items-center space-x-3">
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

          {/* Right: Spacer for balance */}
          <div className="w-64"></div>
        </div>
      </div>
    </div>
  )
}