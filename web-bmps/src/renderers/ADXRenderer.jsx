/**
 * ADXRenderer - Renders ADX (Average Directional Index) and RSI from TechnicalAnalysis events
 * Both ADX and RSI are displayed on the same chart since they use similar scales (0-100)
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
    this.strongTrendSeries = null
    this.veryStrongTrendSeries = null
    this.rsiOverboughtSeries = null
    this.rsiOversoldSeries = null
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
      sampleADX: processedData.adx.slice(0, 3),
      sampleRSI: processedData.rsi.slice(0, 3)
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
    
    // Update threshold lines with flat data across the time range
    const allData = [...processedData.adx, ...processedData.rsi]
    if (allData.length > 0) {
      this.updateThresholdLines(allData)
    }
    
    console.debug(`ADXRenderer: Updated with ${technicalAnalysisEvents.length} technical analysis events`)
  }

  /**
   * Process TechnicalAnalysis events to extract ADX and RSI data
   */
  processIndicatorEvents(events, newYorkOffset = 0) {
    const adxData = []
    const rsiData = []
    
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
        
        console.debug(`ADXRenderer: RSI data point`, {
          time: new Date(timestamp),
          rsi: rsiValue
        })
      } else {
        console.debug(`ADXRenderer: Event ${index} missing momentum analysis or RSI data`, {
          hasMomentumAnalysis: !!technicalAnalysis.momentumAnalysis,
          momentumKeys: technicalAnalysis.momentumAnalysis ? Object.keys(technicalAnalysis.momentumAnalysis) : []
        })
      }
    })
    
    // Sort by time to ensure proper ordering
    adxData.sort((a, b) => a.time - b.time)
    rsiData.sort((a, b) => a.time - b.time)
    
    console.debug('ADXRenderer: Processed indicator data results', {
      adxPoints: adxData.length,
      rsiPoints: rsiData.length,
      timeRange: adxData.length > 0 ? {
        start: new Date(adxData[0].time * 1000),
        end: new Date(adxData[adxData.length - 1].time * 1000)
      } : null
    })
    
    return { adx: adxData, rsi: rsiData }
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
    
    // Toggle ADX and RSI visibility
    this.adxSeries.applyOptions({ visible })
    this.rsiSeries.applyOptions({ visible })
    
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
    
    // Remove ADX and RSI series
    if (this.adxSeries) {
      this.chart.removeSeries(this.adxSeries)
      this.adxSeries = null
    }
    
    if (this.rsiSeries) {
      this.chart.removeSeries(this.rsiSeries)
      this.rsiSeries = null
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