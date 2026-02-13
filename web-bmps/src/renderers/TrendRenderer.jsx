/**
 * TrendRenderer - Renders trend analysis indicators from TechnicalAnalysis events
 * Displays moving average lines (EMA, SMA, Short-term, Long-term) on main chart
 */
import { LineSeries } from 'lightweight-charts'
import { BaseRenderer } from '../services/chartRenderingService.jsx'
import { extractEventType } from '../utils/eventTypeUtils.js'

class TrendRenderer extends BaseRenderer {
  constructor(chart, options = {}, chartService = null) {
    super(chart)
    this.chartService = chartService
    this.options = {
      // Moving Average colors
      shortTermColor: '#10B981',   // Green for short-term MA
      longTermColor: '#EF4444',    // Red for long-term MA
      
      // Line styles
      lineWidth: 2,
      
      ...options
    }
    
    // Main chart series for moving averages
    this.shortTermSeries = null
    this.longTermSeries = null
    
    // Data storage for crossover detection
    this.trendData = []
    this.currentAnalysis = null
    this.previousMAs = null // Store previous MA values for crossover detection
    this.crossoverMarkers = [] // Store crossover markers
    this.currentNewYorkOffset = 0 // Store current offset for marker display
  }

  initialize() {
    if (this.shortTermSeries) return // Already initialized
    
    
    // Create moving average series on main chart
    this.shortTermSeries = this.chart.addSeries(LineSeries, {
      color: this.options.shortTermColor,
      lineWidth: this.options.lineWidth,
      title: 'Short MA',
      lastValueVisible: false,
      priceLineVisible: false,
    })
    
    this.longTermSeries = this.chart.addSeries(LineSeries, {
      color: this.options.longTermColor,
      lineWidth: this.options.lineWidth,
      title: 'Long MA',
      lastValueVisible: false,
      priceLineVisible: false,
    })
  }

  update(allEvents, currentTimestamp, newYorkOffset = 0) {
    if (!this.shortTermSeries) return
    
    // Filter for TechnicalAnalysis events
    const technicalAnalysisEvents = allEvents.filter(eventWrapper => {
      const actualEvent = eventWrapper.event || eventWrapper
      const eventType = extractEventType(actualEvent)
      const isTechnical = eventType === 'TechnicalAnalysis'
      return isTechnical
    })
    
    if (technicalAnalysisEvents.length === 0) return
    
    // Process technical analysis events
    const processedData = this.processTechnicalAnalysisEvents(technicalAnalysisEvents, newYorkOffset)
    
    // Update moving average series
    this.updateMovingAverages(processedData, newYorkOffset)
    
  }

  /**
   * Process TechnicalAnalysis events into chart-ready data
   */
  processTechnicalAnalysisEvents(events, newYorkOffset = 0) {
    const processedData = {
      shortTerm: [],
      longTerm: []
    }
    
    // Reset crossover markers for new data
    this.crossoverMarkers = []
    
    events.forEach((eventWrapper, index) => {
      const actualEvent = eventWrapper.event || eventWrapper
      // Handle both correct and typo versions
      const technicalAnalysis = actualEvent.technicalAnalysis || actualEvent.techncialAnalysis
      
      if (!technicalAnalysis || !technicalAnalysis.trendAnalysis) return
      
      const timestamp = actualEvent.timestamp
      const time = Math.floor((timestamp + newYorkOffset) / 1000)
      const trend = technicalAnalysis.trendAnalysis
      
      
      // Extract current MA values for crossover detection
      const currentMAs = {
        shortTerm: null,
        longTerm: null,
        adx: null // Add ADX for trend strength evaluation
      }
      
      // Extract ADX value for trend strength
      if (trend.adx != null) {
        currentMAs.adx = Number(trend.adx)
      }
      
      // Moving averages
      if (trend.shortTermMA != null) {
        const value = Number(trend.shortTermMA)
        processedData.shortTerm.push({ time, value })
        currentMAs.shortTerm = value
      }
      if (trend.shortTermMovingAverage != null) {
        const value = Number(trend.shortTermMovingAverage)
        processedData.shortTerm.push({ time, value })
        currentMAs.shortTerm = value
      }
      if (trend.longTermMA != null) {
        const value = Number(trend.longTermMA)
        processedData.longTerm.push({ time, value })
        currentMAs.longTerm = value
      }
      if (trend.longTermMovingAverage != null) {
        const value = Number(trend.longTermMovingAverage)
        processedData.longTerm.push({ time, value })
        currentMAs.longTerm = value
      }
      
      // Detect crossovers using raw timestamp (no offset for crossover logic)
      const crossovers = this.detectCrossovers(currentMAs, timestamp)
      this.crossoverMarkers.push(...crossovers)
    })

    // Sort all data by time
    Object.keys(processedData).forEach(key => {
      processedData[key].sort((a, b) => a.time - b.time)
    })

    return processedData
  }

  /**
   * Update moving average series on main chart
   */
  updateMovingAverages(data, newYorkOffset = 0) {
    // Store the offset for marker display
    this.currentNewYorkOffset = newYorkOffset
    
    if (data.shortTerm.length > 0) {
      this.shortTermSeries.setData(data.shortTerm)
    }
    if (data.longTerm.length > 0) {
      this.longTermSeries.setData(data.longTerm)
    }
    
    // Update crossover markers
    this.updateCrossoverMarkers()
  }

