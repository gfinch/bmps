/**
 * Results Service for tracking trading performance
 * Simplified design: listen → deduplicate → update UI
 */

import eventBufferManager from './eventBuffer.jsx'

/**
 * Trading Results Service
 * Listens to the results buffer which contains only order events
 */
class ResultsService {
  constructor() {
    // Order state - keyed by timestamp for deduplication
    this.orders = new Map()
    
    // Listeners for real-time updates
    this.listeners = new Set()
    
    // Set up event listening
    this.setupEventListener()
  }

  /**
   * Set up listening to the results buffer for order events
   * @private
   */
  setupEventListener() {
    const resultsBuffer = eventBufferManager.getBuffer('results')
    
    // Listen for new order events
    resultsBuffer.addListener(() => {
      this.processOrderUpdates()
    })
    
    // Process any existing order events
    this.processOrderUpdates()
  }

  /**
   * Process order updates from the results buffer
   * @private
   */
  processOrderUpdates() {
    const resultsBuffer = eventBufferManager.getBuffer('results')
    const events = resultsBuffer.getEvents()
    
    const deduplicatedOrders = this.deduplicateOrders(events)
    const hasChanges = this.updateOrdersMap(deduplicatedOrders)
    
    if (hasChanges) {
      this.notifyListeners()
    }
  }

  /**
   * Extract the order data from an event, handling both flat and nested structure
   * @param {Object} event - Raw event
   * @returns {Object|null} Order data or null
   */
  extractOrderFromEvent(event) {
    const actualEvent = event.event || event
    // New structure: Order fields are directly on the event
    if (actualEvent.entryPrice !== undefined) {
      return actualEvent
    }
    // Legacy structure: order nested in sub-field
    return actualEvent.order || null
  }

  /**
   * Deduplicate orders by timestamp, keeping the one with most timestamps
   * @param {Array} events - Order events from results buffer
   * @returns {Map} Map of timestamp -> order
   * @private
   */
  deduplicateOrders(events) {
    const ordersByTimestamp = new Map()
    
    events.forEach(event => {
      const actualEvent = event.event || event
      const order = this.extractOrderFromEvent(event)
      const mainTimestamp = actualEvent.timestamp
      
      if (!order) return
      
      const existing = ordersByTimestamp.get(mainTimestamp)
      if (!existing || this.hasMoreTimestamps(order, existing)) {
        ordersByTimestamp.set(mainTimestamp, { ...order, eventTimestamp: mainTimestamp })
      }
    })
    
    return ordersByTimestamp
  }

  /**
   * Check if new order has more timestamp fields than existing order
   * @param {Object} newOrder - New order to compare
   * @param {Object} existingOrder - Existing order to compare
   * @returns {boolean} True if new order has more timestamps
   * @private
   */
  hasMoreTimestamps(newOrder, existingOrder) {
    const countTimestamps = (order) => {
      return [
        order.placedTimestamp,
        order.filledTimestamp,
        order.closeTimestamp
      ].filter(ts => ts != null).length
    }
    
    return countTimestamps(newOrder) > countTimestamps(existingOrder)
  }

  /**
   * Update the orders map with deduplicated orders
   * @param {Map} deduplicatedOrders - New deduplicated orders
   * @returns {boolean} True if there were changes
   * @private
   */
  updateOrdersMap(deduplicatedOrders) {
    const oldSize = this.orders.size
    const oldKeys = new Set(this.orders.keys())
    const newKeys = new Set(deduplicatedOrders.keys())
    
    // Check if there are any changes
    if (oldSize !== deduplicatedOrders.size || 
        ![...oldKeys].every(key => newKeys.has(key))) {
      this.orders = deduplicatedOrders
      return true
    }
    
    // Check if any existing orders have been updated
    for (const [timestamp, newOrder] of deduplicatedOrders) {
      const existingOrder = this.orders.get(timestamp)
      if (!existingOrder || JSON.stringify(existingOrder) !== JSON.stringify(newOrder)) {
        this.orders = deduplicatedOrders
        return true
      }
    }
    
    return false
  }

  /**
   * Add a listener for results updates
   * @param {function} listener - Callback function
   */
  addListener(listener) {
    this.listeners.add(listener)
    
    // Immediately call the new listener with current results
    try {
      listener(this.getResults())
    } catch (error) {
      console.error('ResultsService: Error calling new listener:', error)
    }
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
    const results = this.getResults()
    this.listeners.forEach(listener => {
      try {
        listener(results)
      } catch (error) {
        console.error('ResultsService: Error in listener:', error)
      }
    })
  }

  /**
   * Calculate profit/loss for a completed order using Order case class logic
   * @param {Object} order - Order data
   * @returns {number} Profit/loss in dollars
   * @private
   */
  calcOrderPnL(order) {
    const status = this.getOrderStatus(order.status)
    if (status !== 'Profit' && status !== 'Loss') return 0
    
    const orderType = this.getOrderType(order.orderType)
    const entryPrice = order.entryPrice !== undefined ? order.entryPrice : order.entryPoint
    const contracts = order.contracts || 1
    
    // Determine exit price
    let exitPrice = order.exitPrice
    if (exitPrice === null || exitPrice === undefined) {
      exitPrice = status === 'Profit' ? order.takeProfit : order.stopLoss
    }
    
    // Calculate movement based on order type
    const movement = orderType === 'Long' 
      ? (exitPrice - entryPrice) 
      : (entryPrice - exitPrice)
    
    // Determine price per point based on contract type
    const contractType = this.getContractType(order)
    const pricePerPoint = contractType === 'ES' ? 50.0 : 5.0
    
    // Calculate fees
    const feePerContract = contractType === 'ES' ? (2.88 * 2) : (0.95 * 2)
    const fees = feePerContract * contracts
    
    // gain = movement * pricePerPoint * contracts - fees
    return (movement * pricePerPoint * contracts) - fees
  }

