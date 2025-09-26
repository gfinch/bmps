import { useRef, useState, useEffect } from 'react'
import { createChart, CandlestickSeries } from 'lightweight-charts'
import { Play, Pause, SkipBack, SkipForward, Rewind, FastForward } from 'lucide-react'
import { useEventPlayback } from '../hooks/useEventPlayback.jsx'

export default function PlanningChartPage() {
  const chartContainerRef = useRef()
  const chartRef = useRef()
  
  // Use event playback hook for planning phase
  const playback = useEventPlayback('planning')

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

        // Create candlestick series (empty for now)
        const candlestickSeries = chart.addSeries(CandlestickSeries, {
          upColor: '#26a69a',
          downColor: '#ef5350',
          borderVisible: false,
          wickUpColor: '#26a69a',
          wickDownColor: '#ef5350',
        })

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

  // Event handlers using playback service
  const handlePlay = () => {
    playback.togglePlayPause()
    console.log(`Playback ${playback.isPlaying ? 'paused' : 'playing'}`)
  }

  const handleStepBackward = () => {
    playback.stepBackward()
    console.log(`Stepped backward to timestamp: ${playback.currentTimestamp}`)
  }

  const handleStepForward = () => {
    playback.stepForward()
    console.log(`Stepped forward to timestamp: ${playback.currentTimestamp}`)
  }

  const handleRewind = () => {
    playback.rewind()
    console.log(`Rewound to timestamp: ${playback.currentTimestamp}`)
  }

  const handleFastForward = () => {
    playback.fastForward()
    console.log(`Fast forwarded to timestamp: ${playback.currentTimestamp}`)
  }

  return (
    <div className="flex-1 flex flex-col">
      {/* Chart Container - fills remaining space */}
      <div className="flex-1 min-h-0">
        <div 
          ref={chartContainerRef} 
          className="w-full h-full min-h-[300px]"
        />
      </div>

      {/* Playback Info Panel - for testing */}
      <div className="flex-shrink-0 px-4 py-2 bg-gray-50 border-t border-gray-200">
        <div className="flex items-center justify-center space-x-6 text-sm text-gray-600">
          <div>
            <span className="font-medium">Timestamp:</span> {playback.currentTimestamp || 'None'}
          </div>
          <div>
            <span className="font-medium">Visible Events:</span> {playback.visibleEvents.length}
          </div>
          <div>
            <span className="font-medium">Total Timestamps:</span> {playback.totalTimestamps}
          </div>
          <div>
            <span className="font-medium">Status:</span> {playback.isPlaying ? 'Playing' : 'Paused'}
          </div>
          <div>
            <span className="font-medium">Position:</span> {playback.getPositionPercent().toFixed(1)}%
          </div>
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
        </div>
      </div>
    </div>
  )
}