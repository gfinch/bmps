/**
 * Chart Rendering Service for BMPS application
 * Manages TradingView chart visualization with extensible renderer pattern
 */

/**
 * Base renderer interface for chart elements
 */
class BaseRenderer {
  constructor(chart) {
    this.chart = chart
    this.visible = true // Default to visible
  }

  /**
   * Initialize the renderer (create series, etc.)
   * @abstract
   */
  initialize() {
    throw new Error('initialize() must be implemented by subclass')
  }

  /**
   * Update the renderer with new event data
   * @abstract
   * @param {Array} events - Events of this renderer's type
   * @param {number|null} currentTimestamp - Current playback timestamp
   * @param {number} newYorkOffset - New York offset in milliseconds
   */
  update(events, currentTimestamp, newYorkOffset) {
    throw new Error('update() must be implemented by subclass')
  }

  /**
   * Set the visibility of this renderer
   * @param {boolean} visible - Whether the renderer should be visible
   */
  setVisibility(visible) {
    this.visible = visible
    // Trigger an update with current events to apply visibility change
    if (this.lastEvents) {
      this.update(this.lastEvents, this.lastTimestamp)
    }
  }

  /**
   * Get the current visibility state
   * @returns {boolean} Current visibility state
   */
  isVisible() {
    return this.visible
  }

  /**
   * Clean up resources
   * @abstract
   */
  destroy() {
    throw new Error('destroy() must be implemented by subclass')
  }
}

/**
 * Main chart rendering service
 * Manages multiple renderers for different event types
 */
class ChartRenderingService {
  constructor(chart, phase) {
    this.chart = chart
    this.phase = phase
    this.newYorkOffset = null
    this.renderers = new Map() // Map of eventType -> renderer
    this.currentVisibleEvents = []
  this.debugCount = 0 // Initialize debug counter
  }

  /**
   * Set the New York offset
   * @param {number|null} offset - New York offset in milliseconds
   */
  setNewYorkOffset(offset) {
    this.newYorkOffset = offset
  }

  /**
   * Get the New York offset
   * @returns {number|null} New York offset in milliseconds, or null if not available
   */
  getNewYorkOffset() {
    return this.newYorkOffset
  }

  /**
   * Add a renderer for a specific event type
   * @param {string} eventType - The event type this renderer handles
   * @param {BaseRenderer} renderer - The renderer instance
   */
  addRenderer(eventType, renderer) {
    if (this.renderers.has(eventType)) {
      console.warn(`Renderer for event type '${eventType}' already exists, replacing`)
      this.renderers.get(eventType).destroy()
    }

    this.renderers.set(eventType, renderer)
    renderer.initialize()
    
  }

  /**
   * Remove a renderer for a specific event type
   * @param {string} eventType - The event type to remove
   */
  removeRenderer(eventType) {
    const renderer = this.renderers.get(eventType)
    if (renderer) {
      renderer.destroy()
      this.renderers.delete(eventType)
    }
  }

  /**
   * Set the visibility of a specific renderer
   * @param {string} eventType - The event type renderer to control
   * @param {boolean} visible - Whether the renderer should be visible
   */
  setRendererVisibility(eventType, visible) {
    const renderer = this.renderers.get(eventType)
    if (renderer) {
      renderer.setVisibility(visible)
    } else {
      console.warn(`ChartRenderingService: No renderer found for event type '${eventType}'`)
    }
  }

  /**
   * Get the visibility state of a specific renderer
   * @param {string} eventType - The event type renderer to check
   * @returns {boolean|null} Visibility state, or null if renderer not found
   */
  getRendererVisibility(eventType) {
    const renderer = this.renderers.get(eventType)
    return renderer ? renderer.isVisible() : null
  }

