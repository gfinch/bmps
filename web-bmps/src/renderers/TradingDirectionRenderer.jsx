/**
 * TradingDirectionRenderer - Renders trading direction change markers as large diagonal arrows at bottom of chart
 */
import { LineSeries } from 'lightweight-charts'
import { BaseRenderer } from '../services/chartRenderingService.jsx'
import { extractEventType } from '../utils/eventTypeUtils.js'

class TradingDirectionRenderer extends BaseRenderer {
  constructor(chart, options = {}) {
    super(chart)
    this.options = {
      showTradingDirection: true, // Default to showing trading direction
      colors: {
        up: '#26a69a',   // Green for up direction  
        down: '#ef5350'  // Red for down direction
      },
      size: 4, // Large size for trading direction markers
      ...options
    }
    this.series = null
    this.currentTradingDirections = []
    this.overlayElement = null
  }

  initialize() {
    // Create invisible line series for positioning
    this.series = this.chart.addSeries(LineSeries, {
      color: 'transparent',
      lineWidth: 0,
      priceLineVisible: false,
      lastValueVisible: false,
      crosshairMarkerVisible: false,
      autoscaleInfoProvider: () => null, // Don't affect chart scaling
    })

    // Create overlay element for custom arrows
    this.createOverlay()

    // Listen to chart updates to reposition arrows
    this.setupChartListeners()
  }

  setupChartListeners() {
    // Listen to time scale changes (pan/zoom)
    this.chart.timeScale().subscribeVisibleTimeRangeChange(() => {
      // Use requestAnimationFrame for smooth repositioning
      requestAnimationFrame(() => {
        this.repositionArrows()
      })
    })

    // Listen to chart resize
    if (window.ResizeObserver) {
      const chartElement = this.chart.chartElement()
      if (chartElement) {
        this.resizeObserver = new ResizeObserver(() => {
          requestAnimationFrame(() => {
            this.repositionArrows()
          })
        })
        this.resizeObserver.observe(chartElement)
      }
    }
  }

  createOverlay() {
    const chartContainer = this.chart.chartElement()
    if (!chartContainer) return

    this.overlayElement = document.createElement('div')
    this.overlayElement.style.position = 'absolute'
    this.overlayElement.style.top = '0'
    this.overlayElement.style.left = '0'
    this.overlayElement.style.width = '100%'
    this.overlayElement.style.height = '100%'
    this.overlayElement.style.pointerEvents = 'none'
    this.overlayElement.style.zIndex = '100'
    
    chartContainer.style.position = 'relative'
    chartContainer.appendChild(this.overlayElement)
  }

  /**
   * Configure trading direction display options
   * @param {Object} options - Options object
   * @param {boolean} options.showTradingDirection - Whether to show trading direction markers
   * @param {Object} options.colors - Color configuration for trading direction markers
   * @param {number} options.size - Size of the markers
   */
  setTradingDirectionOptions(options) {
    this.options = { ...this.options, ...options }
    // Re-render with new options if we have data
    if (this.currentTradingDirections.length > 0) {
      this.updateTradingDirectionMarkers(this.currentTradingDirections)
    }
  }

  update(allEvents) {
    if (!this.series || !this.overlayElement) {
      // Try to initialize if we haven't already
      this.initialize()
      if (!this.series || !this.overlayElement) return
    }

    // Filter for trading direction events
    const tradingDirectionEvents = []
    
    allEvents.forEach(eventWrapper => {
      const actualEvent = eventWrapper.event || eventWrapper
      const eventType = extractEventType(actualEvent)
      
      if (eventType === 'TradingDirection') {
        tradingDirectionEvents.push(eventWrapper)
      }
    })

    this.updateTradingDirectionMarkers(tradingDirectionEvents)
  }

