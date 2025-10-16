/**
 * REST API Service for BMPS application
 * Handles phase control and event polling via REST endpoints
 */

const API_URL = 'https://bmps.misfortunesheir.com'
const BACKTEST_API_URL = 'https://bmps.misfortunesheir.com:444'
// const API_URL = 'http://localhost:8081'

/**
 * REST API Service for phase management
 */
class RestApiService {
  constructor() {
    this.activePolls = new Map() // Track active polling intervals per phase
  }

  /**
   * Get the appropriate API URL based on trading date
   * @param {string} tradingDate - Trading date in YYYY-MM-DD format
   * @returns {string} API URL to use
   */
  getApiUrl(tradingDate) {
    const today = new Date().toISOString().split('T')[0]
    return tradingDate === today ? API_URL : BACKTEST_API_URL
  }

  /**
   * Start a phase on the backend
   * @param {string} phase - Phase name ('planning', 'preparing', 'trading')
   * @param {string} tradingDate - Trading date in YYYY-MM-DD format
   * @returns {Promise<void>}
   */
  async startPhase(phase, tradingDate) {
    console.debug(`REST API: Starting phase ${phase}`, { tradingDate })
    
    const apiUrl = this.getApiUrl(tradingDate)
    const body = {
      phase,
      tradingDate,
      options: {}
    }

    try {
      const response = await fetch(`${apiUrl}/phase/start`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(body)
      })

      if (!response.ok) {
        const error = await response.json()
        throw new Error(error.error || `Failed to start ${phase} phase`)
      }

      const result = await response.json()
      console.debug(`REST API: Phase ${phase} start response:`, result)
      return result
    } catch (error) {
      console.error(`REST API: Failed to start phase ${phase}:`, error)
      throw error
    }
  }

  /**
   * Poll for phase events
   * @param {string} phase - Phase name ('planning', 'preparing', 'trading')
   * @param {string} tradingDate - Trading date in YYYY-MM-DD format
   * @returns {Promise<{events: Array, isComplete: boolean, newYorkOffset: number}>}
   */
  async pollPhaseEvents(phase, tradingDate) {
    console.debug(`REST API: Polling events for ${phase} on ${tradingDate}`)
    
    const apiUrl = this.getApiUrl(tradingDate)
    try {
      const response = await fetch(
        `${apiUrl}/phase/events?tradingDate=${tradingDate}&phase=${phase}`,
        {
          method: 'GET',
          headers: {
            'Accept': 'application/json',
          }
        }
      )

      if (!response.ok) {
        const error = await response.json()
        throw new Error(error.error || `Failed to fetch ${phase} events`)
      }

      const result = await response.json()
      console.debug(`REST API: Received ${result.events.length} events for ${phase}, complete: ${result.isComplete}, offset: ${result.newYorkOffset}`)
      
      return {
        events: result.events || [],
        isComplete: result.isComplete || false,
        newYorkOffset: result.newYorkOffset
      }
    } catch (error) {
      console.error(`REST API: Failed to poll phase ${phase}:`, error)
      throw error
    }
  }

  /**
   * Start polling for a phase (with automatic retry and completion detection)
   * @param {string} phase - Phase name
   * @param {string} tradingDate - Trading date
   * @param {Function} onEvents - Callback for new events: (events, isComplete, newYorkOffset) => void
   * @param {Function} [onComplete] - Callback when phase completes
   * @param {Function} [onError] - Callback for errors: (error) => void
   * @param {number} [interval] - Polling interval in ms (default: 1000)
   * @returns {Function} Stop function to cancel polling
   */
  startPolling(phase, tradingDate, onEvents, onComplete = null, onError = null, interval = 1000) {
    console.debug(`REST API: Starting polling for ${phase}`)
    
    // Stop any existing poll for this phase
    this.stopPolling(phase)
    
    let isRunning = true
    let timeoutId = null
    
    const poll = async () => {
      if (!isRunning) return
      
      try {
        const { events, isComplete, newYorkOffset } = await this.pollPhaseEvents(phase, tradingDate)
        
        if (!isRunning) return
        
        // Call the events callback with the offset
        if (onEvents) {
          onEvents(events, isComplete, newYorkOffset)
        }
        
        // If complete, call completion callback and stop polling
        if (isComplete) {
          console.debug(`REST API: Phase ${phase} completed`)
          if (onComplete) {
            onComplete()
          }
          this.stopPolling(phase)
          return
        }
        
        // Schedule next poll
        if (isRunning) {
          timeoutId = setTimeout(poll, interval)
        }
      } catch (error) {
        console.error(`REST API: Polling error for ${phase}:`, error)
        
        if (onError) {
          onError(error)
        }
        
        // Continue polling on error (server might be temporarily unavailable)
        if (isRunning) {
          timeoutId = setTimeout(poll, interval)
        }
      }
    }
    
    // Start first poll immediately
    poll()
    
    // Store the stop function
    const stopFn = () => {
      console.debug(`REST API: Stopping polling for ${phase}`)
      isRunning = false
      if (timeoutId) {
        clearTimeout(timeoutId)
        timeoutId = null
      }
    }
    
    this.activePolls.set(phase, stopFn)
    
    return stopFn
  }

  /**
   * Stop polling for a specific phase
   * @param {string} phase - Phase name
   */
  stopPolling(phase) {
    const stopFn = this.activePolls.get(phase)
    if (stopFn) {
      stopFn()
      this.activePolls.delete(phase)
    }
  }

  /**
   * Stop all active polling
   */
  stopAllPolling() {
    console.debug('REST API: Stopping all polling')
    for (const [phase, stopFn] of this.activePolls.entries()) {
      stopFn()
    }
    this.activePolls.clear()
  }

  /**
   * Check if a phase is currently being polled
   * @param {string} phase - Phase name
   * @returns {boolean}
   */
  isPolling(phase) {
    return this.activePolls.has(phase)
  }

  /**
   * Get order report for a trading day
   * @param {string} tradingDate - Trading date in YYYY-MM-DD format
   * @param {string} [accountId] - Optional account ID to filter by specific broker
   * @returns {Promise<Object>} OrderReport with orders, winning, losing, averageWinDollars, averageLossDollars, maxDrawdownDollars
   */
  async getOrderReport(tradingDate, accountId = null) {
    console.debug(`REST API: Getting order report for ${tradingDate}`, { accountId })
    
    const apiUrl = this.getApiUrl(tradingDate)
    try {
      const params = new URLSearchParams({ tradingDate })
      if (accountId) {
        params.append('accountId', accountId)
      }
      
      const response = await fetch(`${apiUrl}/orderReport?${params.toString()}`, {
        method: 'GET',
        headers: {
          'Accept': 'application/json',
        }
      })

      if (!response.ok) {
        const error = await response.json()
        throw new Error(error.error || 'Failed to fetch order report')
      }

      const report = await response.json()
      console.debug(`REST API: Received order report with ${report.orders.length} orders`, report)
      return report
    } catch (error) {
      console.error(`REST API: Failed to get order report:`, error)
      throw error
    }
  }

  /**
   * Get available trading dates from EventStore with profitability status
   * @param {string} tradingDate - Trading date to determine which API to query
   * @returns {Promise<Array<{date: string, profitable: boolean|null}>>} 
   *          Array of date objects with profitability info
   *          - profitable: true = profitable, false = unprofitable, null = neutral/no trades
   */
  async getAvailableDates(tradingDate) {
    console.debug('REST API: Getting available dates with profitability')
    
    const apiUrl = this.getApiUrl(tradingDate)
    try {
      const response = await fetch(`${apiUrl}/availableDates`, {
        method: 'GET',
        headers: {
          'Accept': 'application/json',
        }
      })

      if (!response.ok) {
        const error = await response.json()
        throw new Error(error.error || 'Failed to fetch available dates')
      }

      const result = await response.json()
      console.debug(`REST API: Received ${result.dates.length} available dates with profitability`, result.dates)
      return result.dates
    } catch (error) {
      console.error('REST API: Failed to get available dates:', error)
      throw error
    }
  }

  /**
   * Get aggregate order report across all trading days
   * @param {string} tradingDate - Trading date to determine which API to query
   * @param {string} [accountId] - Optional account ID to filter by specific broker
   * @returns {Promise<Object>} OrderReport with orders, winning, losing, averageWinDollars, averageLossDollars, maxDrawdownDollars, totalPnL
   */
  async getAggregateOrderReport(tradingDate, accountId = null) {
    console.debug('REST API: Getting aggregate order report', { accountId })
    
    const apiUrl = this.getApiUrl(tradingDate)
    try {
      const params = new URLSearchParams()
      if (accountId) {
        params.append('accountId', accountId)
      }
      
      const url = params.toString() 
        ? `${apiUrl}/aggregateOrderReport?${params.toString()}`
        : `${apiUrl}/aggregateOrderReport`
      
      const response = await fetch(url, {
        method: 'GET',
        headers: {
          'Accept': 'application/json',
        }
      })

      if (!response.ok) {
        const error = await response.json()
        throw new Error(error.error || 'Failed to fetch aggregate order report')
      }

      const report = await response.json()
      console.debug(`REST API: Received aggregate order report with ${report.orders.length} orders`, report)
      return report
    } catch (error) {
      console.error(`REST API: Failed to get aggregate order report:`, error)
      throw error
    }
  }

  /**
   * Check health of the REST API
   * @param {string} tradingDate - Trading date to determine which API to check
   * @returns {Promise<boolean>}
   */
  async checkHealth(tradingDate) {
    const apiUrl = this.getApiUrl(tradingDate)
    try {
      const response = await fetch(`${apiUrl}/health`)
      return response.ok
    } catch (error) {
      console.error('REST API: Health check failed:', error)
      return false
    }
  }

  /**
   * Check health of a specific API server
   * @param {boolean} isLive - true for live API, false for backtest API
   * @returns {Promise<boolean>}
   */
  async checkServerHealth(isLive) {
    const apiUrl = isLive ? API_URL : BACKTEST_API_URL
    try {
      const response = await fetch(`${apiUrl}/health`)
      return response.ok
    } catch (error) {
      console.error(`REST API: Health check failed for ${isLive ? 'live' : 'backtest'} server:`, error)
      return false
    }
  }
}

// Export singleton instance
const restApiService = new RestApiService()
export default restApiService
