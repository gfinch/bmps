/**
 * Results Service for tracking trading performance
 * Watches trading events, manages order state, and calculates P&L
 */

import eventBufferManager from './eventBuffer.jsx'

/**
 * Trading Results Service
 * Maintains in-memory state of orders and trading performance
 */
class ResultsService {
  constructor() {
    // Order state - keyed by timestamp for deduplication
    this.orders = new Map()
    
    // Performance tracking
    this.dailyResults = {
      totalPnL: 0,
      totalTrades: 0,
      winningTrades: 0,
      losingTrades: 0,
      totalWinAmount: 0,
      totalLossAmount: 0,
      pnlHistory: [], // [{timestamp, pnl, cumulativePnL}]
      tradingDayComplete: false
    }
    
    // Listeners for real-time updates
    this.listeners = new Set()
    
    // Set up event buffer watching
    this.setupEventWatching()
    
    console.debug('ResultsService: Initialized')
  }

  /**
   * Set up listening to trading event buffer
   * @private
   */
  setupEventWatching() {
    const tradingBuffer = eventBufferManager.getBuffer('trading')
    
    // Listen for new events
    const eventListener = () => {
      this.processNewEvents()
    }
    
    tradingBuffer.addListener(eventListener)
    
    // Initial processing of existing events
    this.processAllEvents()
  }

  /**
   * Process all existing events in the trading buffer
   * @private
   */
  processAllEvents() {
    const tradingBuffer = eventBufferManager.getBuffer('trading')
    const events = tradingBuffer.getEvents()
    
    console.debug(`ResultsService: Processing ${events.length} existing events`)
    
    // Reset state
    this.orders.clear()
    this.resetDailyResults()
    
    // Process each event
    events.forEach(event => this.processEvent(event))
    
    // Notify listeners
    this.notifyListeners()
  }

  /**
   * Process new events that have been added to buffer
   * @private
   */
  processNewEvents() {
    const tradingBuffer = eventBufferManager.getBuffer('trading')
    const events = tradingBuffer.getEvents()
    
    // If buffer is empty, it might have been cleared by a reset
    // In that case, don't reset our results - just return
    if (events.length === 0) {
      console.debug('ResultsService: Buffer is empty (possibly due to reset) - preserving results')
      return
    }
    
    // If we have fewer events than before, the buffer was likely cleared and repopulated
    // In this case, we should reprocess from scratch
    const currentOrderCount = this.orders.size
    
    // Check if this looks like a buffer reset by seeing if we have orders but no events
    if (currentOrderCount > 0 && events.length > 0) {
      // Check if any of our existing orders are missing from the new events
      const eventTimestamps = new Set(events.map(e => (e.event || e).timestamp))
      const hasOrphanedOrders = Array.from(this.orders.keys()).some(timestamp => !eventTimestamps.has(timestamp))
      
      if (hasOrphanedOrders) {
        console.debug('ResultsService: Detected buffer reset with new events - reprocessing from scratch')
        this.orders.clear()
        this.resetDailyResults()
      }
    }
    
    // Process any events we haven't seen yet
    events.forEach(event => this.processEvent(event))
    
    // Only notify if we actually processed new orders
    if (this.orders.size !== currentOrderCount) {
      console.debug(`ResultsService: Processed new events, now have ${this.orders.size} orders`)
      this.notifyListeners()
    }
  }

  /**
   * Process a single event
   * @param {Object} event - Event to process
   * @private
   */
  processEvent(event) {
    const actualEvent = event.event || event
    
    // Handle Reset events - do NOT clear results data, just ignore
    if (this.isResetEvent(actualEvent)) {
      console.debug('ResultsService: Reset event received - preserving results data')
      return
    }
    
    // Handle Order events
    if (this.isOrderEvent(actualEvent)) {
      this.processOrderEvent(actualEvent)
    }
    
    // Handle PhaseComplete events for trading phase
    if (this.isPhaseCompleteEvent(actualEvent)) {
      this.handleTradingDayComplete()
    }
  }

  /**
   * Check if event is an Order event
   * @param {Object} event - Event to check
   * @returns {boolean}
   * @private
   */
  isOrderEvent(event) {
    return (event.eventType === 'Order' || 
           (typeof event.eventType === 'object' && 
            event.eventType && 
            Object.keys(event.eventType).includes('Order'))) &&
           event.order !== null && event.order !== undefined
  }

