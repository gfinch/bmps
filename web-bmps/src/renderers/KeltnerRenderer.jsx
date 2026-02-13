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
    
    
    // Chart series
    this.upperBandSeries = null
    this.lowerBandSeries = null
    this.centerLineSeries = null
  }

  initialize() {
    if (this.upperBandSeries) return // Already initialized
    
    
    // Create upper band series
    this.upperBandSeries = this.chart.addSeries(LineSeries, {
      color: this.options.upperBandColor,
      lineWidth: this.options.lineWidth,
      lineStyle: 0, // Solid line
      title: 'Keltner Upper',
      lastValueVisible: false,
      priceLineVisible: false,
    })
    
    
    // Create lower band series  
    this.lowerBandSeries = this.chart.addSeries(LineSeries, {
      color: this.options.lowerBandColor,
      lineWidth: this.options.lineWidth,
      lineStyle: 0, // Solid line
      title: 'Keltner Lower',
      lastValueVisible: false,
      priceLineVisible: false,
    })
    
    
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
  }

  update(allEvents, currentTimestamp, newYorkOffset = 0) {
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
    
    if (technicalAnalysisEvents.length === 0) return
    
    // Process technical analysis events
    const keltnerData = this.processKeltnerEvents(technicalAnalysisEvents, newYorkOffset)
    
    // Update Keltner channel series
    if (keltnerData.upperBand.length > 0) {
      this.upperBandSeries.setData(keltnerData.upperBand)
    }
    
    if (keltnerData.lowerBand.length > 0) {
      this.lowerBandSeries.setData(keltnerData.lowerBand)
    }
    
    if (this.centerLineSeries && keltnerData.centerLine.length > 0) {
      this.centerLineSeries.setData(keltnerData.centerLine)
    }
    
  }

  /**
   * Process TechnicalAnalysis events to extract Keltner channel data
   */
  processKeltnerEvents(events, newYorkOffset = 0) {
    const upperBandData = []
    const lowerBandData = []
    const centerLineData = []
    
    events.forEach((eventWrapper, index) => {
      const actualEvent = eventWrapper.event || eventWrapper
      // Handle both correct and typo versions
      const technicalAnalysis = actualEvent.technicalAnalysis || actualEvent.techncialAnalysis
      
      if (!technicalAnalysis || !technicalAnalysis.volatilityAnalysis) {
        return
      }
      
      const timestamp = actualEvent.timestamp
      const time = Math.floor((timestamp + newYorkOffset) / 1000)
      const volatility = technicalAnalysis.volatilityAnalysis
      
      // Extract Keltner channel data
      if (volatility.keltnerChannels) {
        const keltner = volatility.keltnerChannels
        
        if (keltner.upperBand != null) {
          const upperValue = Number(keltner.upperBand)
          upperBandData.push({ time, value: upperValue })
        }
        
        if (keltner.lowerBand != null) {
          const lowerValue = Number(keltner.lowerBand)
          lowerBandData.push({ time, value: lowerValue })
        }
        
        if (keltner.centerLine != null && this.options.showCenterLine) {
          const centerValue = Number(keltner.centerLine)
          centerLineData.push({ time, value: centerValue })
        }
      } else {
      }
    })
    
    // Sort by time to ensure proper ordering
    upperBandData.sort((a, b) => a.time - b.time)
    lowerBandData.sort((a, b) => a.time - b.time)
    centerLineData.sort((a, b) => a.time - b.time)
    
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
    
    if (!this.upperBandSeries) {
      return
    }
    
    // Toggle Keltner channel visibility
    this.upperBandSeries.applyOptions({ visible })
    this.lowerBandSeries.applyOptions({ visible })
    
    // Toggle center line visibility if it exists
    if (this.centerLineSeries) {
      this.centerLineSeries.applyOptions({ visible })
    }
    
  }

  /**
   * Get current Keltner channel values for external use
   */
  getCurrentKeltnerChannels() {
    // This could be implemented to return the latest channel values
    return { upperBand: null, lowerBand: null, centerLine: null }
  }

  destroy() {
    
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