  /**
   * Update trading direction markers on the chart
   * @param {Array} tradingDirectionEvents - Array of trading direction events
   */
  updateTradingDirectionMarkers(tradingDirectionEvents) {
    if (!this.overlayElement || !this.options.showTradingDirection || !this.visible) {
      if (this.overlayElement) {
        this.overlayElement.innerHTML = ''
      }
      return
    }

    this.currentTradingDirections = tradingDirectionEvents
    this.renderCustomArrows(tradingDirectionEvents)
  }

  /**
   * Reposition existing arrows when chart pans/zooms
   */
  repositionArrows() {
    if (this.currentTradingDirections.length > 0) {
      this.renderCustomArrows(this.currentTradingDirections)
    }
  }

  /**
   * Render custom diagonal arrows at the bottom of the chart
   * @param {Array} tradingDirectionEvents - Trading direction events
   */
  renderCustomArrows(tradingDirectionEvents) {
    if (!this.overlayElement) return

    // Clear existing arrows
    this.overlayElement.innerHTML = ''

    tradingDirectionEvents.forEach(eventWrapper => {
      const actualEvent = eventWrapper.event || eventWrapper
      const tradingDirection = actualEvent.tradingDirection
      
      if (!tradingDirection) return
      
      const direction = this.extractDirection(tradingDirection)
      const timestamp = actualEvent.timestamp
      
      if (!direction || !timestamp) return
      
      const isUpDirection = direction.toLowerCase() === 'up'
      

      // Get chart dimensions and time scale
      const timeScale = this.chart.timeScale()
      
      // Convert timestamp to pixel position
      const timeInSeconds = Math.floor(timestamp / 1000)
      const pixelX = timeScale.timeToCoordinate(timeInSeconds)
      
      if (pixelX === null || pixelX < 0) return // Time not visible on chart
      
      // Create arrow element
      const arrow = document.createElement('div')
      arrow.style.position = 'absolute'
      arrow.style.left = `${pixelX - 12}px` // Center the arrow (24px wide / 2)
      arrow.style.bottom = '20px' // Position at bottom of chart
      arrow.style.width = '24px'
      arrow.style.height = '24px'
      arrow.style.fontSize = '24px'
      arrow.style.textAlign = 'center'
      arrow.style.lineHeight = '24px'
      arrow.style.fontWeight = 'bold'
      arrow.style.textShadow = '0 0 3px rgba(0,0,0,0.5)'
      
      // Use clear directional arrows with proper colors
      if (isUpDirection) {
        arrow.textContent = '↗' // Up-right diagonal arrow
        arrow.style.color = this.options.colors.up // Green
      } else {
        arrow.textContent = '↘' // Down-right diagonal arrow  
        arrow.style.color = this.options.colors.down // Red
      }
      arrow.title = `Trading Direction: ${direction}`
      
      this.overlayElement.appendChild(arrow)
    })
  }



  /**
   * Extract direction from TradingDirection object
   * @param {Object|string} direction - Direction from TradingDirection
   * @returns {string|null} Direction string or null
   */
  extractDirection(direction) {
    if (typeof direction === 'string') {
      return direction
    }
    
    if (typeof direction === 'object' && direction !== null) {
      const keys = Object.keys(direction)
      if (keys.length > 0) {
        return keys[0] // Return first key (Up, Down)
      }
    }
    
    return null
  }

  /**
   * Set visibility of trading direction markers
   * @param {boolean} visible - Whether markers should be visible
   */
  setVisibility(visible) {
    super.setVisibility(visible)
    // Update markers with current visibility
    this.updateTradingDirectionMarkers(this.currentTradingDirections)
  }

  destroy() {
    // Clean up resize observer
    if (this.resizeObserver) {
      this.resizeObserver.disconnect()
      this.resizeObserver = null
    }

    // Clean up overlay element
    if (this.overlayElement && this.overlayElement.parentNode) {
      this.overlayElement.parentNode.removeChild(this.overlayElement)
      this.overlayElement = null
    }

    // Clean up series
    if (this.series) {
      this.chart.removeSeries(this.series)
      this.series = null
    }

    this.currentTradingDirections = []
  }
}

export default TradingDirectionRenderer