/**
 * Phase Service for BMPS application
 * Handles all phase operations and transitions: planning → preparing → trading
 * Uses REST API with polling instead of WebSocket
 */

import restApiService from './restApiService.jsx'
import eventBufferManager from './eventBuffer.jsx'
import eventPlaybackService from './eventPlaybackService.jsx'

/**
 * @typedef {Object} PlanningConfig
 * @property {string} tradingDate - Trading date in YYYY-MM-DD format
 */

/**
 * Phase Service class - handles all phase lifecycle with REST API polling
 */
class PhaseService {
  constructor() {
    this.isInitializing = false
    this.isInitialized = false
    this.currentConfig = null
    this.currentPhase = null
    this.isPolling = false
  }

  /**
   * Initialize planning phase
   * @param {PlanningConfig} config - Planning configuration
   */
  async initializePlanning(config) {
    console.debug('Initializing planning phase with config:', config)

    try {
      this.isInitializing = true

      // Validate configuration
      this.validateConfig(config)

      // Clear previous planning and trading buffers
      eventBufferManager.clearPhase('planning')
      eventBufferManager.clearPhase('trading')

      // Also clear playback
      eventPlaybackService.reset()

      // Store current config
      this.currentConfig = config

      // Start the planning phase via REST API
      await this.startPhase('planning', config.tradingDate)

      this.isInitialized = true
      console.debug('Planning initialization completed successfully')

    } catch (error) {
      console.error('Planning initialization failed:', error)
      this.isInitialized = false
      throw error
    } finally {
      this.isInitializing = false
    }
  }

  /**
   * Start a specific phase (planning, preparing, or trading)
   * @param {string} phase - Phase name ('planning', 'preparing', 'trading')
   * @param {string} tradingDate - Trading date
   * @private
   */
  async startPhase(phase, tradingDate) {
    console.debug(`Starting ${phase} phase`, { tradingDate })
    
    this.currentPhase = phase
    this.isPolling = true
    
    try {
      // Check if trading date is today
      const today = new Date().toISOString().split('T')[0] // YYYY-MM-DD format
      const isToday = tradingDate === today
      
      if (isToday) {
        console.debug(`Trading date is today - skipping startPhase API call, will only poll for existing events`)
      } else {
        // Call REST API to start phase (for historical dates)
        await restApiService.startPhase(phase, tradingDate)
      }
      
      // Determine which buffer to use
      const targetBuffer = phase === 'planning' ? 'planning' : 'trading'
      
      // Start polling for events
      restApiService.startPolling(
        phase,
        tradingDate,
        (events, isComplete, newYorkOffset) => {
          // Update event buffer with new events
          const buffer = eventBufferManager.getBuffer(targetBuffer)
          
          // Replace events from this specific phase (preserves events from other phases in shared buffers)
          buffer.replaceEventsForPhase(events, phase)
          
          // Store the New York offset in the buffer
          if (newYorkOffset !== undefined && newYorkOffset !== null) {
            buffer.setNewYorkOffset(newYorkOffset)
          }
          
          console.debug(`${phase} polling update: ${events.length} events, complete: ${isComplete}`)
        },
        () => {
          // On completion callback
          console.debug(`${phase} phase completed`)
          this.handlePhaseComplete(phase, tradingDate)
        },
        (error) => {
          // On error callback
          console.error(`${phase} polling error:`, error)
        }
      )
      
      console.debug(`${phase} phase started and polling initiated`)
    } catch (error) {
      this.isPolling = false
      console.error(`Failed to start ${phase} phase:`, error)
      throw error
    }
  }

  /**
   * Handle phase completion and auto-progress to next phase
   * @param {string} completedPhase - Phase that just completed
   * @param {string} tradingDate - Trading date
   * @private
   */
  async handlePhaseComplete(completedPhase, tradingDate) {
    console.debug(`Handling completion of ${completedPhase} phase`)
    
    // Auto-progression logic
    if (completedPhase === 'planning') {
      console.debug('Auto-starting preparing phase')
      try {
        await this.startPhase('preparing', tradingDate)
      } catch (error) {
        console.error('Failed to auto-start preparing phase:', error)
      }
    } else if (completedPhase === 'preparing') {
      console.debug('Auto-starting trading phase')
      try {
        await this.startPhase('trading', tradingDate)
      } catch (error) {
        console.error('Failed to auto-start trading phase:', error)
      }
    } else if (completedPhase === 'trading') {
      console.debug('All phases complete!')
      this.isPolling = false
    }
  }

  /**
   * Reset planning phase
   */
  resetPlanning() {
    console.debug('Resetting planning phase')
    this.isInitialized = false
    this.isInitializing = false
    this.isPolling = false
    this.currentConfig = null
    this.currentPhase = null
    eventBufferManager.clearPhase('planning')
    eventBufferManager.clearPhase('trading')
    eventPlaybackService.reset()
    restApiService.stopAllPolling()
  }

  /**
   * Get current phase status
   * @returns {Object} Phase status information
   */
  getStatus() {
    return {
      isInitializing: this.isInitializing,
      isInitialized: this.isInitialized,
      isPolling: this.isPolling,
      currentPhase: this.currentPhase,
      currentConfig: this.currentConfig,
      planningEventCount: eventBufferManager.getBuffer('planning').getEventCount(),
      tradingEventCount: eventBufferManager.getBuffer('trading').getEventCount()
    }
  }

  /**
   * Validate planning configuration
   * @param {PlanningConfig} config - Configuration to validate
   * @private
   */
  validateConfig(config) {
    if (!config) {
      throw new Error('Planning configuration is required')
    }

    if (!config.tradingDate) {
      throw new Error('Trading date is required')
    }
  }
}

// Create singleton instance
const phaseService = new PhaseService()

export default phaseService
