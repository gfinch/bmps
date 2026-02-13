/**
 * ADXRenderer - Renders ADX (Average Directional Index), RSI, and Trend Strength from TechnicalAnalysis events
 * ADX, RSI, and Trend Strength are displayed on the same chart since they use similar scales (0-100)
 * 
 * Trend Strength is calculated after MA crossovers and measures how quickly the moving averages
 * are diverging relative to the Keltner channel width, providing a normalized measure of trend momentum.
 */
import { LineSeries } from 'lightweight-charts'
import { BaseRenderer } from '../services/chartRenderingService.jsx'
import { extractEventType } from '../utils/eventTypeUtils.js'

class ADXRenderer extends BaseRenderer {
  constructor(chart, options = {}) {
    super(chart)
    this.options = {
      // ADX line style
      adxColor: '#2563EB',        // Blue for ADX line
      lineWidth: 2,
      
      // RSI line style
      rsiColor: '#EF4444',        // Red for RSI line
      
      // Inverse RSI line style
      iRsiColor: '#EAB308',       // Yellow for inverse RSI line
      
      // Trend strength after cross line style
      trendStrengthColor: '#10B981', // Green for trend strength
      
      // Threshold lines
      showThresholds: true,
      strongTrendLine: 25,        // ADX > 25 indicates strong trend
      veryStrongTrendLine: 50,    // ADX > 50 indicates very strong trend
      rsiOverboughtLine: 70,      // RSI > 70 indicates overbought
      rsiOversoldLine: 30,        // RSI < 30 indicates oversold
      thresholdColor: '#6B7280',  // Gray for threshold lines
      thresholdStyle: 1,          // Dashed line
      
      ...options
    }
    
    // Chart series
    this.adxSeries = null
    this.rsiSeries = null
    this.iRsiSeries = null
    this.trendStrengthSeries = null
    this.strongTrendSeries = null
    this.veryStrongTrendSeries = null
    this.rsiOverboughtSeries = null
    this.rsiOversoldSeries = null
    
    // Data for crossover tracking
    this.previousMAs = null
  }

  initialize() {
    if (this.adxSeries) return // Already initialized
    
    
    // Create ADX line series
    this.adxSeries = this.chart.addSeries(LineSeries, {
      color: this.options.adxColor,
      lineWidth: this.options.lineWidth,
      title: 'ADX',
      lastValueVisible: true,
      priceLineVisible: false,
    })
    
    // Create RSI line series
    this.rsiSeries = this.chart.addSeries(LineSeries, {
      color: this.options.rsiColor,
      lineWidth: this.options.lineWidth,
      title: 'RSI',
      lastValueVisible: true,
      priceLineVisible: false,
    })
    
    // Create inverse RSI line series
    this.iRsiSeries = this.chart.addSeries(LineSeries, {
      color: this.options.iRsiColor,
      lineWidth: this.options.lineWidth,
      title: 'iRSI',
      lastValueVisible: true,
      priceLineVisible: false,
    })
    
    // Create Trend Strength line series
    this.trendStrengthSeries = this.chart.addSeries(LineSeries, {
      color: this.options.trendStrengthColor,
      lineWidth: this.options.lineWidth,
      title: 'Trend Strength',
      lastValueVisible: true,
      priceLineVisible: false,
    })
    
    // Create threshold lines if enabled
    if (this.options.showThresholds) {
      // ADX threshold lines
      this.strongTrendSeries = this.chart.addSeries(LineSeries, {
        color: this.options.thresholdColor,
        lineWidth: 1,
        lineStyle: this.options.thresholdStyle,
        title: 'ADX(25)',
        lastValueVisible: false,
        priceLineVisible: true,
      })
      
      this.veryStrongTrendSeries = this.chart.addSeries(LineSeries, {
        color: this.options.thresholdColor,
        lineWidth: 1,
        lineStyle: this.options.thresholdStyle,
        title: 'ADX(50)',
        lastValueVisible: false,
        priceLineVisible: true,
      })
      
      // RSI threshold lines
      this.rsiOverboughtSeries = this.chart.addSeries(LineSeries, {
        color: this.options.thresholdColor,
        lineWidth: 1,
        lineStyle: this.options.thresholdStyle,
        title: 'RSI(70)',
        lastValueVisible: false,
        priceLineVisible: true,
      })
      
      this.rsiOversoldSeries = this.chart.addSeries(LineSeries, {
        color: this.options.thresholdColor,
        lineWidth: 1,
        lineStyle: this.options.thresholdStyle,
        title: 'RSI(30)',
        lastValueVisible: false,
        priceLineVisible: true,
      })
    }
  }