  /**
   * Check if event is a PhaseComplete event
   * @param {Object} event - Event to check
   * @returns {boolean}
   * @private
   */
  isPhaseCompleteEvent(event) {
    return event.eventType === 'PhaseComplete' || 
           (typeof event.eventType === 'object' && 
            event.eventType && 
            Object.keys(event.eventType).includes('PhaseComplete'))
  }

  /**
   * Check if event is a Reset event
   * @param {Object} event - Event to check
   * @returns {boolean}
   * @private
   */
  isResetEvent(event) {
    return event.eventType === 'Reset' || 
           (typeof event.eventType === 'object' && 
            event.eventType && 
            Object.keys(event.eventType).includes('Reset'))
  }

  /**
   * Process an order event (deduplicate by timestamp)
   * @param {Object} event - Order event
   * @private
   */
  processOrderEvent(event) {
    const order = event.order
    const timestamp = event.timestamp
    
    // Validate required order data
    if (!this.isValidOrder(order)) {
      console.warn('ResultsService: Invalid order data:', order)
      return
    }
    
    // Store/update order (deduplication by timestamp)
    const existingOrder = this.orders.get(timestamp)
    this.orders.set(timestamp, { ...order, eventTimestamp: timestamp })
    
    // If this is an update to an existing order, recalculate from scratch
    if (existingOrder) {
      console.debug(`ResultsService: Updated order at timestamp ${timestamp}`)
      this.recalculateResults()
    } else {
      console.debug(`ResultsService: Added new order at timestamp ${timestamp}`)
      this.updateResultsForOrder(order)
    }
  }

  /**
   * Validate order has required fields
   * @param {Object} order - Order to validate
   * @returns {boolean}
   * @private
   */
  isValidOrder(order) {
    return order &&
           typeof order.entryPoint === 'number' &&
           typeof order.takeProfit === 'number' &&
           typeof order.stopLoss === 'number' &&
           order.status &&
           typeof order.potential === 'number' &&
           typeof order.atRisk === 'number'
  }

  /**
   * Extract status string from order status object
   * @param {Object|string} status - Order status
   * @returns {string}
   * @private
   */
  getOrderStatus(status) {
    if (typeof status === 'string') return status
    if (typeof status === 'object' && status) {
      const statusKeys = Object.keys(status)
      if (statusKeys.length > 0) {
        return statusKeys[0] // Take first key (Profit, Loss, Planned, etc.)
      }
    }
    return 'Unknown'
  }

  /**
   * Update results for a single order
   * @param {Object} order - Order to process
   * @private
   */
  updateResultsForOrder(order) {
    const status = this.getOrderStatus(order.status)
    
    if (status === 'Profit') {
      this.dailyResults.totalTrades++
      this.dailyResults.winningTrades++
      this.dailyResults.totalPnL += order.potential
      this.dailyResults.totalWinAmount += order.potential
      
      // Add to P&L history
      this.dailyResults.pnlHistory.push({
        timestamp: order.eventTimestamp,
        pnl: order.potential,
        cumulativePnL: this.dailyResults.totalPnL
      })
      
    } else if (status === 'Loss') {
      this.dailyResults.totalTrades++
      this.dailyResults.losingTrades++
      this.dailyResults.totalPnL -= order.atRisk
      this.dailyResults.totalLossAmount += order.atRisk
      
      // Add to P&L history  
      this.dailyResults.pnlHistory.push({
        timestamp: order.eventTimestamp,
        pnl: -order.atRisk,
        cumulativePnL: this.dailyResults.totalPnL
      })
    }
    
    // Other statuses (Planned, Placed, Filled, Cancelled) don't affect P&L
  }

  /**
   * Recalculate all results from scratch
   * @private
   */
  recalculateResults() {
    this.resetDailyResults()
    
    // Process all orders in chronological order
    const ordersArray = Array.from(this.orders.values())
      .sort((a, b) => a.eventTimestamp - b.eventTimestamp)
    
    ordersArray.forEach(order => this.updateResultsForOrder(order))
  }

  /**
   * Reset daily results to initial state
   * @private
   */
  resetDailyResults() {
    this.dailyResults = {
      totalPnL: 0,
      totalTrades: 0,
      winningTrades: 0,
      losingTrades: 0,
      totalWinAmount: 0,
      totalLossAmount: 0,
      pnlHistory: [],
      tradingDayComplete: false
    }
  }

  /**
   * Handle trading day completion
   * @private
   */
  handleTradingDayComplete() {
    console.debug('ResultsService: Trading day completed')
    this.dailyResults.tradingDayComplete = true
    this.notifyListeners()
  }

  /**
   * Add a listener for results updates
   * @param {function} listener - Callback function
   */
  addListener(listener) {
    this.listeners.add(listener)
  }

