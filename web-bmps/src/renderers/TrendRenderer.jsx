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
      emaColor: '#2563EB',        // Blue for EMA
      smaColor: '#F59E0B',        // Orange for SMA  
      shortTermColor: '#10B981',   // Green for short-term MA
      longTermColor: '#EF4444',    // Red for long-term MA
      
      // Line styles
      lineWidth: 2,
      
      ...options
    }
    
    // Main chart series for moving averages
    this.emaSeries = null
    this.smaSeries = null
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
    if (this.emaSeries) return // Already initialized
    
    console.debug('TrendRenderer: Starting initialization...')
    
    // Create moving average series on main chart
    this.emaSeries = this.chart.addSeries(LineSeries, {
      color: this.options.emaColor,
      lineWidth: this.options.lineWidth,
      title: 'EMA',
      lastValueVisible: false,
      priceLineVisible: false,
    })
    
    this.smaSeries = this.chart.addSeries(LineSeries, {
      color: this.options.smaColor,
      lineWidth: this.options.lineWidth,
      title: 'SMA',
      lastValueVisible: false,
      priceLineVisible: false,
    })
    
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
    
    console.debug('TrendRenderer: Initialized with moving averages', {
      ema: !!this.emaSeries,
      sma: !!this.smaSeries,
      shortTerm: !!this.shortTermSeries,
      longTerm: !!this.longTermSeries
    })
  }

  update(allEvents, currentTimestamp, newYorkOffset = 0) {
    if (!this.emaSeries) return
    
    console.debug('TrendRenderer: Update called', {
      totalEvents: allEvents.length,
      currentTimestamp,
      newYorkOffset,
      hasEMASeries: !!this.emaSeries
    })
    
    // Filter for TechnicalAnalysis events
    const technicalAnalysisEvents = allEvents.filter(eventWrapper => {
      const actualEvent = eventWrapper.event || eventWrapper
      const eventType = extractEventType(actualEvent)
      const isTechnical = eventType === 'TechnicalAnalysis'
      if (isTechnical) {
        console.debug('TrendRenderer: Found TechnicalAnalysis event', {
          eventType,
          hasAnalysis: !!actualEvent.technicalAnalysis,
          analysisKeys: actualEvent.technicalAnalysis ? Object.keys(actualEvent.technicalAnalysis) : []
        })
      }
      return isTechnical
    })
    
    console.debug('TrendRenderer: Filtered events', {
      technicalCount: technicalAnalysisEvents.length
    })
    
    if (technicalAnalysisEvents.length === 0) return
    
    // Process technical analysis events
    const processedData = this.processTechnicalAnalysisEvents(technicalAnalysisEvents, newYorkOffset)
    
    console.debug('TrendRenderer: Processed data', {
      emaCount: processedData.ema.length,
      smaCount: processedData.sma.length,
      shortTermCount: processedData.shortTerm.length,
      longTermCount: processedData.longTerm.length,
      sampleEMA: processedData.ema.slice(0, 3)
    })
    
    // Update moving average series
    this.updateMovingAverages(processedData, newYorkOffset)
    
    console.debug(`TrendRenderer: Updated with ${technicalAnalysisEvents.length} technical analysis events`)
  }

  /**
   * Process TechnicalAnalysis events into chart-ready data
   */
  processTechnicalAnalysisEvents(events, newYorkOffset = 0) {
    const processedData = {
      ema: [],
      sma: [],
      shortTerm: [],
      longTerm: []
    }
    
    // Reset crossover markers for new data
    this.crossoverMarkers = []
    
    console.debug('TrendRenderer: Processing events', {
      eventCount: events.length,
      firstEvent: events[0]
    })
    
    events.forEach((eventWrapper, index) => {
      const actualEvent = eventWrapper.event || eventWrapper
      // Handle both correct and typo versions
      const technicalAnalysis = actualEvent.technicalAnalysis || actualEvent.techncialAnalysis
      
      console.debug(`TrendRenderer: Event ${index}`, {
        hasTechnicalAnalysis: !!technicalAnalysis,
        trendAnalysisKeys: technicalAnalysis ? Object.keys(technicalAnalysis) : [],
        trendAnalysisData: technicalAnalysis?.trendAnalysis
      })
      
      if (!technicalAnalysis || !technicalAnalysis.trendAnalysis) return
      
      const timestamp = actualEvent.timestamp
      const time = Math.floor((timestamp + newYorkOffset) / 1000)
      const trend = technicalAnalysis.trendAnalysis
      
      console.debug(`TrendRenderer: Processing trend data for time ${time}`, trend)
      
      // Extract current MA values for crossover detection
      const currentMAs = {
        ema: null,
        sma: null,
        shortTerm: null,
        longTerm: null
      }
      
      // Moving averages - checking all possible property names
      if (trend.ema != null) {
        const value = Number(trend.ema)
        processedData.ema.push({ time, value })
        currentMAs.ema = value
      }
      if (trend.exponentialMovingAverage != null) {
        const value = Number(trend.exponentialMovingAverage)
        processedData.ema.push({ time, value })
        currentMAs.ema = value
      }
      if (trend.sma != null) {
        const value = Number(trend.sma)
        processedData.sma.push({ time, value })
        currentMAs.sma = value
      }
      if (trend.simpleMovingAverage != null) {
        const value = Number(trend.simpleMovingAverage)
        processedData.sma.push({ time, value })
        currentMAs.sma = value
      }
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
    
    console.debug('TrendRenderer: Processed data results', {
      emaCount: processedData.ema.length,
      smaCount: processedData.sma.length,
      shortTermCount: processedData.shortTerm.length,
      longTermCount: processedData.longTerm.length,
      sampleData: {
        ema: processedData.ema.slice(0, 3),
        sma: processedData.sma.slice(0, 3)
      }
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
    
    console.debug('TrendRenderer: Updating moving averages with data', {
      emaCount: data.ema.length,
      smaCount: data.sma.length,
      shortTermCount: data.shortTerm.length,
      longTermCount: data.longTerm.length,
      sampleEMA: data.ema.slice(0, 3)
    })
    
    if (data.ema.length > 0) {
      this.emaSeries.setData(data.ema)
      console.debug('TrendRenderer: Set EMA data', data.ema.length, 'points')
    }
    if (data.sma.length > 0) {
      this.smaSeries.setData(data.sma)
      console.debug('TrendRenderer: Set SMA data', data.sma.length, 'points')
    }
    if (data.shortTerm.length > 0) {
      this.shortTermSeries.setData(data.shortTerm)
      console.debug('TrendRenderer: Set Short Term data', data.shortTerm.length, 'points')
    }
    if (data.longTerm.length > 0) {
      this.longTermSeries.setData(data.longTerm)
      console.debug('TrendRenderer: Set Long Term data', data.longTerm.length, 'points')
    }
    
    // Update crossover markers
    this.updateCrossoverMarkers()
    
    console.debug('TrendRenderer: Finished updating all moving averages and crossover markers', {
      crossoverCount: this.crossoverMarkers.length
    })
  }

  /**
   * Set visibility of the trend renderer
   */
  setVisibility(visible) {
    console.debug(`TrendRenderer: setVisibility called with ${visible}`)
    
    if (!this.emaSeries) {
      console.debug('TrendRenderer: No EMA series found, cannot set visibility')
      return
    }
    
    // Toggle moving averages visibility
    this.emaSeries.applyOptions({ visible })
    this.smaSeries.applyOptions({ visible })
    this.shortTermSeries.applyOptions({ visible })
    this.longTermSeries.applyOptions({ visible })
    
    console.debug(`TrendRenderer: Visibility set to ${visible} for all series`)
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
          signal: 'bullish'
        })
        console.debug('TrendRenderer: GOLDEN CROSS detected!', {
          time: new Date(timestamp),
          prevShort: prev.shortTerm,
          prevLong: prev.longTerm,
          currShort: curr.shortTerm,
          currLong: curr.longTerm
        })
      } else if (prev.shortTerm >= prev.longTerm && curr.shortTerm < curr.longTerm) {
        crossovers.push({
          type: 'death_cross',
          description: 'Death Cross: Short MA < Long MA', 
          timestamp,
          price: curr.shortTerm,
          signal: 'bearish'
        })
        console.debug('TrendRenderer: DEATH CROSS detected!', {
          time: new Date(timestamp),
          prevShort: prev.shortTerm,
          prevLong: prev.longTerm,
          currShort: curr.shortTerm,
          currLong: curr.longTerm
        })
      }
    }
    
    // EMA vs SMA crossover (Secondary signal) - DISABLED
    // Commenting out secondary crossovers to reduce noise
    /*
    if (curr.ema && curr.sma && prev.ema && prev.sma) {
      if (prev.ema <= prev.sma && curr.ema > curr.sma) {
        crossovers.push({
          type: 'golden_cross_secondary',
          description: 'Golden Cross: EMA > SMA',
          timestamp,
          price: curr.ema,
          signal: 'bullish'
        })
        console.debug('TrendRenderer: Secondary golden cross detected (EMA > SMA)', {
          time: new Date(timestamp),
          prevEMA: prev.ema,
          prevSMA: prev.sma,
          currEMA: curr.ema,
          currSMA: curr.sma
        })
      } else if (prev.ema >= prev.sma && curr.ema < curr.sma) {
        crossovers.push({
          type: 'death_cross_secondary',
          description: 'Death Cross: EMA < SMA',
          timestamp,
          price: curr.ema,
          signal: 'bearish'
        })
        console.debug('TrendRenderer: Secondary death cross detected (EMA < SMA)', {
          time: new Date(timestamp),
          prevEMA: prev.ema,
          prevSMA: prev.sma,
          currEMA: curr.ema,
          currSMA: curr.sma
        })
      }
    }
    */
    
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
      
      return {
        time: Math.floor((crossover.timestamp + newYorkOffset) / 1000),
        position: isBullish ? 'belowBar' : 'aboveBar',
        color: isBullish ? '#FBBF24' : '#000000', // Yellow for Golden Cross, Black for Death Cross
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
      console.debug('TrendRenderer: No crossover markers to display')
      return
    }

    // Get the candlestick renderer to add our markers alongside swing point markers
    const candlestickRenderer = this.getCandlestickRenderer()
    if (!candlestickRenderer) {
      console.debug('TrendRenderer: No candlestick renderer found for markers')
      return
    }

    // Check if candlestick renderer has a method to add additional markers
    if (typeof candlestickRenderer.addAdditionalMarkers === 'function') {
      const markers = this.createCrossoverMarkers(this.crossoverMarkers, this.currentNewYorkOffset)
      candlestickRenderer.addAdditionalMarkers('crossover', markers)
      console.debug(`TrendRenderer: Added ${markers.length} crossover markers via addAdditionalMarkers`)
    } else {
      // Fallback: Log the crossover events for now
      console.debug('TrendRenderer: Crossover events detected (markers not yet supported):', 
        this.crossoverMarkers.map(cross => ({
          type: cross.type,
          signal: cross.signal,
          time: new Date(cross.timestamp),
          description: cross.description
        }))
      )
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
    console.debug('TrendRenderer: Destroying renderer')
    
    // Remove main chart series
    if (this.emaSeries) {
      this.chart.removeSeries(this.emaSeries)
      this.emaSeries = null
    }
    if (this.smaSeries) {
      this.chart.removeSeries(this.smaSeries)
      this.smaSeries = null
    }
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