  /**
   * Update chart with new visible events
   * @param {Array} visibleEvents - Events visible at current playback position
   * @param {number} currentTimestamp - Current playback timestamp (optional)
   */
  updateVisibleEvents(visibleEvents, currentTimestamp = null) {
    this.currentVisibleEvents = visibleEvents
    this.currentTimestamp = currentTimestamp
    
    // Group events by type
    const eventsByType = this.groupEventsByType(visibleEvents)
    
    // Get the New York offset to pass to renderers
    const newYorkOffset = this.getNewYorkOffset() || 0
    
    // Update each renderer with its relevant events
    this.renderers.forEach((renderer, eventType) => {
      if (eventType === 'Candle') {
        // CandlestickRenderer gets ALL events to handle both candles and swing points
        renderer.update(visibleEvents, currentTimestamp, newYorkOffset)
      } else {
        // Other renderers get only their specific event type
        const events = eventsByType.get(eventType) || []
        renderer.update(events, currentTimestamp, newYorkOffset)
      }
    })
  }

  /**
   * Group events by their event type
   * @param {Array} events - Array of events
   * @returns {Map} Map of eventType -> events array
   * @private
   */
  groupEventsByType(events) {
    const grouped = new Map()
    const eventTypeCounts = new Map()
    
    events.forEach(event => {
      let eventType = this.extractEventType(event)
      if (eventType) {
        // Special handling for Order events: split into Order vs TrailingOrder
        if (eventType === 'Order') {
          const actualEvent = event.event || event
          // New structure: Order fields may be flat on the event (trailStop is now a number/null instead of boolean)
          // Legacy structure: order nested in sub-field (isTrailing)
          const order = actualEvent.entryPrice !== undefined ? actualEvent : actualEvent.order
          if (order && (order.trailStop != null || order.isTrailing === true)) {
            eventType = 'TrailingOrder'
          }
        }
        
        if (!grouped.has(eventType)) {
          grouped.set(eventType, [])
        }
        grouped.get(eventType).push(event)
        
        // Count event types for debugging
        eventTypeCounts.set(eventType, (eventTypeCounts.get(eventType) || 0) + 1)
      }
    })
    
    
    return grouped
  }

  /**
   * Extract event type from an event object
   * @param {Object} event - Event object
   * @returns {string|null} Event type or null if not found
   * @private
   */
  extractEventType(event) {
    if (!event) return null


    // Handle WebSocket message wrapper structure (event.event contains the actual Event)
    const actualEvent = event.event || event

    // Handle direct string eventType
    if (typeof actualEvent.eventType === 'string') {
      return actualEvent.eventType
    }

    // Handle object eventType (like {Candle: {...}})
    if (typeof actualEvent.eventType === 'object' && actualEvent.eventType !== null) {
      const keys = Object.keys(actualEvent.eventType)
      if (keys.length > 0) {
        return keys[0] // Return first key
      }
    }

    // Check if event has candle property directly (alternative structure)
    if (actualEvent.candle) {
      return 'Candle'
    }

    // Check if event has Order fields directly (new flat Order structure)
    if (actualEvent.entryPrice !== undefined && actualEvent.orderType !== undefined) {
      return 'Order'
    }

    // Check if event has technicalAnalysis property
    if (actualEvent.technicalAnalysis) {
      return 'TechnicalAnalysis'
    }

    return null
  }

  /**
   * Get all registered event types
   * @returns {Array<string>} Array of registered event types
   */
  getRegisteredEventTypes() {
    return Array.from(this.renderers.keys())
  }

  /**
   * Get current visible events
   * @returns {Array} Current visible events
   */
  getCurrentVisibleEvents() {
    return [...this.currentVisibleEvents]
  }

  /**
   * Clean up all renderers and resources
   */
  destroy() {
    
    this.renderers.forEach((renderer, eventType) => {
      renderer.destroy()
    })
    
    this.renderers.clear()
    this.currentVisibleEvents = []
  }
}

export { ChartRenderingService, BaseRenderer }
export default ChartRenderingService