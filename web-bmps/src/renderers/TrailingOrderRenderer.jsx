/**
 * TrailingOrderRenderer - Renders trailing order events as triangles using Series Primitives
 * Trailing orders show entry line extending to close, with diagonal to exit point
 */
import { BaseRenderer } from '../services/chartRenderingService.jsx'
import { TrailingOrderPrimitive } from '../primitives/TrailingOrderPrimitive.jsx'

class TrailingOrderRenderer extends BaseRenderer {
  constructor(chart, options = {}) {
    super(chart)
    this.options = {
      openColor: '#2563EB',           // Blue for open trailing orders
      profitFill: 'rgba(34, 197, 94, 0.15)',   // Green with transparency
      lossFill: 'rgba(239, 68, 68, 0.15)',     // Red with transparency
      strokeColor: '#000000',          // Black stroke for triangles
      lineWidth: 2,
      ...options
    }
    this.primitive = null
    this.orders = []
  }

  initialize() {
    // Create a Series Primitive that draws on the candlestick series
    this.primitive = new TrailingOrderPrimitive(this.options)
    
    // Attach to the candlestick series (assumes it exists)
    if (this.chart.candlestickSeries) {
      this.chart.candlestickSeries.attachPrimitive(this.primitive)
    } else {
      console.warn('TrailingOrderRenderer: No candlestick series found to attach primitive')
    }
  }

  update(events, currentTimestamp = null, newYorkOffset = 0) {
    if (!this.primitive) {
      return
    }

    // Store events and timestamp for potential visibility changes
    this.lastEvents = events
    this.lastTimestamp = currentTimestamp

    // If not visible, pass empty orders to hide all orders
    if (!this.visible) {
      this.primitive.updateOrders([])
      return
    }


    // Filter events by validity and current playback timestamp
    const validEvents = events.filter(event => this.isValidEvent(event))
    const timeFilteredEvents = currentTimestamp 
      ? validEvents.filter(event => this.isOrderVisibleAtTime(event, currentTimestamp))
      : validEvents
    
    
    // Deduplicate the time-filtered events
    const deduplicatedEvents = this.deduplicateByTimestamp(timeFilteredEvents)
    

    // Transform events into order data, passing the offset
    this.orders = deduplicatedEvents.map(event => this.transformEventToOrderData(event, newYorkOffset))


    // Update the primitive with new order data
    this.primitive.updateOrders(this.orders)
  }

  /**
   * Extract the order data from an event, handling both new flat structure and legacy nested structure
   * @param {Object} event - Raw event
   * @returns {Object} { actualEvent, order } where order contains the Order fields
   */
  extractOrder(event) {
    const actualEvent = event.event || event
    // New structure: Order fields are directly on the event
    if (actualEvent.entryPrice !== undefined) {
      return { actualEvent, order: actualEvent }
    }
    // Legacy structure: order nested in sub-field
    return { actualEvent, order: actualEvent.order }
  }

  /**
   * Check if an order should be visible at the given timestamp
   * @param {Object} event - Order event
   * @param {number} currentTimestamp - Current playback timestamp
   * @returns {boolean} True if order should be visible
   */
  isOrderVisibleAtTime(event, currentTimestamp) {
    const { actualEvent, order } = this.extractOrder(event)
    
    // List of all possible timestamps in an order
    const timestamps = [
      actualEvent.timestamp,
      order.placedTimestamp,
      order.filledTimestamp,
      order.closeTimestamp
    ].filter(ts => ts !== null && ts !== undefined)
    
    // All timestamps must be <= currentTimestamp
    const isVisible = timestamps.every(ts => ts <= currentTimestamp)
    
    return isVisible
  }

  /**
   * Deduplicate events by timestamp, keeping only the latest arriving event for each timestamp
   * @param {Array} events - Array of valid events
   * @returns {Array} Deduplicated events
   */
  deduplicateByTimestamp(events) {
    const eventMap = new Map()
    
    events.forEach((event, index) => {
      const { actualEvent } = this.extractOrder(event)
      const timestamp = actualEvent.timestamp
      
      // If we haven't seen this timestamp before, or this event comes later in the buffer, store it
      const existing = eventMap.get(timestamp)
      if (!existing || index > existing.index) {
        eventMap.set(timestamp, { 
          event: event, 
          index: index,
          timestamp: timestamp
        })
      }
    })
    
    // Return the events, sorted by timestamp for consistent ordering
    return Array.from(eventMap.values())
      .sort((a, b) => a.timestamp - b.timestamp)
      .map(item => item.event)
  }

  isValidEvent(event) {
    const { actualEvent, order } = this.extractOrder(event)
    
    // Check for Order event - either by eventType or by Order fields present
    const hasOrderEventType = actualEvent.eventType === 'Order' || 
                             (typeof actualEvent.eventType === 'object' && 
                              actualEvent.eventType && 
                              Object.keys(actualEvent.eventType).includes('Order'))
    
    const hasOrderData = order !== null && order !== undefined
    
    const hasOrderFields = actualEvent.entryPrice !== undefined && 
                           actualEvent.orderType !== undefined
    
    if (!hasOrderEventType && !hasOrderData && !hasOrderFields) {
      return false
    }

    // Validate required order properties
    // New Order uses entryPrice and trailStop (was isTrailing)
    const isValid = order &&
           typeof order.entryPrice === 'number' &&
           typeof actualEvent.timestamp === 'number' &&
           order.status &&
           order.trailStop === true  // Must be a trailing order (was isTrailing)

    if (!isValid) {
    }

    return isValid
  }

  transformEventToOrderData(event, newYorkOffset = 0) {
    const { actualEvent, order } = this.extractOrder(event)
    
    // Extract orderType - now a string, but handle legacy object format
    let orderType = 'Long' // default
    if (typeof order.orderType === 'string') {
      orderType = order.orderType
    } else if (order.orderType) {
      if (order.orderType.Long !== undefined) orderType = 'Long'
      else if (order.orderType.Short !== undefined) orderType = 'Short'
    }
    
    // Extract status - now a string, but handle legacy object format
    let status = 'Planned' // default
    if (typeof order.status === 'string') {
      status = order.status
    } else if (order.status) {
      const statusKeys = Object.keys(order.status)
      if (statusKeys.length > 0) status = statusKeys[0]
    }

    // Extract entry strategy description (was entryType, now entryStrategy)
    let entryType = order.entryStrategy || order.entryType || { description: 'Order' }

    const orderData = {
      id: `trailing-order-${actualEvent.timestamp}`,
      entryPoint: order.entryPrice,      // Was entryPoint, now entryPrice
      exitPoint: order.exitPrice !== undefined ? order.exitPrice : null,  // Was exitPoint, now exitPrice
      timestamp: actualEvent.timestamp + newYorkOffset,
      placedTimestamp: order.placedTimestamp ? order.placedTimestamp + newYorkOffset : null,
      filledTimestamp: order.filledTimestamp ? order.filledTimestamp + newYorkOffset : null,
      closeTimestamp: order.closeTimestamp ? order.closeTimestamp + newYorkOffset : null,
      status: status,
      orderType: orderType,
      entryType: entryType,
      isTrailing: true
    }

    return orderData
  }

  destroy() {
    
    if (this.primitive && this.chart.candlestickSeries) {
      this.chart.candlestickSeries.detachPrimitive(this.primitive)
    }
    this.primitive = null
    this.orders = []
  }
}

export default TrailingOrderRenderer