  update(allEvents, currentTimestamp, newYorkOffset = 0) {
    if (!this.adxSeries) return
    
    // Filter for TechnicalAnalysis events
    const technicalAnalysisEvents = allEvents.filter(eventWrapper => {
      const actualEvent = eventWrapper.event || eventWrapper
      const eventType = extractEventType(actualEvent)
      return eventType === 'TechnicalAnalysis'
    })
    
    if (technicalAnalysisEvents.length === 0) return
    
    // Process technical analysis events
    const processedData = this.processIndicatorEvents(technicalAnalysisEvents, newYorkOffset)
    
    // Update ADX series
    if (processedData.adx.length > 0) {
      this.adxSeries.setData(processedData.adx)
    }
    
    // Update RSI series
    if (processedData.rsi.length > 0) {
      this.rsiSeries.setData(processedData.rsi)
    }
    
    // Update inverse RSI series
    if (processedData.iRsi.length > 0) {
      this.iRsiSeries.setData(processedData.iRsi)
    }
    
    // Update Trend Strength series
    if (processedData.trendStrength.length > 0) {
      this.trendStrengthSeries.setData(processedData.trendStrength)
    }
    
    // Update threshold lines with flat data across the time range
    const allData = [...processedData.adx, ...processedData.rsi, ...processedData.iRsi, ...processedData.trendStrength]
    if (allData.length > 0) {
      this.updateThresholdLines(allData)
    }
    
  }

  /**
   * Process TechnicalAnalysis events to extract ADX, RSI, and inverse RSI data
   */
  processIndicatorEvents(events, newYorkOffset = 0) {
    const adxData = []
    const rsiData = []
    const iRsiData = []
    const trendStrengthData = []
    
    // Array to store all event data for trend strength calculation
    const allEventData = []
    
    events.forEach((eventWrapper, index) => {
      const actualEvent = eventWrapper.event || eventWrapper
      // Handle both correct and typo versions
      const technicalAnalysis = actualEvent.technicalAnalysis || actualEvent.techncialAnalysis
      
      if (!technicalAnalysis) {
        return
      }
      
      const timestamp = actualEvent.timestamp
      const time = Math.floor((timestamp + newYorkOffset) / 1000)
      
      // Store event data for trend strength calculation
      const eventData = {
        time,
        timestamp,
        trendAnalysis: technicalAnalysis.trendAnalysis,
        volatilityAnalysis: technicalAnalysis.volatilityAnalysis
      }
      allEventData.push(eventData)
      
      // Extract ADX from trend analysis
      if (technicalAnalysis.trendAnalysis && technicalAnalysis.trendAnalysis.adx != null) {
        const adxValue = Number(technicalAnalysis.trendAnalysis.adx)
        adxData.push({ time, value: adxValue })
      }
      
      // Extract RSI from momentum analysis
      if (technicalAnalysis.momentumAnalysis && technicalAnalysis.momentumAnalysis.rsi != null) {
        const rsiValue = Number(technicalAnalysis.momentumAnalysis.rsi)
        rsiData.push({ time, value: rsiValue })
        
        // Calculate inverse RSI (100 - RSI)
        const iRsiValue = 100 - rsiValue
        iRsiData.push({ time, value: iRsiValue })
      }
    })
    
    // Calculate trend strength after crosses
    const trendStrengthValues = this.calculateTrendStrengthAfterCrosses(allEventData)
    trendStrengthData.push(...trendStrengthValues)
    
    // Sort by time to ensure proper ordering
    adxData.sort((a, b) => a.time - b.time)
    rsiData.sort((a, b) => a.time - b.time)
    iRsiData.sort((a, b) => a.time - b.time)
    trendStrengthData.sort((a, b) => a.time - b.time)
    
    return { adx: adxData, rsi: rsiData, iRsi: iRsiData, trendStrength: trendStrengthData }
  }

  /**
   * Calculate trend strength using rolling 5-point window
   * For each point, uses current + 4 previous trend analyses
   */
  calculateTrendStrengthAfterCrosses(allEventData) {
    const trendStrengthData = []
    
    if (allEventData.length < 5) {
      return []
    }
    
    // Calculate strength for each point using a rolling 5-point window
    for (let i = 4; i < allEventData.length; i++) {
      // Get current point and 4 previous points (5 total)
      const fivePoints = allEventData.slice(i - 4, i + 1)
      
      if (fivePoints.length === 5) {
        const currentEvent = fivePoints[4] // Last point in window
        const strength = this.calculateStrengthOfTrendAfterCross(fivePoints)
        
        trendStrengthData.push({
          time: currentEvent.time,
          value: strength * 100 // Scale to 0-100
        })
      }
    }
    
    return trendStrengthData
  }