  /**
   * Set visibility of the trend renderer
   */
  setVisibility(visible) {
    
    if (!this.shortTermSeries) {
      return
    }
    
    // Toggle moving averages visibility
    this.shortTermSeries.applyOptions({ visible })
    this.longTermSeries.applyOptions({ visible })
    
  }

  /**
   * Get the current trend analysis for external use
   */
  getCurrentTrendAnalysis() {
    return this.currentAnalysis
  }

  /**
   * Get trend direction based on latest data
   */
  getTrendDirection() {
    if (!this.currentAnalysis) return 'Unknown'
    
    const trend = this.currentAnalysis.trendAnalysis
    if (!trend) return 'Unknown'
    
    if (trend.isUptrend && trend.isStrongTrend) return 'Strong Uptrend'
    if (trend.isDowntrend && trend.isStrongTrend) return 'Strong Downtrend'
    if (trend.isUptrend) return 'Weak Uptrend'
    if (trend.isDowntrend) return 'Weak Downtrend'
    return 'Sideways'
  }

  /**
   * Detect crossover events between moving averages
   * @param {Object} currentMAs - Current MA values {ema, sma, shortTerm, longTerm}
   * @param {number} timestamp - Current timestamp 
   * @returns {Array} Array of crossover events
   */
  detectCrossovers(currentMAs, timestamp) {
    const crossovers = []
    
    if (!this.previousMAs) {
      this.previousMAs = currentMAs
      return crossovers
    }
    
    const prev = this.previousMAs
    const curr = currentMAs
    
    // Golden Cross: Faster MA crosses above Slower MA (bullish)
    // Death Cross: Faster MA crosses below Slower MA (bearish)
    
    // Short-term MA vs Long-term MA (Primary Golden/Death Cross)
    if (curr.shortTerm && curr.longTerm && prev.shortTerm && prev.longTerm) {
      if (prev.shortTerm <= prev.longTerm && curr.shortTerm > curr.longTerm) {
        crossovers.push({
          type: 'golden_cross',
          description: 'Golden Cross: Short MA > Long MA',
          timestamp,
          price: curr.shortTerm,
          signal: 'bullish',
          adx: curr.adx || null // Include ADX value for trend strength evaluation
        })
      } else if (prev.shortTerm >= prev.longTerm && curr.shortTerm < curr.longTerm) {
        crossovers.push({
          type: 'death_cross',
          description: 'Death Cross: Short MA < Long MA', 
          timestamp,
          price: curr.shortTerm,
          signal: 'bearish',
          adx: curr.adx || null // Include ADX value for trend strength evaluation
        })
      }
    }
    
    this.previousMAs = currentMAs
    return crossovers
  }

  /**
   * Convert crossover events to chart markers
   * @param {Array} crossovers - Array of crossover events
   * @param {number} newYorkOffset - New York offset in milliseconds (for display time)
   * @returns {Array} Array of chart markers
   */
  createCrossoverMarkers(crossovers, newYorkOffset = 0) {
    return crossovers.map(crossover => {
      const isBullish = crossover.signal === 'bullish'
      const isPrimary = (crossover.type.includes('golden_cross') || crossover.type.includes('death_cross')) && !crossover.type.includes('secondary')
      
      // Check ADX trend strength to determine color
      const hasWeakTrend = crossover.adx !== null && crossover.adx <= 25
      const isStrongTrend = crossover.adx !== null && crossover.adx > 25
      
      // Color logic: Gray for weak trend (ADX ≤ 25), normal colors for strong trend
      let markerColor
      if (hasWeakTrend) {
        markerColor = '#9CA3AF' // Gray for weak trend - should not trade
      } else {
        markerColor = isBullish ? '#FBBF24' : '#000000' // Yellow for Golden Cross, Black for Death Cross
      }
      
      return {
        time: Math.floor((crossover.timestamp + newYorkOffset) / 1000),
        position: isBullish ? 'belowBar' : 'aboveBar',
        color: markerColor,
        shape: isPrimary ? (isBullish ? 'arrowUp' : 'arrowDown') : 'circle', // Arrow direction based on signal
        text: isPrimary ? (isBullish ? 'GC' : 'DC') : (isBullish ? 'gc' : 'dc'), // Golden Cross / Death Cross
        size: isPrimary ? 1 : 0.8
      }
    })
  }

  /**
   * Update crossover markers on the chart
   */
  updateCrossoverMarkers() {
    if (this.crossoverMarkers.length === 0) {
      return
    }

    // Get the candlestick renderer to add our markers alongside swing point markers
    const candlestickRenderer = this.getCandlestickRenderer()
    if (!candlestickRenderer) {
      return
    }

    // Check if candlestick renderer has a method to add additional markers
    if (typeof candlestickRenderer.addAdditionalMarkers === 'function') {
      const markers = this.createCrossoverMarkers(this.crossoverMarkers, this.currentNewYorkOffset)
      candlestickRenderer.addAdditionalMarkers('crossover', markers)
    }
  }

  /**
   * Get the candlestick renderer from the chart service
   * @returns {Object|null} Candlestick renderer or null
   */
  getCandlestickRenderer() {
    if (this.chartService) {
      return this.chartService.renderers.get('Candle')
    }
    return null
  }

  destroy() {
    
    // Remove main chart series
    if (this.shortTermSeries) {
      this.chart.removeSeries(this.shortTermSeries)
      this.shortTermSeries = null
    }
    if (this.longTermSeries) {
      this.chart.removeSeries(this.longTermSeries)
      this.longTermSeries = null
    }
    
    // Clear data
    this.trendData = []
    this.currentAnalysis = null
  }
}

export default TrendRenderer