/**
 * CandlestickRenderer - Renders candlestick series from Candle events with optional swing point markers
 */
import { CandlestickSeries, createSeriesMarkers } from 'lightweight-charts'
import { BaseRenderer } from '../services/chartRenderingService.jsx'
import { extractEventType } from '../utils/eventTypeUtils.js'

class CandlestickRenderer extends BaseRenderer {
  constructor(chart, options = {}) {
    super(chart)
    this.series = null
    this.options = {
      showSwingPoints: true, // Default to showing swing points
      swingPointColors: {
        up: '#26a69a',   // Green for up swings (swing lows)
        down: '#ef5350'  // Red for down swings (swing highs)
      },
      ...options
    }
    this.currentSwingPoints = []
    this.markersPlugin = null
  }

  initialize() {
    if (this.series) return
    this.series = this.chart.addSeries(CandlestickSeries, {
      upColor: '#26a69a',
      downColor: '#ef5350',
      borderVisible: false,
      wickUpColor: '#26a69a',
      wickDownColor: '#ef5350',
    })
    
    // Store series reference on chart for easy access by other components
    this.chart.candlestickSeries = this.series
    
    // Create markers plugin for swing points
    this.markersPlugin = createSeriesMarkers(this.series)
  }

  /**
   * Configure swing point display options
   * @param {Object} options - Options object
   * @param {boolean} options.showSwingPoints - Whether to show swing points
   * @param {Object} options.swingPointColors - Color configuration for swing points
   */
  setSwingPointOptions(options) {
    this.options = { ...this.options, ...options }
    // Re-render swing points with new options if they exist
    if (this.currentSwingPoints.length > 0) {
      this.updateSwingPointMarkers(this.currentSwingPoints)
    }
  }

  /**
   * Get the candlestick series (for other renderers that need to attach to it)
   * @returns {Object|null} The candlestick series or null if not initialized
   */
  getSeries() {
    return this.series
  }

  update(allEvents, currentTimestamp, newYorkOffset = 0) {
    if (!this.series) return
    
    // Filter events by type
    const candleEvents = []
    const swingPointEvents = []
    
    allEvents.forEach(eventWrapper => {
      const actualEvent = eventWrapper.event || eventWrapper
      const eventType = extractEventType(actualEvent)
      
      if (eventType === 'Candle') {
        candleEvents.push(eventWrapper)
      } else if (eventType === 'SwingPoint') {
        swingPointEvents.push(eventWrapper)
      }
    })
    
    // Update candlestick data
    if (candleEvents.length > 0) {
      const chartData = this.processCandleEvents(candleEvents, newYorkOffset)
      this.series.setData(chartData)
    }
    
    // Update swing point markers
    this.updateSwingPointMarkers(swingPointEvents, newYorkOffset)
  }

  /**
   * Update swing point markers on the candlestick series
   * @param {Array} swingPointEvents - Array of swing point events
   * @param {number} newYorkOffset - New York offset in milliseconds
   */
  updateSwingPointMarkers(swingPointEvents, newYorkOffset = 0) {
    if (!this.markersPlugin || !this.options.showSwingPoints) {
      return
    }

    this.currentSwingPoints = swingPointEvents
    const markers = this.processSwingPointEvents(swingPointEvents, newYorkOffset)
    this.markersPlugin.setMarkers(markers)
  }

  /**
   * Process swing point events into TradingView markers
   * @param {Array} swingPointEvents - Swing point events
   * @param {number} newYorkOffset - New York offset in milliseconds
   * @returns {Array} Array of marker objects for TradingView
   */
  processSwingPointEvents(swingPointEvents, newYorkOffset = 0) {
    const markers = []
    
    swingPointEvents.forEach(eventWrapper => {
      const actualEvent = eventWrapper.event || eventWrapper
      const swingPoint = actualEvent.swingPoint
      
      if (!swingPoint) return
      
      // Extract direction from SwingPoint structure
      const direction = this.extractDirection(swingPoint.direction)
      const level = swingPoint.level
      const timestamp = actualEvent.timestamp
      
      if (!direction || level == null || !timestamp) return
      
      const isUpSwing = direction.toLowerCase() === 'up'
      
      markers.push({
        time: Math.floor((timestamp + newYorkOffset) / 1000), // Apply New York offset and convert to seconds
        position: isUpSwing ? 'belowBar' : 'aboveBar',
        color: isUpSwing ? this.options.swingPointColors.up : this.options.swingPointColors.down,
        shape: isUpSwing ? 'arrowUp' : 'arrowDown',
        text: '', // No text label
        size: 1 // Small size
      })
    })
    
    // Sort by time to ensure proper ordering
    return markers.sort((a, b) => a.time - b.time)
  }

  /**
   * Extract direction from SwingPoint direction object
   * @param {Object|string} direction - Direction from SwingPoint
   * @returns {string|null} Direction string or null
   */
  extractDirection(direction) {
    if (typeof direction === 'string') {
      return direction
    }
    
    if (typeof direction === 'object' && direction !== null) {
      const keys = Object.keys(direction)
      if (keys.length > 0) {
        return keys[0] // Return first key (Up, Down, Doji)
      }
    }
    
    return null
  }

  processCandleEvents(candleEvents, newYorkOffset = 0) {
    // Handle duplicate timestamps - take the latest one found
    const uniqueCandles = new Map()
    candleEvents.forEach(eventWrapper => {
      const actualEvent = eventWrapper.event || eventWrapper
      const timestamp = actualEvent.timestamp
      if (!uniqueCandles.has(timestamp)) {
        uniqueCandles.set(timestamp, eventWrapper)
      } else {
        uniqueCandles.set(timestamp, eventWrapper)
      }
    })
    return Array.from(uniqueCandles.values())
      .map(eventWrapper => {
        const actualEvent = eventWrapper.event || eventWrapper
        const candle = actualEvent.candle
        if (!candle) return null
        const open = candle.open
        const high = candle.high
        const low = candle.low
        const close = candle.close
        if ([open, high, low, close].some(v => v == null || isNaN(v))) return null
        return {
          time: Math.floor((actualEvent.timestamp + newYorkOffset) / 1000), // Apply New York offset and convert to seconds
          open: Number(open),
          high: Number(high),
          low: Number(low),
          close: Number(close)
        }
      })
      .filter(candle => candle !== null)
      .sort((a, b) => a.time - b.time)
  }

  destroy() {
    if (this.markersPlugin) {
      // Clear any markers before cleaning up
      this.markersPlugin.setMarkers([])
      this.markersPlugin = null
    }
    
    if (this.series) {
      this.chart.removeSeries(this.series)
      this.series = null
      // Clean up chart reference
      if (this.chart.candlestickSeries === this.series) {
        this.chart.candlestickSeries = null
      }
    }
    this.currentSwingPoints = []
  }
}

export default CandlestickRenderer
