import { BaseRenderer } from '../services/chartRenderingService.jsx'
import TrendRenderer from './TrendRenderer.jsx'
import KeltnerRenderer from './KeltnerRenderer.jsx'

/**
 * Technical Analysis Renderer - Coordinates multiple technical analysis renderers
 * Handles TechnicalAnalysis events and delegates to appropriate sub-renderers
 */
class TechnicalAnalysisRenderer extends BaseRenderer {
  constructor(chart, options = {}, chartService) {
    super()
    this.chart = chart
    this.chartService = chartService
    this.options = {
      // Trend renderer options
      trend: {
        emaColor: '#2563EB',        // Blue for EMA
        smaColor: '#F59E0B',        // Orange for SMA  
        shortTermColor: '#10B981',   // Green for short-term MA
        longTermColor: '#EF4444',    // Red for long-term MA
        lineWidth: 2,
        ...options.trend
      },
      // Keltner renderer options  
      keltner: {
        upperBandColor: '#FF0000',                      // Bright red upper band
        lowerBandColor: '#FF0000',                      // Bright red lower band
        centerLineColor: '#FF0000',                     // Bright red center line
        lineWidth: 8,                                   // Very thick lines
        showCenterLine: true,
        ...options.keltner
      },
      ...options
    }
    
    // Initialize sub-renderers
    this.trendRenderer = new TrendRenderer(chart, this.options.trend, chartService)
    this.keltnerRenderer = new KeltnerRenderer(chart, this.options.keltner)
    
    // Track visibility states
    this.trendVisible = true
    this.keltnerVisible = true
    
  }

  initialize() {
    this.trendRenderer.initialize()
    this.keltnerRenderer.initialize()
  }

  /**
   * Process TechnicalAnalysis events and delegate to appropriate sub-renderers
   */
  update(events, currentTimestamp, newYorkOffset = 0) {
    if (!Array.isArray(events) || events.length === 0) {
      return
    }

    
    // Since we're registered for TechnicalAnalysis, all events should already be relevant
    // Just pass them directly to sub-renderers with all parameters
    
    // Delegate to trend renderer if visible
    if (this.trendVisible) {
      this.trendRenderer.update(events, currentTimestamp, newYorkOffset)
    }
    
    // Delegate to Keltner renderer if visible
    if (this.keltnerVisible) {
      this.keltnerRenderer.update(events, currentTimestamp, newYorkOffset)
    }
  }

  /**
   * Set visibility for specific sub-renderer or all
   */
  setVisibility(visible, subRenderer = null) {
    
    if (subRenderer === 'trend' || !subRenderer) {
      this.trendVisible = visible
      this.trendRenderer.setVisibility(visible)
    }
    
    if (subRenderer === 'keltner' || !subRenderer) {
      this.keltnerVisible = visible
      this.keltnerRenderer.setVisibility(visible)
    }
  }

  /**
   * Get visibility state for sub-renderers
   */
  getVisibility() {
    return {
      trend: this.trendVisible,
      keltner: this.keltnerVisible,
      overall: this.trendVisible || this.keltnerVisible
    }
  }

  /**
   * Clear all rendered elements from sub-renderers
   */
  clear() {
    this.trendRenderer.clear()
    this.keltnerRenderer.clear()
  }

  /**
   * Destroy all sub-renderers
   */
  destroy() {
    this.trendRenderer.destroy()
    this.keltnerRenderer.destroy()
  }
}

export { TechnicalAnalysisRenderer }