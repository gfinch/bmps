/**
 * OrderRenderer - Renders order events with status-based visualizations using Series Primitives
 * Orders show different combinations of lines and boxes based on their lifecycle status
 */
import { BaseRenderer } from '../services/chartRenderingService.jsx'
import { OrderPrimitive } from '../primitives/OrderPrimitive.jsx'

class OrderRenderer extends BaseRenderer {
  constructor(chart, options = {}) {
    super(chart)
    this.options = {
      plannedColor: '#7C3AED',    // Purple for planned state
      placedColor: '#2563EB',     // Blue for placed state
      filledColor: '#2563EB',     // Blue for filled state  
      profitBoxFill: 'rgba(34, 197, 94, 0.15)',   // Green with transparency
      profitBoxStroke: '#22C55E',  // Green stroke
      lossBoxFill: 'rgba(239, 68, 68, 0.15)',     // Red with transparency  
      lossBoxStroke: '#EF4444',    // Red stroke
      greyBoxFill: 'rgba(107, 114, 128, 0.12)',   // Grey with transparency
      greyBoxStroke: '#6B7280',    // Grey stroke
      cancelledColor: '#6B7280',   // Grey for cancelled
      lineWidth: 2,
      labelColor: '#374151',
      labelSize: 12,
      labelOffset: 15,
      ...options
    }
    this.primitive = null
    this.orders = []
  }

  initialize() {
    // Create a Series Primitive that draws on the candlestick series
    this.primitive = new OrderPrimitive(this.options)
    
    // Attach to the candlestick series (assumes it exists)
    if (this.chart.candlestickSeries) {
      this.chart.candlestickSeries.attachPrimitive(this.primitive)
      console.debug('OrderRenderer initialized and attached to candlestick series')
    } else {
      console.warn('OrderRenderer: No candlestick series found to attach primitive')
    }
  }

  update(events, currentTimestamp = null) {
    if (!this.primitive) {
      console.debug('OrderRenderer: Primitive not initialized, skipping update')
      return
    }

    // Store events and timestamp for potential visibility changes
    this.lastEvents = events
    this.lastTimestamp = currentTimestamp

    // If not visible, pass empty orders to hide all orders
    if (!this.visible) {
      this.primitive.updateOrders([])
      console.debug('OrderRenderer: Hidden, updating with empty orders')
      return
    }

    console.debug(`OrderRenderer: Updating with ${events.length} events`)

    // Filter events by validity and current playback timestamp
    const validEvents = events.filter(event => this.isValidEvent(event))
    const timeFilteredEvents = currentTimestamp 
      ? validEvents.filter(event => this.isOrderVisibleAtTime(event, currentTimestamp))
      : validEvents
    
    console.debug(`OrderRenderer: After time filtering: ${timeFilteredEvents.length} events (currentTime: ${currentTimestamp})`)
    
    // Deduplicate the time-filtered events
    const deduplicatedEvents = this.deduplicateByTimestamp(timeFilteredEvents)
    
    console.debug(`OrderRenderer: After deduplication: ${deduplicatedEvents.length} events`)

    // Transform events into order data
    this.orders = deduplicatedEvents.map(event => this.transformEventToOrderData(event))

    console.debug(`OrderRenderer: Transformed to ${this.orders.length} orders:`, this.orders)

    // Update the primitive with new order data
    this.primitive.updateOrders(this.orders)
  }

  /**
   * Check if an order should be visible at the given timestamp
   * An order is visible if ALL its non-null timestamps are <= currentTimestamp
   * @param {Object} event - Order event
   * @param {number} currentTimestamp - Current playback timestamp
   * @returns {boolean} True if order should be visible
   */
  isOrderVisibleAtTime(event, currentTimestamp) {
    const actualEvent = event.event || event
    const order = actualEvent.order
    
    // List of all possible timestamps in an order
    const timestamps = [
      actualEvent.timestamp,
      order.placedTimestamp,
      order.filledTimestamp,
      order.closeTimestamp
    ].filter(ts => ts !== null && ts !== undefined)
    
    // All timestamps must be <= currentTimestamp
    const isVisible = timestamps.every(ts => ts <= currentTimestamp)
    
    if (!isVisible) {
      console.debug(`OrderRenderer: Filtering out order with future timestamp(s):`, {
        orderTimestamps: timestamps,
        currentTimestamp,
        futureTimestamps: timestamps.filter(ts => ts > currentTimestamp)
      })
    }
    
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
      const actualEvent = event.event || event
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
    const actualEvent = event.event || event
    
    // Check for Order event - either by eventType or by order property
    const hasOrderEventType = actualEvent.eventType === 'Order' || 
                             (typeof actualEvent.eventType === 'object' && 
                              actualEvent.eventType && 
                              Object.keys(actualEvent.eventType).includes('Order'))
    
    const hasOrderData = actualEvent.order !== null && actualEvent.order !== undefined
    
    if (!hasOrderEventType && !hasOrderData) {
      return false
    }

    // Validate required order properties - they are direct numeric values, not objects with .value
    const order = actualEvent.order
    const isValid = order &&
           typeof order.entryPoint === 'number' &&
           typeof order.takeProfit === 'number' &&
           typeof order.stopLoss === 'number' &&
           typeof actualEvent.timestamp === 'number' &&
           order.status

    if (!isValid) {
      console.debug('OrderRenderer: Invalid event filtered out:', actualEvent)
    }

    return isValid
  }

  transformEventToOrderData(event) {
    const actualEvent = event.event || event
    const order = actualEvent.order
    
    // Extract orderType from nested object structure like {Long: {}} or {Short: {}}
    let orderType = 'Long' // default
    if (order.orderType) {
      if (order.orderType.Long !== undefined) {
        orderType = 'Long'
      } else if (order.orderType.Short !== undefined) {
        orderType = 'Short'
      }
    }
    
    // Extract status from nested object structure like {Loss: {}}, {Profit: {}}, etc.
    let status = 'Planned' // default
    if (order.status) {
      const statusKeys = Object.keys(order.status)
      if (statusKeys.length > 0) {
        status = statusKeys[0] // Take the first key (Loss, Profit, Planned, etc.)
      }
    }

    // Extract entryType from nested object structure like {EngulfingOrderBlock: {}}, {FairValueGapOrderBlock: {}}, etc.
    let entryType = 'EngulfingOrderBlock' // default
    if (order.entryType) {
      const entryTypeKeys = Object.keys(order.entryType)
      if (entryTypeKeys.length > 0) {
        entryType = entryTypeKeys[0] // Take the first key (EngulfingOrderBlock, FairValueGapOrderBlock, etc.)
      }
    }
    
    const orderData = {
      id: `order-${actualEvent.timestamp}`,
      entryPoint: order.entryPoint,      // Direct numeric value
      takeProfit: order.takeProfit,      // Direct numeric value
      stopLoss: order.stopLoss,          // Direct numeric value
      timestamp: actualEvent.timestamp,
      placedTimestamp: order.placedTimestamp || null,
      filledTimestamp: order.filledTimestamp || null,
      closeTimestamp: order.closeTimestamp || null,
      status: status,
      orderType: orderType,
      entryType: entryType,
      cancelReason: order.cancelReason || null
    }

    console.debug('Transformed event to order data:', orderData)
    return orderData
  }

  destroy() {
    console.debug('OrderRenderer: Destroying renderer')
    
    if (this.primitive && this.chart.candlestickSeries) {
      this.chart.candlestickSeries.detachPrimitive(this.primitive)
    }
    this.primitive = null
    this.orders = []
  }
}

export default OrderRenderer