  /**
   * Extract contract type from order
   * @param {Object} order - Order data
   * @returns {string} 'ES' or 'MES'
   * @private
   */
  getContractType(order) {
    // New Order has contractType field
    if (typeof order.contractType === 'string') return order.contractType
    if (typeof order.contractType === 'object' && order.contractType) {
      if (order.contractType.ES !== undefined) return 'ES'
      if (order.contractType.MES !== undefined) return 'MES'
    }
    // Fallback: infer from contract name
    if (order.contract && order.contract.startsWith('M')) return 'MES'
    return 'MES' // Default to MES
  }

  /**
   * Get current results (calculated on-demand)
   * @returns {Object} Current trading results
   */
  getResults() {
    const orders = Array.from(this.orders.values())
    const completedOrders = orders.filter(order => {
      const status = this.getOrderStatus(order.status)
      return status === 'Profit' || status === 'Loss'
    })
    
    // Calculate metrics
    const totalTrades = completedOrders.length
    const winningTrades = completedOrders.filter(order => this.getOrderStatus(order.status) === 'Profit').length
    const losingTrades = totalTrades - winningTrades
    
    const totalWinAmount = completedOrders
      .filter(order => this.getOrderStatus(order.status) === 'Profit')
      .reduce((sum, order) => sum + Math.max(0, this.calcOrderPnL(order)), 0)
    
    const totalLossAmount = completedOrders
      .filter(order => this.getOrderStatus(order.status) === 'Loss')
      .reduce((sum, order) => sum + Math.abs(this.calcOrderPnL(order)), 0)
    
    const totalPnL = completedOrders.reduce((sum, order) => sum + this.calcOrderPnL(order), 0)
    
    // Create P&L history
    const pnlHistory = []
    let cumulativePnL = 0
    
    completedOrders
      .sort((a, b) => a.eventTimestamp - b.eventTimestamp)
      .forEach(order => {
        const pnl = this.calcOrderPnL(order)
        cumulativePnL += pnl
        
        pnlHistory.push({
          timestamp: order.eventTimestamp,
          pnl,
          cumulativePnL
        })
      })
    
    return {
      // Basic stats
      totalPnL: Math.round(totalPnL),
      totalTrades,
      winningTrades,
      losingTrades,
      
      // Calculated metrics
      winRate: totalTrades > 0 ? Math.round((winningTrades / totalTrades) * 100) : 0,
      avgWin: winningTrades > 0 ? Math.round(totalWinAmount / winningTrades) : 0,
      avgLoss: losingTrades > 0 ? Math.round(totalLossAmount / losingTrades) : 0,
      
      // Additional metrics
      totalReturn: 0, // TODO: Calculate based on account size
      maxDrawdown: this.calculateMaxDrawdown(pnlHistory),
      sharpeRatio: 0, // TODO: Calculate if needed
      
      // Historical data
      pnlHistory,
      
      // Status
      tradingDayComplete: false,
      
      // Raw orders for detailed analysis
      orders
    }
  }

  /**
   * Calculate maximum drawdown from P&L history
   * @param {Array} pnlHistory - P&L history array
   * @returns {number} Max drawdown amount
   * @private
   */
  calculateMaxDrawdown(pnlHistory) {
    if (pnlHistory.length === 0) return 0
    
    let maxDrawdown = 0
    let peak = 0
    
    pnlHistory.forEach(entry => {
      if (entry.cumulativePnL > peak) {
        peak = entry.cumulativePnL
      }
      const drawdown = peak - entry.cumulativePnL
      if (drawdown > maxDrawdown) {
        maxDrawdown = drawdown
      }
    })
    
    return Math.round(maxDrawdown)
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
        const orderType = this.getOrderType(order.orderType)
        const entryPrice = order.entryPrice !== undefined ? order.entryPrice : order.entryPoint
        
        return {
          id: index + 1,
          time: this.formatTimestamp(order.eventTimestamp),
          type: orderType,
          price: entryPrice,
          quantity: order.contracts || 1,
          pnl: this.calcOrderPnL(order),
          status: 'closed'
        }
      })
  }

  /**
   * Extract status string from order status
   * New Order uses string status; legacy used object like {Profit: {}}
   * @param {Object|string} status - Order status
   * @returns {string}
   * @private
   */
  getOrderStatus(status) {
    if (typeof status === 'string') return status
    if (typeof status === 'object' && status) {
      const statusKeys = Object.keys(status)
      if (statusKeys.length > 0) {
        return statusKeys[0]
      }
    }
    return 'Unknown'
  }

  /**
   * Extract order type string
   * New Order uses string orderType; legacy used object like {Long: {}}
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
   * Format timestamp for display
   * @param {number} timestamp - Timestamp in milliseconds
   * @returns {string} Formatted date and time
   * @private
   */
  formatTimestamp(timestamp) {
    const date = new Date(timestamp)
    
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
    this.orders.clear()
    eventBufferManager.clearResults()
    this.notifyListeners()
  }
}

// Create singleton instance
const resultsService = new ResultsService()

export { ResultsService }
export default resultsService