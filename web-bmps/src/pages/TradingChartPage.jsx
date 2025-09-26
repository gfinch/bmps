import { useRef, useState, useEffect } from 'react'
import { createChart, CandlestickSeries } from 'lightweight-charts'
import { Play, Pause, SkipBack, SkipForward, Rewind, FastForward } from 'lucide-react'
import { useEventPlayback } from '../hooks/useEventPlayback.jsx'

export default function TradingChartPage() {
  const chartContainerRef = useRef()
  const chartRef = useRef()
  
  // Use event playback hook for trading phase
  const playback = useEventPlayback('trading')

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

  return (
    <div className="flex-1 flex flex-col">
      {/* Chart Container - fills remaining space */}
      <div className="flex-1 min-h-0">
        <div 
          ref={chartContainerRef} 
          className="w-full h-full min-h-[300px]"
        />
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