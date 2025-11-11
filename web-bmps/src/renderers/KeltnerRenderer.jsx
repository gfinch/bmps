/**
 * KeltnerRenderer - Renders Keltner Channels from TechnicalAnalysis events
 * Displays upperBand and lowerBand as a lightly shaded region on the main chart
 */
import { LineSeries } from 'lightweight-charts'
import { BaseRenderer } from '../services/chartRenderingService.jsx'
import { extractEventType } from '../utils/eventTypeUtils.js'

class KeltnerRenderer extends BaseRenderer {
  constructor(chart, options = {}) {
    super(chart)
    
    console.log('KeltnerRenderer: Constructor called with options:', options)
    
    this.options = {
      // Keltner channel colors - make them very visible for testing
      upperBandColor: '#FF0000',                       // Red for upper band
      lowerBandColor: '#00FF00',                       // Green for lower band
      centerLineColor: '#0000FF',                      // Blue for center line
      
      // Line styles
      lineWidth: 3,                                    // Very thick lines
      showCenterLine: true,                            // Show center line
      
      ...options
    }
    
    console.log('KeltnerRenderer: Final options after merge:', this.options)
    
    // Chart series
    this.upperBandSeries = null
    this.lowerBandSeries = null
    this.centerLineSeries = null
  }

  initialize() {
    if (this.upperBandSeries) return // Already initialized
    
    console.debug('KeltnerRenderer: Starting initialization...')
    console.log('KeltnerRenderer: Using options for series creation:', this.options)
    
    // Create upper band series
    this.upperBandSeries = this.chart.addSeries(LineSeries, {
      color: this.options.upperBandColor,
      lineWidth: this.options.lineWidth,
      lineStyle: 0, // Solid line
      title: 'Keltner Upper',
      lastValueVisible: false,
      priceLineVisible: false,
    })
    
    console.log('KeltnerRenderer: Created upper band series with color:', this.options.upperBandColor, 'width:', this.options.lineWidth)
    
    // Create lower band series  
    this.lowerBandSeries = this.chart.addSeries(LineSeries, {
      color: this.options.lowerBandColor,
      lineWidth: this.options.lineWidth,
      lineStyle: 0, // Solid line
      title: 'Keltner Lower',
      lastValueVisible: false,
      priceLineVisible: false,
    })
    
    console.log('KeltnerRenderer: Created lower band series with color:', this.options.lowerBandColor, 'width:', this.options.lineWidth)
    
    // Create center line series if enabled
    if (this.options.showCenterLine) {
      this.centerLineSeries = this.chart.addSeries(LineSeries, {
        color: this.options.centerLineColor,
        lineWidth: this.options.lineWidth,
        lineStyle: 1, // Dashed line
        title: 'Keltner Center',
        lastValueVisible: false,
        priceLineVisible: false,
      })
    }
    
    console.debug('KeltnerRenderer: Initialized successfully', {
      hasUpperBand: !!this.upperBandSeries,
      hasLowerBand: !!this.lowerBandSeries,
      hasCenterLine: !!this.centerLineSeries
    })
  }

  update(allEvents, currentTimestamp, newYorkOffset = 0) {
    console.debug('KeltnerRenderer: Update called', {
      initialized: !!this.upperBandSeries,
      totalEvents: allEvents.length,
      currentTimestamp,
      newYorkOffset
    })
    
    if (!this.upperBandSeries) {
      console.warn('KeltnerRenderer: Not initialized, calling initialize()')
      this.initialize()
      if (!this.upperBandSeries) {
        console.error('KeltnerRenderer: Failed to initialize')
        return
      }
    }
    
    // Filter for TechnicalAnalysis events
    const technicalAnalysisEvents = allEvents.filter(eventWrapper => {
      const actualEvent = eventWrapper.event || eventWrapper
      const eventType = extractEventType(actualEvent)
      return eventType === 'TechnicalAnalysis'
    })
    
    console.debug('KeltnerRenderer: Filtered events', {
      technicalCount: technicalAnalysisEvents.length
    })
    
    if (technicalAnalysisEvents.length === 0) return
    
    // Process technical analysis events
    const keltnerData = this.processKeltnerEvents(technicalAnalysisEvents, newYorkOffset)
    
    console.debug('KeltnerRenderer: Processed Keltner data', {
      upperBandCount: keltnerData.upperBand.length,
      lowerBandCount: keltnerData.lowerBand.length,
      centerLineCount: keltnerData.centerLine.length,
      sampleData: {
        upperBand: keltnerData.upperBand.slice(0, 3),
        lowerBand: keltnerData.lowerBand.slice(0, 3)
      }
    })
    
    // Update Keltner channel series
    if (keltnerData.upperBand.length > 0) {
      this.upperBandSeries.setData(keltnerData.upperBand)
      console.debug('KeltnerRenderer: Set upper band data', keltnerData.upperBand.length, 'points')
    }
    
    if (keltnerData.lowerBand.length > 0) {
      this.lowerBandSeries.setData(keltnerData.lowerBand)
      console.debug('KeltnerRenderer: Set lower band data', keltnerData.lowerBand.length, 'points')
    }
    
    if (this.centerLineSeries && keltnerData.centerLine.length > 0) {
      this.centerLineSeries.setData(keltnerData.centerLine)
      console.debug('KeltnerRenderer: Set center line data', keltnerData.centerLine.length, 'points')
    }
    
    console.debug(`KeltnerRenderer: Updated with ${technicalAnalysisEvents.length} technical analysis events`)
  }

