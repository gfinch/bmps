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
    
    console.debug('ADXRenderer: Starting initialization...')
    
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
    
    console.debug('ADXRenderer: Initialized successfully', {
      hasADX: !!this.adxSeries,
      hasRSI: !!this.rsiSeries,
      hasIRSI: !!this.iRsiSeries,
      hasTrendStrength: !!this.trendStrengthSeries,
      hasThresholds: this.options.showThresholds
    })
  }

  update(allEvents, currentTimestamp, newYorkOffset = 0) {
    if (!this.adxSeries) return
    
    console.debug('ADXRenderer: Update called', {
      totalEvents: allEvents.length,
      currentTimestamp,
      newYorkOffset
    })
    
    // Filter for TechnicalAnalysis events
    const technicalAnalysisEvents = allEvents.filter(eventWrapper => {
      const actualEvent = eventWrapper.event || eventWrapper
      const eventType = extractEventType(actualEvent)
      return eventType === 'TechnicalAnalysis'
    })
    
    console.debug('ADXRenderer: Filtered events', {
      technicalCount: technicalAnalysisEvents.length
    })
    
    if (technicalAnalysisEvents.length === 0) return
    
    // Process technical analysis events
    const processedData = this.processIndicatorEvents(technicalAnalysisEvents, newYorkOffset)
    
    console.debug('ADXRenderer: Processed indicator data', {
      adxCount: processedData.adx.length,
      rsiCount: processedData.rsi.length,
      iRsiCount: processedData.iRsi.length,
      trendStrengthCount: processedData.trendStrength.length,
      sampleADX: processedData.adx.slice(0, 3),
      sampleRSI: processedData.rsi.slice(0, 3),
      sampleIRSI: processedData.iRsi.slice(0, 3),
      sampleTrendStrength: processedData.trendStrength.slice(0, 3)
    })
    
    // Update ADX series
    if (processedData.adx.length > 0) {
      this.adxSeries.setData(processedData.adx)
      console.debug('ADXRenderer: Set ADX data', processedData.adx.length, 'points')
    }
    
    // Update RSI series
    if (processedData.rsi.length > 0) {
      this.rsiSeries.setData(processedData.rsi)
      console.debug('ADXRenderer: Set RSI data', processedData.rsi.length, 'points')
    }
    
    // Update inverse RSI series
    if (processedData.iRsi.length > 0) {
      this.iRsiSeries.setData(processedData.iRsi)
      console.debug('ADXRenderer: Set iRSI data', processedData.iRsi.length, 'points')
    }
    
    // Update Trend Strength series
    if (processedData.trendStrength.length > 0) {
      this.trendStrengthSeries.setData(processedData.trendStrength)
      console.debug('ADXRenderer: Set Trend Strength data', processedData.trendStrength.length, 'points')
    }
    
    // Update threshold lines with flat data across the time range
    const allData = [...processedData.adx, ...processedData.rsi, ...processedData.iRsi, ...processedData.trendStrength]
    if (allData.length > 0) {
      this.updateThresholdLines(allData)
    }
    
    console.debug(`ADXRenderer: Updated with ${technicalAnalysisEvents.length} technical analysis events`)
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
    
    console.debug('ADXRenderer: Processing events for ADX and RSI', {
      eventCount: events.length
    })
    
    events.forEach((eventWrapper, index) => {
      const actualEvent = eventWrapper.event || eventWrapper
      // Handle both correct and typo versions
      const technicalAnalysis = actualEvent.technicalAnalysis || actualEvent.techncialAnalysis
      
      if (!technicalAnalysis) {
        console.debug(`ADXRenderer: Event ${index} missing technical analysis`)
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
        
        console.debug(`ADXRenderer: ADX data point`, {
          time: new Date(timestamp),
          adx: adxValue
        })
      }
      
      // Extract RSI from momentum analysis
      if (technicalAnalysis.momentumAnalysis && technicalAnalysis.momentumAnalysis.rsi != null) {
        const rsiValue = Number(technicalAnalysis.momentumAnalysis.rsi)
        rsiData.push({ time, value: rsiValue })
        
        // Calculate inverse RSI (100 - RSI)
        const iRsiValue = 100 - rsiValue
        iRsiData.push({ time, value: iRsiValue })
        
        console.debug(`ADXRenderer: RSI data point`, {
          time: new Date(timestamp),
          rsi: rsiValue,
          iRsi: iRsiValue
        })
      } else {
        console.debug(`ADXRenderer: Event ${index} missing momentum analysis or RSI data`, {
          hasMomentumAnalysis: !!technicalAnalysis.momentumAnalysis,
          momentumKeys: technicalAnalysis.momentumAnalysis ? Object.keys(technicalAnalysis.momentumAnalysis) : []
        })
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
    
    console.debug('ADXRenderer: Processed indicator data results', {
      adxPoints: adxData.length,
      rsiPoints: rsiData.length,
      iRsiPoints: iRsiData.length,
      trendStrengthPoints: trendStrengthData.length,
      timeRange: adxData.length > 0 ? {
        start: new Date(adxData[0].time * 1000),
        end: new Date(adxData[adxData.length - 1].time * 1000)
      } : null
    })
    
    return { adx: adxData, rsi: rsiData, iRsi: iRsiData, trendStrength: trendStrengthData }
  }

  /**
   * Calculate trend strength using rolling 5-point window
   * For each point, uses current + 4 previous trend analyses
   */
  calculateTrendStrengthAfterCrosses(allEventData) {
    const trendStrengthData = []
    
    if (allEventData.length < 5) {
      console.debug('ADXRenderer: Not enough data for trend strength calculation')
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
        
        if (i % 50 === 0) { // Log every 50th calculation to avoid spam
          console.debug('ADXRenderer: Calculated trend strength', {
            time: new Date(currentEvent.timestamp),
            strength: strength * 100,
            windowSize: fivePoints.length
          })
        }
      }
    }
    
    console.debug('ADXRenderer: Trend strength calculation complete', {
      totalPoints: trendStrengthData.length,
      sampleValues: trendStrengthData.slice(0, 5)
    })
    
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
      console.debug('ADXRenderer: No Keltner channel data available')
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
    if (Math.random() < 0.01) { // Log ~1% of calculations
      console.debug('ADXRenderer: Strength calculation details', {
        time: new Date(lastPoint.timestamp),
        shortTermMA: trend.shortTermMA,
        longTermMA: trend.longTermMA,
        maSpread: maSpread,
        upperBand: keltner.upperBand,
        lowerBand: keltner.lowerBand,
        channelWidth: channelWidth,
        rawStrength: rawStrength,
        clampedStrength: clampedStrength,
        finalValue: clampedStrength * 100
      })
    }
    
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
      console.debug('ADXRenderer: Skipping threshold update - only one data point')
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
    
    console.debug('ADXRenderer: Updated threshold lines', {
      strongTrend: this.options.strongTrendLine,
      veryStrongTrend: this.options.veryStrongTrendLine,
      rsiOverbought: this.options.rsiOverboughtLine,
      rsiOversold: this.options.rsiOversoldLine,
      timeRange: { start: startTime, end: endTime }
    })
  }

  /**
   * Set visibility of the ADX renderer
   */
  setVisibility(visible) {
    console.debug(`ADXRenderer: setVisibility called with ${visible}`)
    
    if (!this.adxSeries) {
      console.debug('ADXRenderer: No series found, cannot set visibility')
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
    
    console.debug(`ADXRenderer: Visibility set to ${visible} for all indicators and thresholds`)
  }

  /**
   * Get current indicator values for external use
   */
  getCurrentIndicators() {
    // This could be implemented to return the latest ADX and RSI values
    return { adx: null, rsi: null }
  }

  destroy() {
    console.debug('ADXRenderer: Destroying renderer')
    
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