  /**
   * Calculate strength of trend
   * Simple ratio: MA spread / Keltner channel width
   * Clamped between 0 and 1
   */
  calculateStrengthOfTrendAfterCross(lastFiveTrend) {
    if (lastFiveTrend.length !== 5) {
      return 0.0
    }
    
    // Get the last point (current)
    const lastPoint = lastFiveTrend[lastFiveTrend.length - 1]
    
    if (!lastPoint.trendAnalysis || !lastPoint.volatilityAnalysis) {
      return 0.0
    }
    
    const trend = lastPoint.trendAnalysis
    const keltner = lastPoint.volatilityAnalysis.keltnerChannels
    
    if (!keltner) {
      return 0.0
    }
    
    // Calculate MA spread (how far apart short and long MAs are)
    const maSpread = Math.abs(trend.shortTermMA - trend.longTermMA)
    
    // Calculate absolute Keltner channel width
    const channelWidth = Math.abs(keltner.upperBand - keltner.lowerBand)
    
    // Ratio of MA spread to channel width
    const rawStrength = maSpread / channelWidth
    const clampedStrength = Math.max(0.0, Math.min(1.0, rawStrength))
    
    // Occasional logging to debug
    
    return clampedStrength
  }

  /**
   * Update threshold lines to span the time range of the indicator data
   */
  updateThresholdLines(allData) {
    if (!this.options.showThresholds || allData.length === 0) return
    
    // Find the time range across all data
    const times = allData.map(d => d.time).sort((a, b) => a - b)
    const startTime = times[0]
    const endTime = times[times.length - 1]
    
    // If start and end are the same (only one data point), skip threshold updates
    // to avoid duplicate timestamps error
    if (startTime === endTime) {
      return
    }
    
    // Create flat threshold lines for ADX
    const strongTrendData = [
      { time: startTime, value: this.options.strongTrendLine },
      { time: endTime, value: this.options.strongTrendLine }
    ]
    
    const veryStrongTrendData = [
      { time: startTime, value: this.options.veryStrongTrendLine },
      { time: endTime, value: this.options.veryStrongTrendLine }
    ]
    
    // Create flat threshold lines for RSI
    const rsiOverboughtData = [
      { time: startTime, value: this.options.rsiOverboughtLine },
      { time: endTime, value: this.options.rsiOverboughtLine }
    ]
    
    const rsiOversoldData = [
      { time: startTime, value: this.options.rsiOversoldLine },
      { time: endTime, value: this.options.rsiOversoldLine }
    ]
    
    // Update ADX threshold series
    if (this.strongTrendSeries) {
      this.strongTrendSeries.setData(strongTrendData)
    }
    
    if (this.veryStrongTrendSeries) {
      this.veryStrongTrendSeries.setData(veryStrongTrendData)
    }
    
    // Update RSI threshold series
    if (this.rsiOverboughtSeries) {
      this.rsiOverboughtSeries.setData(rsiOverboughtData)
    }
    
    if (this.rsiOversoldSeries) {
      this.rsiOversoldSeries.setData(rsiOversoldData)
    }
  }

  /**
   * Set visibility of the ADX renderer
   */
  setVisibility(visible) {
    
    if (!this.adxSeries) {
      return
    }
    
    // Toggle ADX, RSI, and Trend Strength visibility
    this.adxSeries.applyOptions({ visible })
    this.rsiSeries.applyOptions({ visible })
    this.iRsiSeries.applyOptions({ visible })
    this.trendStrengthSeries.applyOptions({ visible })
    
    // Toggle threshold lines visibility
    if (this.strongTrendSeries) {
      this.strongTrendSeries.applyOptions({ visible })
    }
    if (this.veryStrongTrendSeries) {
      this.veryStrongTrendSeries.applyOptions({ visible })
    }
    if (this.rsiOverboughtSeries) {
      this.rsiOverboughtSeries.applyOptions({ visible })
    }
    if (this.rsiOversoldSeries) {
      this.rsiOversoldSeries.applyOptions({ visible })
    }
    
  }

  /**
   * Get current indicator values for external use
   */
  getCurrentIndicators() {
    // This could be implemented to return the latest ADX and RSI values
    return { adx: null, rsi: null }
  }

  destroy() {
    
    // Remove ADX, RSI, inverse RSI, and Trend Strength series
    if (this.adxSeries) {
      this.chart.removeSeries(this.adxSeries)
      this.adxSeries = null
    }
    
    if (this.rsiSeries) {
      this.chart.removeSeries(this.rsiSeries)
      this.rsiSeries = null
    }
    
    if (this.iRsiSeries) {
      this.chart.removeSeries(this.iRsiSeries)
      this.iRsiSeries = null
    }
    
    if (this.trendStrengthSeries) {
      this.chart.removeSeries(this.trendStrengthSeries)
      this.trendStrengthSeries = null
    }
    
    // Remove threshold series
    if (this.strongTrendSeries) {
      this.chart.removeSeries(this.strongTrendSeries)
      this.strongTrendSeries = null
    }
    
    if (this.veryStrongTrendSeries) {
      this.chart.removeSeries(this.veryStrongTrendSeries)
      this.veryStrongTrendSeries = null
    }
    
    if (this.rsiOverboughtSeries) {
      this.chart.removeSeries(this.rsiOverboughtSeries)
      this.rsiOverboughtSeries = null
    }
    
    if (this.rsiOversoldSeries) {
      this.chart.removeSeries(this.rsiOversoldSeries)
      this.rsiOversoldSeries = null
    }
  }
}

export default ADXRenderer