  /**
   * Process TechnicalAnalysis events to extract Keltner channel data
   */
  processKeltnerEvents(events, newYorkOffset = 0) {
    const upperBandData = []
    const lowerBandData = []
    const centerLineData = []
    
    console.debug('KeltnerRenderer: Processing events for Keltner channels', {
      eventCount: events.length
    })
    
    events.forEach((eventWrapper, index) => {
      const actualEvent = eventWrapper.event || eventWrapper
      // Handle both correct and typo versions
      const technicalAnalysis = actualEvent.technicalAnalysis || actualEvent.techncialAnalysis
      
      console.debug(`KeltnerRenderer: Event ${index}`, {
        hasTechnicalAnalysis: !!technicalAnalysis,
        hasVolatilityAnalysis: !!(technicalAnalysis?.volatilityAnalysis),
        volatilityKeys: technicalAnalysis?.volatilityAnalysis ? Object.keys(technicalAnalysis.volatilityAnalysis) : []
      })
      
      if (!technicalAnalysis || !technicalAnalysis.volatilityAnalysis) {
        console.debug(`KeltnerRenderer: Event ${index} missing volatility analysis`)
        return
      }
      
      const timestamp = actualEvent.timestamp
      const time = Math.floor((timestamp + newYorkOffset) / 1000)
      const volatility = technicalAnalysis.volatilityAnalysis
      
      console.debug(`KeltnerRenderer: Volatility analysis for event ${index}`, {
        hasKeltnerChannels: !!volatility.keltnerChannels,
        volatilityStructure: volatility
      })
      
      // Extract Keltner channel data
      if (volatility.keltnerChannels) {
        const keltner = volatility.keltnerChannels
        
        console.debug(`KeltnerRenderer: Keltner channels found`, {
          upperBand: keltner.upperBand,
          lowerBand: keltner.lowerBand,
          centerLine: keltner.centerLine,
          channelWidth: keltner.channelWidth
        })
        
        if (keltner.upperBand != null) {
          const upperValue = Number(keltner.upperBand)
          upperBandData.push({ time, value: upperValue })
          console.debug(`KeltnerRenderer: Added upper band point`, { time, value: upperValue })
        }
        
        if (keltner.lowerBand != null) {
          const lowerValue = Number(keltner.lowerBand)
          lowerBandData.push({ time, value: lowerValue })
          console.debug(`KeltnerRenderer: Added lower band point`, { time, value: lowerValue })
        }
        
        if (keltner.centerLine != null && this.options.showCenterLine) {
          const centerValue = Number(keltner.centerLine)
          centerLineData.push({ time, value: centerValue })
          console.debug(`KeltnerRenderer: Added center line point`, { time, value: centerValue })
        }
      } else {
        console.debug(`KeltnerRenderer: Event ${index} missing Keltner channels data`, volatility)
      }
    })
    
    // Sort by time to ensure proper ordering
    upperBandData.sort((a, b) => a.time - b.time)
    lowerBandData.sort((a, b) => a.time - b.time)
    centerLineData.sort((a, b) => a.time - b.time)
    
    console.debug('KeltnerRenderer: Processed Keltner data results', {
      upperBandPoints: upperBandData.length,
      lowerBandPoints: lowerBandData.length,
      centerLinePoints: centerLineData.length,
      timeRange: upperBandData.length > 0 ? {
        start: new Date(upperBandData[0].time * 1000),
        end: new Date(upperBandData[upperBandData.length - 1].time * 1000)
      } : null
    })
    
    return { 
      upperBand: upperBandData, 
      lowerBand: lowerBandData, 
      centerLine: centerLineData 
    }
  }

  /**
   * Set visibility of the Keltner renderer
   */
  setVisibility(visible) {
    console.debug(`KeltnerRenderer: setVisibility called with ${visible}`)
    
    if (!this.upperBandSeries) {
      console.debug('KeltnerRenderer: No series found, cannot set visibility')
      return
    }
    
    // Toggle Keltner channel visibility
    this.upperBandSeries.applyOptions({ visible })
    this.lowerBandSeries.applyOptions({ visible })
    
    // Toggle center line visibility if it exists
    if (this.centerLineSeries) {
      this.centerLineSeries.applyOptions({ visible })
    }
    
    console.debug(`KeltnerRenderer: Visibility set to ${visible} for all Keltner channels`)
  }

  /**
   * Get current Keltner channel values for external use
   */
  getCurrentKeltnerChannels() {
    // This could be implemented to return the latest channel values
    return { upperBand: null, lowerBand: null, centerLine: null }
  }

  destroy() {
    console.debug('KeltnerRenderer: Destroying renderer')
    
    // Remove Keltner channel series
    if (this.upperBandSeries) {
      this.chart.removeSeries(this.upperBandSeries)
      this.upperBandSeries = null
    }
    
    if (this.lowerBandSeries) {
      this.chart.removeSeries(this.lowerBandSeries)
      this.lowerBandSeries = null
    }
    
    if (this.centerLineSeries) {
      this.chart.removeSeries(this.centerLineSeries)
      this.centerLineSeries = null
    }
  }
}

export default KeltnerRenderer