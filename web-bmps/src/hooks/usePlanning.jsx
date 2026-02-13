/**
 * Planning Hook for React components
 * Provides planning phase state and operations
 */

import { useState, useEffect, useCallback, useRef } from 'react'
import phaseService from '../services/phaseService.jsx'
import eventBufferManager from '../services/eventBuffer.jsx'
import restApiService from '../services/restApiService.jsx'

/**
 * Planning Phase Hook
 * @returns {Object} Planning state and methods
 */
export function usePlanning() {
  // Planning state
  const [events, setEvents] = useState([])
  const [isInitializing, setIsInitializing] = useState(false)
  const [isInitialized, setIsInitialized] = useState(false)
  const [isPolling, setIsPolling] = useState(false)
  const [error, setError] = useState(null)
  const [currentConfig, setCurrentConfig] = useState(null)
  
  // Use refs to track mounted state
  const isMounted = useRef(true)
  
  // Get planning buffer and set up event listener
  useEffect(() => {
    isMounted.current = true
    
    const planningBuffer = eventBufferManager.getBuffer('planning')
    
    // Update events from buffer
    const updateEvents = () => {
      if (!isMounted.current) return
      
      const currentEvents = planningBuffer.getEvents()
      setEvents(currentEvents)
    }
    
    // Initial load
    updateEvents()
    
    // Listen for buffer changes
    planningBuffer.addListener(updateEvents)
    
    // Update planning status from service
    const updatePlanningStatus = () => {
      if (!isMounted.current) return
      
      const status = phaseService.getStatus()
      setIsInitializing(status.isInitializing)
      setIsInitialized(status.isInitialized)
      setIsPolling(status.isPolling)
      setCurrentConfig(status.currentConfig)
    }
    
    // Initial status update
    updatePlanningStatus()
    
    // Set up periodic status updates
    const statusInterval = setInterval(updatePlanningStatus, 500)
    
    // Cleanup
    return () => {
      isMounted.current = false
      planningBuffer.removeListener(updateEvents)
      clearInterval(statusInterval)
    }
  }, [])

  /**
   * Initialize planning phase with configuration
   * @param {Object} config - Planning configuration
   * @param {string} config.tradingDate - Trading date (system uses 2 trading days automatically)
   */
  const startPlanning = useCallback(async (config) => {
    
    if (!config) {
      setError('Configuration is required')
      return
    }
    
    setError(null)
    
    try {
      await phaseService.initializePlanning(config)
    } catch (err) {
      console.error('usePlanning: Failed to start planning:', err)
      setError(err.message || 'Failed to start planning phase')
      throw err // Re-throw so component can handle it
    }
  }, [])

  /**
   * Reset planning phase
   */
  const resetPlanning = useCallback(() => {
    setError(null)
    phaseService.resetPlanning()
  }, [])

  /**
   * Clear planning events
   */
  const clearEvents = useCallback(() => {
    eventBufferManager.clearPhase('planning')
  }, [])

  // Computed values
  const eventCount = events.length
  const latestEvent = events.length > 0 ? events[events.length - 1] : null
  const hasEvents = eventCount > 0
  const canStart = !isInitializing && !isPolling
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
    } : null
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
    isPolling,
    currentConfig,
    canStart,
    
    // Error state
    error,
    hasError,
    
    // Methods
    startPlanning,
    resetPlanning,
    clearEvents,
    
    // Helpers
    getEventsByType,
    getEventsByTimeRange,
    
    // Statistics
    stats,
    
    // Status information
    getStatusMessage: () => {
      if (hasError) return error
      if (isInitializing) return 'Initializing planning phase...'
      if (isPolling) return `Polling for events (${eventCount} events)`
      if (isInitialized) return `Planning complete (${eventCount} events)`
      return 'Ready to start planning'
    }
  }
}

export default usePlanning