  /**
   * Remove a listener
   * @param {function} listener - Callback function to remove
   */
  removeListener(listener) {
    this.listeners.delete(listener)
  }

  /**
   * Notify all listeners of updates
   * @private
   */
  notifyListeners() {
    this.listeners.forEach(listener => {
      try {
        listener(this.getResults())
      } catch (error) {
        console.error('ResultsService: Error in listener:', error)
      }
    })
  }

  /**
   * Get current results
   * @returns {Object} Current trading results
   */
  getResults() {
    const { totalTrades, winningTrades, losingTrades, totalWinAmount, totalLossAmount } = this.dailyResults
    
    return {
      // Basic stats
      totalPnL: this.dailyResults.totalPnL,
      totalTrades,
      winningTrades,
      losingTrades,
      
      // Calculated metrics
      winRate: totalTrades > 0 ? Math.round((winningTrades / totalTrades) * 100) : 0,
      avgWin: winningTrades > 0 ? Math.round(totalWinAmount / winningTrades) : 0,
      avgLoss: losingTrades > 0 ? Math.round(totalLossAmount / losingTrades) : 0,
      
      // Additional metrics
      totalReturn: 0, // TODO: Calculate based on account size
      maxDrawdown: this.calculateMaxDrawdown(),
      sharpeRatio: 0, // TODO: Calculate if needed
      
      // Historical data
      pnlHistory: [...this.dailyResults.pnlHistory],
      
      // Status
      tradingDayComplete: this.dailyResults.tradingDayComplete,
      
      // Raw orders for detailed analysis
      orders: Array.from(this.orders.values())
    }
  }

  /**
   * Calculate maximum drawdown
   * @returns {number} Max drawdown amount
   * @private
   */
  calculateMaxDrawdown() {
    if (this.dailyResults.pnlHistory.length === 0) return 0
    
    let maxDrawdown = 0
    let peak = 0
    
    this.dailyResults.pnlHistory.forEach(entry => {
      if (entry.cumulativePnL > peak) {
        peak = entry.cumulativePnL
      }
      const drawdown = peak - entry.cumulativePnL
      if (drawdown > maxDrawdown) {
        maxDrawdown = drawdown
      }
    })
    
    return maxDrawdown
  }

  /**
   * Get orders for trade history display
   * @returns {Array} Formatted trade history
   */
  getTradeHistory() {
    return Array.from(this.orders.values())
      .filter(order => {
        const status = this.getOrderStatus(order.status)
        return status === 'Profit' || status === 'Loss'
      })
      .sort((a, b) => a.eventTimestamp - b.eventTimestamp)
      .map((order, index) => {
        const status = this.getOrderStatus(order.status)
        const orderType = this.getOrderType(order.orderType)
        
        return {
          id: index + 1,
          time: this.formatTimestamp(order.eventTimestamp),
          type: orderType,
          price: order.entryPoint,
          quantity: order.contracts || 1,
          pnl: status === 'Profit' ? order.potential : -order.atRisk,
          status: 'closed'
        }
      })
  }

  /**
   * Extract order type string
   * @param {Object|string} orderType - Order type
   * @returns {string}
   * @private
   */
  getOrderType(orderType) {
    if (typeof orderType === 'string') return orderType
    if (typeof orderType === 'object' && orderType) {
      if (orderType.Long !== undefined) return 'Long'
      if (orderType.Short !== undefined) return 'Short'
    }
    return 'Long'
  }

  /**
   * Format timestamp for display (timestamp is already in Eastern time but needs to be treated as UTC)
   * @param {number} timestamp - Timestamp in milliseconds (already Eastern time)
   * @returns {string} Formatted date and time
   * @private
   */
  formatTimestamp(timestamp) {
    const date = new Date(timestamp)
    
    // Format as "Aug 22 '25 09:55" treating the timestamp as UTC to display correctly
    // since the timestamp values are already shifted for Eastern timezone
    const options = {
      month: 'short',
      day: 'numeric',
      year: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
      timeZone: 'UTC'  // Treat as UTC to avoid local timezone conversion
    }
    
    return date.toLocaleDateString('en-US', options).replace(',', '')
  }

  /**
   * Clear all results (for testing/reset)
   */
  clearResults() {
    console.debug('ResultsService: Clearing all results')
    this.orders.clear()
    this.resetDailyResults()
    this.notifyListeners()
  }
}

// Create singleton instance
const resultsService = new ResultsService()

export { ResultsService }
export default resultsService