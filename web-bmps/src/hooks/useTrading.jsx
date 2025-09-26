/**
 * Trading Hook for React components
 * Provides trading phase state and operations
 */

import { useState, useEffect, useCallback, useRef } from 'react'
import websocketService from '../services/websocketService.jsx'
import eventBufferManager from '../services/eventBuffer.jsx'
import { useWebSocket } from './useWebSocket.jsx'

/**
 * Trading Phase Hook
 * @returns {Object} Trading state and methods
 */
export function useTrading() {
  const { isConnected, status: wsStatus } = useWebSocket()
  
  // Trading state
  const [events, setEvents] = useState([])
  const [isInitializing, setIsInitializing] = useState(false)
  const [isInitialized, setIsInitialized] = useState(false)
  const [error, setError] = useState(null)
  const [currentConfig, setCurrentConfig] = useState(null)
  
  // Use refs to track mounted state
  const isMounted = useRef(true)
  
  // Get trading buffer and set up event listener
  useEffect(() => {
    isMounted.current = true
    
    const tradingBuffer = eventBufferManager.getBuffer('trading')
    
    // Update events from buffer
    const updateEvents = () => {
      if (!isMounted.current) return
      
      const currentEvents = tradingBuffer.getEvents()
      console.debug(`useTrading: Updated with ${currentEvents.length} events`)
      setEvents(currentEvents)
    }
    
    // Initial load
    updateEvents()
    
    // Listen for buffer changes
    tradingBuffer.addListener(updateEvents)
    
    // Cleanup
    return () => {
      isMounted.current = false
      tradingBuffer.removeListener(updateEvents)
      console.debug('useTrading: Component unmounted')
    }
  }, [])

  /**
   * Initialize trading phase with configuration
   * @param {Object} config - Trading configuration (if any)
   */
  const startTrading = useCallback(async (config = {}) => {
    console.debug('useTrading: Starting trading with config:', config)
    
    if (!isConnected) {
      const errorMsg = 'Cannot start trading: WebSocket not connected'
      setError(errorMsg)
      throw new Error(errorMsg)
    }
    
    setError(null)
    setIsInitializing(true)
    setCurrentConfig(config)
    
    try {
      // Clear existing trading events
      eventBufferManager.clearPhase('trading')
      
      // Send start trading command
      const startCommand = {
        command: 'startPhase',
        phase: 'trading',
        options: config
      }
      
      console.debug('useTrading: Sending start command:', startCommand)
      websocketService.send(startCommand)
      
      // Small delay to ensure start command is processed
      await new Promise(resolve => setTimeout(resolve, 100))
      
      // Subscribe to trading phase events
      const subscribeCommand = {
        command: 'subscribePhase',
        phase: 'trading'
      }
      
      console.debug('useTrading: Sending subscribe command:', subscribeCommand)
      websocketService.send(subscribeCommand)
      
      setIsInitialized(true)
      console.debug('useTrading: Trading started successfully')
      
    } catch (err) {
      console.error('useTrading: Failed to start trading:', err)
      setError(err.message || 'Failed to start trading phase')
      setIsInitialized(false)
      throw err
    } finally {
      setIsInitializing(false)
    }
  }, [isConnected])

  /**
   * Reset trading phase
   */
  const resetTrading = useCallback(() => {
    console.debug('useTrading: Resetting trading')
    setError(null)
    setIsInitialized(false)
    setIsInitializing(false)
    setCurrentConfig(null)
    eventBufferManager.clearPhase('trading')
  }, [])

  /**
   * Clear trading events
   */
  const clearEvents = useCallback(() => {
    console.debug('useTrading: Clearing events')
    eventBufferManager.clearPhase('trading')
  }, [])

  /**
   * Send a trading command
   * @param {Object} command - Trading command to send
   */
  const sendTradingCommand = useCallback(async (command) => {
    if (!isConnected) {
      const errorMsg = 'Cannot send command: WebSocket not connected'
      setError(errorMsg)
      throw new Error(errorMsg)
    }
    
    try {
      console.debug('useTrading: Sending trading command:', command)
      websocketService.send(command)
    } catch (err) {
      console.error('useTrading: Failed to send command:', err)
      setError(err.message || 'Failed to send trading command')
      throw err
    }
  }, [isConnected])

  // Computed values
  const eventCount = events.length
  const latestEvent = events.length > 0 ? events[events.length - 1] : null
  const hasEvents = eventCount > 0
  const canStart = isConnected && !isInitializing
  const hasError = error !== null

  // Event filtering helpers
  const getEventsByType = useCallback((eventType) => {
    return events.filter(event => event.eventType === eventType)
  }, [events])

  const getEventsByTimeRange = useCallback((startTime, endTime) => {
    return events.filter(event => 
      event.timestamp >= startTime && event.timestamp <= endTime
    )
  }, [events])

  // Trading-specific event types (common ones)
  const getTradeEvents = useCallback(() => {
    return events.filter(event => 
      event.eventType && event.eventType.toLowerCase().includes('trade')
    )
  }, [events])

  const getOrderEvents = useCallback(() => {
    return events.filter(event => 
      event.eventType && event.eventType.toLowerCase().includes('order')
    )
  }, [events])

  const getPositionEvents = useCallback(() => {
    return events.filter(event => 
      event.eventType && event.eventType.toLowerCase().includes('position')
    )
  }, [events])

  // Statistics
  const stats = {
    total: eventCount,
    byType: events.reduce((acc, event) => {
      acc[event.eventType] = (acc[event.eventType] || 0) + 1
      return acc
    }, {}),
    timeRange: events.length > 0 ? {
      oldest: events[0]?.timestamp,
      newest: events[events.length - 1]?.timestamp
    } : null,
    trading: {
      trades: getTradeEvents().length,
      orders: getOrderEvents().length,
      positions: getPositionEvents().length
    }
  }

  return {
    // Event data
    events,
    eventCount,
    latestEvent,
    hasEvents,
    
    // State
    isInitializing,
    isInitialized,
    currentConfig,
    
    // Connection state
    isConnected,
    wsStatus,
    canStart,
    
    // Error state
    error,
    hasError,
    
    // Methods
    startTrading,
    resetTrading,
    clearEvents,
    sendTradingCommand,
    
    // Event filtering helpers
    getEventsByType,
    getEventsByTimeRange,
    getTradeEvents,
    getOrderEvents,
    getPositionEvents,
    
    // Statistics
    stats,
    
    // Status information
    getStatusMessage: () => {
      if (hasError) return error
      if (isInitializing) return 'Initializing trading phase...'
      if (isInitialized) return `Trading active (${eventCount} events)`
      if (!isConnected) return 'Waiting for connection...'
      return 'Ready to start trading'
    }
  }
}

export default useTrading