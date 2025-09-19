import { useEffect, useRef, useState } from 'react'
import { createChart, CandlestickSeries, createSeriesMarkers } from 'lightweight-charts'
import { Play, Pause, RotateCcw, SkipBack, SkipForward, Rewind, FastForward, TrendingUp } from 'lucide-react'
import { useEventStore } from '../stores/eventStore'
import { WorkPlanSeries, createWorkPlanData } from '../components/WorkPlanSeries'
import { DaytimeExtremeSeries, createDaytimeExtremeData } from '../components/DaytimeExtremeSeries'
import { WorkPlanType } from '../types/workplan'

export default function PlanningChartPage() {
  const chartContainerRef = useRef()
  const chartRef = useRef()
  const candlestickSeriesRef = useRef()
  const workPlanSeriesRef = useRef()
  const extremeHighSeriesRef = useRef() // Store custom series for high extremes
  const extremeLowSeriesRef = useRef()  // Store custom series for low extremes
  const markersPluginRef = useRef()
  const [config, setConfig] = useState(null)

  // Get event store data and actions
  const { 
    planningPlayback, 
    getVisiblePlanningCandles, 
    getVisiblePlanningZones,
    getVisiblePlanningSwingPoints,
    getVisiblePlanningDaytimeExtremes,
    setPlanningPlaying,
    stepPlanningForward,
    stepPlanningBackward,
    resetPlanningPlayback,
    fastForwardToEnd
  } = useEventStore()

  const { isPlaying, currentTime } = planningPlayback
  const visibleCandles = getVisiblePlanningCandles()
  const visibleZones = getVisiblePlanningZones()
  const visibleSwingPoints = getVisiblePlanningSwingPoints()
  const visibleExtremes = getVisiblePlanningDaytimeExtremes()

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

        // Create markers plugin for swing points
        const markersPlugin = createSeriesMarkers(candlestickSeries)
        markersPluginRef.current = markersPlugin

        // Create WorkPlan series for Supply and Demand zones
        const workPlanSeriesInstance = new WorkPlanSeries({
          workPlanColors: {
            supply: {
              fill: 'rgba(255, 0, 0, 0.15)',    // Light red shading
              topLine: '#ff0000',               // Red top line
              bottomLine: '#00ff00',            // Green bottom line
              sides: '#000000',                 // Black sides
            },
            demand: {
              fill: 'rgba(0, 255, 0, 0.15)',   // Light green shading
              topLine: '#00ff00',               // Green top line
              bottomLine: '#ff0000',            // Red bottom line
              sides: '#000000',                 // Black sides
            },
            ended: {
              fill: 'rgba(128, 128, 128, 0.15)', // Light gray shading for ended zones
            },
          },
        })

        // Add WorkPlan custom series to chart
        const workPlanSeries = chart.addCustomSeries(workPlanSeriesInstance)
        workPlanSeriesRef.current = workPlanSeries

        // Initialize with empty data - will be updated from event store
        workPlanSeries.setData([])

        // Add DaytimeExtreme custom series to chart (separate series for highs and lows)
        const extremeHighSeriesInstance = new DaytimeExtremeSeries({
          lineColor: '#000000',
          lineWidth: 1,
          labelColor: '#000000',
          labelSize: 10,
        })
        const extremeHighSeries = chart.addCustomSeries(extremeHighSeriesInstance)
        extremeHighSeriesRef.current = extremeHighSeries

        const extremeLowSeriesInstance = new DaytimeExtremeSeries({
          lineColor: '#000000',
          lineWidth: 1,
          labelColor: '#000000',
          labelSize: 10,
        })
        const extremeLowSeries = chart.addCustomSeries(extremeLowSeriesInstance)
        extremeLowSeriesRef.current = extremeLowSeries

        // Initialize with empty data - will be updated from event store
        extremeHighSeries.setData([])
        extremeLowSeries.setData([])

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

  // Update WorkPlan zones when visible zones change
  useEffect(() => {
    if (!workPlanSeriesRef.current) {
      console.log('WorkPlan series not ready yet');
      return;
    }
    
    if (!visibleZones || visibleZones.length === 0) {
      console.log('No visible zones to display yet');
      workPlanSeriesRef.current.setData([]);
      return;
    }
    
    try {
      console.log('Updating WorkPlan zones with', visibleZones.length, 'zones');
      console.log('Zones:', visibleZones);
      
      // Convert ProcessedPlanZone to WorkPlanEvent format
      const workPlanData = visibleZones.map(zone => {
        const workPlanEvent = {
          id: zone.id,
          type: zone.type === 'Supply' ? WorkPlanType.Supply : WorkPlanType.Demand,
          startTime: zone.startTime,
          endTime: zone.endTime,
          high: zone.high,
          low: zone.low,
        };
        
        return createWorkPlanData(workPlanEvent);
      });
      
      workPlanSeriesRef.current.setData(workPlanData);
      console.log('WorkPlan zones updated successfully');
    } catch (error) {
      console.error('Error updating WorkPlan zones:', error);
    }
  }, [visibleZones])

  // Update swing point markers when visible swing points change
  useEffect(() => {
    if (!markersPluginRef.current) {
      console.log('Markers plugin not ready for swing points yet');
      return;
    }
    
    if (!visibleSwingPoints || visibleSwingPoints.length === 0) {
      console.log('No visible swing points to display yet');
      // Clear existing markers
      markersPluginRef.current.setMarkers([]);
      return;
    }
    
    try {
      console.log('Updating swing points with', visibleSwingPoints.length, 'points');
      console.log('Swing points:', visibleSwingPoints);
      
      // Convert ProcessedSwingPoint to Lightweight Charts markers
      const markers = visibleSwingPoints.map(point => {
        // In trading: 
        // - Swing Low: Price makes a low point and turns up (we mark below with up arrow)
        // - Swing High: Price makes a high point and turns down (we mark above with down arrow)
        // The direction indicates the next move after the swing point
        const isSwingLow = point.direction === 'Up';   // Price was going down, now going up = swing low
        const isSwingHigh = point.direction === 'Down'; // Price was going up, now going down = swing high
        
        console.log(`Swing point: ${point.direction} at level ${point.level}, treating as ${isSwingLow ? 'Low' : 'High'}`);
        
        return {
          time: Math.floor(point.timestamp / 1000), // Convert to TradingView time format
          position: isSwingLow ? 'belowBar' : 'aboveBar',
          color: isSwingLow ? '#26a69a' : '#ef5350', // Teal for lows, red for highs (match candle colors)
          shape: isSwingLow ? 'arrowUp' : 'arrowDown',
          size: 1,
        };
      });
      
      markersPluginRef.current.setMarkers(markers);
      console.log('Swing point markers updated successfully');
    } catch (error) {
      console.error('Error updating swing point markers:', error);
    }
  }, [visibleSwingPoints])

  // Update daytime extremes when visible extremes change
  // Daytime Extremes effect - using separate custom series for highs and lows
  useEffect(() => {
    if (!extremeHighSeriesRef.current || !extremeLowSeriesRef.current) {
      console.log('Extreme series not ready yet');
      return;
    }
    
    if (!visibleExtremes || visibleExtremes.length === 0) {
      console.log('No visible extremes to display yet');
      extremeHighSeriesRef.current.setData([]);
      extremeLowSeriesRef.current.setData([]);
      return;
    }
    
    try {
      console.log('Updating daytime extremes with', visibleExtremes.length, 'extremes');
      console.log('Extremes:', visibleExtremes);
      
      // Separate extremes into highs and lows based on description
      const highExtremes = [];
      const lowExtremes = [];
      
      visibleExtremes.forEach(extreme => {
        const description = extreme.description || '';
        const isHigh = description.toLowerCase().includes('high');
        const isLow = description.toLowerCase().includes('low');
        
        const extremeData = createDaytimeExtremeData({
          session: description,
          level: extreme.level,
          startTime: extreme.timestamp,
          endTime: extreme.endTime || undefined,
        });
        
        if (isHigh) {
          highExtremes.push(extremeData);
        } else if (isLow) {
          lowExtremes.push(extremeData);
        } else {
          // Default to low if we can't determine
          console.warn('Could not determine if extreme is high or low, defaulting to low:', description);
          lowExtremes.push(extremeData);
        }
      });
      
      // Sort each series by time
      highExtremes.sort((a, b) => a.time - b.time);
      lowExtremes.sort((a, b) => a.time - b.time);
      
      // Update both series
      extremeHighSeriesRef.current.setData(highExtremes);
      extremeLowSeriesRef.current.setData(lowExtremes);
      
      console.log(`Daytime extremes updated: ${highExtremes.length} highs, ${lowExtremes.length} lows`);
      
    } catch (error) {
      console.error('Error updating daytime extremes:', error);
    }
  }, [visibleExtremes])

  // WorkPlan management functions
  const addSupplyZone = (high, low, startTime, endTime = null) => {
    if (!workPlanSeriesRef.current) return;
    
    const newWorkPlan = {
      id: `supply-${Date.now()}`,
      type: WorkPlanType.Supply,
      startTime: startTime || Date.now(),
      endTime: endTime,
      high: high,
      low: low,
    };
    
    console.log('Adding Supply zone:', newWorkPlan);
    // In a real implementation, you would maintain a list of all zones and update the series
    // For now, this is just a demonstration of the function signature
  };

  const addDemandZone = (high, low, startTime, endTime = null) => {
    if (!workPlanSeriesRef.current) return;
    
    const newWorkPlan = {
      id: `demand-${Date.now()}`,
      type: WorkPlanType.Demand,
      startTime: startTime || Date.now(),
      endTime: endTime,
      high: high,
      low: low,
    };
    
    console.log('Adding Demand zone:', newWorkPlan);
    // In a real implementation, you would maintain a list of all zones and update the series
  };

  const endWorkPlanZone = (zoneId, endTime = null) => {
    console.log(`Ending WorkPlan zone ${zoneId} at ${endTime || Date.now()}`);
    // In a real implementation, you would update the zone with an endTime and refresh the series data
  };

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