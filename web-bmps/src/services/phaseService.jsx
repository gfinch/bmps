/**
 * Phase Service for BMPS application
 * Handles all phase operations and transitions: planning → preparing → trading
 */

import websocketService from './websocketService.jsx'
import eventBufferManager from './eventBuffer.jsx'
import eventPlaybackService from './eventPlaybackService.jsx'

/**
 * @typedef {Object} PlanningConfig
 * @property {string} tradingDate - Trading date in YYYY-MM-DD format
 * @property {number} planningDays - Number of planning days (1-15)
 */

/**
 * Phase Service class - handles all phase lifecycle
 */
class PhaseService {
  constructor() {
    this.isInitializing = false
    this.isInitialized = false
    this.currentConfig = null
    
    // Set up event handling when WebSocket service is configured
    this.setupEventHandling()
  }

  /**
   * Set up event handling for WebSocket messages
   * @private
   */
  setupEventHandling() {
    // Configure WebSocket service to handle messages
    websocketService.configure({
      onMessage: this.handleWebSocketMessage.bind(this),
      onError: this.handleWebSocketError.bind(this),
      onStatusChange: this.handleStatusChange.bind(this),
      onOpen: this.handleWebSocketOpen.bind(this)
    })
  }

  /**
   * Initialize planning phase with configuration
   * @param {PlanningConfig} config - Planning configuration
   * @returns {Promise<void>}
   */
  async initializePlanning(config) {
    if (this.isInitializing) {
      throw new Error('Planning initialization already in progress')
    }

    console.debug('Initializing planning phase with config:', config)
    
    // Validate configuration
    this.validateConfig(config)
    
    this.isInitializing = true
    this.currentConfig = config
    
    try {
      // Clear existing planning events
      eventBufferManager.clearPhase('planning')
      
      // Connect to WebSocket if not connected
      if (!websocketService.isConnected()) {
        console.debug('WebSocket not connected, connecting...')
        websocketService.connect()
        
        // Wait for connection (with timeout)
        await this.waitForConnection(10000) // 10 second timeout
      }
      
      // Send planning commands
      await this.sendPlanningCommands(config)
      
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
   * Send planning phase commands
   * @param {PlanningConfig} config - Planning configuration
   * @private
   */
  async sendPlanningCommands(config) {
    console.debug('Sending planning phase commands')
    
    // First, send reset command to clear all event buffers
    const resetCommand = {
      command: 'reset'
    }
    
    console.debug('Sending reset command:', resetCommand)
    websocketService.send(resetCommand)
    
    // Small delay to ensure reset command is processed
    await new Promise(resolve => setTimeout(resolve, 200))
    
    // Then, start the planning phase (this triggers processing)
    const startCommand = {
      command: 'startPhase',
      phase: 'planning',
      options: {
        tradingDate: config.tradingDate,
        planningDays: config.planningDays.toString()
      }
    }
    
    console.debug('Sending start command:', startCommand)
    websocketService.send(startCommand)
    
    // Small delay to ensure start command is processed
    await new Promise(resolve => setTimeout(resolve, 100))
    
    // Finally, subscribe to planning phase events (this starts receiving events)
    const subscribeCommand = {
      command: 'subscribePhase',
      phase: 'planning'
    }
    
    console.debug('Sending subscribe command:', subscribeCommand)
    websocketService.send(subscribeCommand)
  }

  /**
   * Start next phase in the sequence
   * @param {string} phase - Phase to start ('preparing' or 'trading')
   * @private
   */
  async startNextPhase(phase) {
    try {
      console.debug(`Starting ${phase} phase`)
      await this.sendPhaseCommands(phase)
      console.debug(`${phase} phase started successfully`)
    } catch (error) {
      console.error(`Failed to start ${phase} phase:`, error)
      // Stop here - no retry as requested
    }
  }

  /**
   * Send start and subscribe commands for any phase
   * @param {string} phase - Phase name ('preparing' or 'trading')
   * @private
   */
  async sendPhaseCommands(phase) {
    // Start the phase
    const startCommand = {
      command: 'startPhase',
      phase: phase
    }
    
    console.debug(`Sending start command for ${phase}:`, startCommand)
    websocketService.send(startCommand)
    
    // Small delay to ensure start command is processed
    await new Promise(resolve => setTimeout(resolve, 100))
    
    // Subscribe to phase events
    const subscribeCommand = {
      command: 'subscribePhase',
      phase: phase
    }
    
    console.debug(`Sending subscribe command for ${phase}:`, subscribeCommand)
    websocketService.send(subscribeCommand)
  }

  /**
   * Reset planning phase
   */
  resetPlanning() {
    console.debug('Resetting planning phase')
    this.isInitialized = false
    this.isInitializing = false
    this.currentConfig = null
    eventBufferManager.clearPhase('planning')
  }

  /**
   * Get current planning status
   * @returns {Object} Planning status information
   */
  getStatus() {
    return {
      isInitializing: this.isInitializing,
      isInitialized: this.isInitialized,
      isConnected: websocketService.isConnected(),
      connectionStatus: websocketService.getStatus(),
      currentConfig: this.currentConfig,
      eventCount: eventBufferManager.getBuffer('planning').getEventCount()
    }
  }

  /**
   * Validate planning configuration
   * @param {PlanningConfig} config - Configuration to validate
   * @private
   */
  validateConfig(config) {
    if (!config) {
      throw new Error('Configuration is required')
    }
    
    if (!config.tradingDate || typeof config.tradingDate !== 'string') {
      throw new Error('Valid trading date is required')
    }
    
    if (!config.planningDays || 
        typeof config.planningDays !== 'number' || 
        config.planningDays < 1 || 
        config.planningDays > 15) {
      throw new Error('Planning days must be a number between 1 and 15')
    }
    
    // Validate date format (basic check)
    const dateRegex = /^\d{4}-\d{2}-\d{2}$/
    if (!dateRegex.test(config.tradingDate)) {
      throw new Error('Trading date must be in YYYY-MM-DD format')
    }
  }

  /**
   * Wait for WebSocket connection
   * @param {number} timeout - Timeout in milliseconds
   * @returns {Promise<void>}
   * @private
   */
  waitForConnection(timeout = 5000) {
    return new Promise((resolve, reject) => {
      const checkConnection = () => {
        if (websocketService.isConnected()) {
          resolve()
          return
        }
        
        const status = websocketService.getStatus()
        if (status === 'error') {
          reject(new Error('WebSocket connection failed'))
          return
        }
        
        // Continue waiting
        setTimeout(checkConnection, 100)
      }
      
      // Start checking
      checkConnection()
      
      // Set timeout
      setTimeout(() => {
        reject(new Error('WebSocket connection timeout'))
      }, timeout)
    })
  }

  // Event handlers

  /**
   * Handle WebSocket messages
   * @param {Object} message - WebSocket message
   * @private
   */
  handleWebSocketMessage(message) {
    console.debug('Phase service received message:', message)
    console.debug('Message type check - isPhaseEvent:', this.isPhaseEvent(message))
    console.debug('Message type check - isPhaseCompleteEvent:', this.isPhaseCompleteEvent(message))
    console.debug('Message type check - isResetEvent:', this.isResetEvent(message))
    console.debug('Message type check - isLifecycleEvent:', this.isLifecycleEvent(message))
    console.debug('Message type check - isErrorMessage:', this.isErrorMessage(message))
    
    // Handle reset events - clear event buffers but preserve results
    if (this.isResetEvent(message)) {
      console.debug('Reset event received - clearing event buffers')
      this.handleResetEvent()
      return
    }
    
    // Handle phase complete events for automatic transitions
    if (this.isPhaseCompleteEvent(message)) {
      const completedPhase = message.phase.toLowerCase()
      console.debug(`Phase ${completedPhase} completed`)
      
      if (completedPhase === 'planning') {
        this.startNextPhase('preparing')
      } else if (completedPhase === 'preparing') {
        this.startNextPhase('trading')
      }
      return
    }
    
    // Route events to appropriate phase buffer
    if (this.isPhaseEvent(message)) {
      const phase = message.phase ? message.phase.toLowerCase() : 'planning'
      
      // Determine target buffer: preparing and trading events go to trading buffer
      let targetBuffer
      if (phase === 'planning') {
        targetBuffer = 'planning'
      } else if (phase === 'preparing' || phase === 'trading') {
        targetBuffer = 'trading'
      } else {
        targetBuffer = phase // fallback
      }
      
      console.debug(`Routing ${phase} event to '${targetBuffer}' buffer`)
      eventBufferManager.addEvent({
        ...message,
        phase: targetBuffer,
        timestamp: message.timestamp || message.event?.timestamp || Date.now()
      })
    } else if (this.isLifecycleEvent(message)) {
      console.debug('Phase lifecycle event:', message)
      // Handle lifecycle events (phase transitions, etc.)
    } else if (this.isErrorMessage(message)) {
      console.error('Phase error:', message)
    } else {
      console.debug('Unknown phase message type:', message)
    }
  }

  /**
   * Handle WebSocket errors
   * @param {Error} error - WebSocket error
   * @private
   */
  handleWebSocketError(error) {
    console.error('Phase service WebSocket error:', error)
  }

  /**
   * Handle WebSocket status changes
   * @param {string} status - New connection status
   * @private
   */
  handleStatusChange(status) {
    console.debug(`Phase service WebSocket status: ${status}`)
    
    if (status === 'disconnected' || status === 'error') {
      this.isInitialized = false
    }
  }

  /**
   * Handle WebSocket connection open
   * @private
   */
  handleWebSocketOpen() {
    console.debug('Phase service WebSocket connected')
  }

  /**
   * Handle reset event - clear event buffers but preserve results
   * @private
   */
  handleResetEvent() {
    console.debug('Phase service handling reset event')
    
    try {
      // Reset playback service state (current timestamps, playing state, etc.)
      eventPlaybackService.resetPlaybackState()
      console.debug('Reset event playback service state')
      
      // Clear planning buffer
      eventBufferManager.clearPhase('planning')
      console.debug('Cleared planning event buffer')
      
      // Clear trading buffer  
      eventBufferManager.clearPhase('trading')
      console.debug('Cleared trading event buffer')
      
      // Reset internal state
      this.isInitialized = false
      this.isInitializing = false
      this.currentConfig = null
      
      console.debug('Phase service state reset complete')
    } catch (error) {
      console.error('Error handling reset event:', error)
    }
  }

  // Message type detection helpers

  /**
   * Check if message is a phase event
   * @param {Object} message - Message to check
   * @returns {boolean}
   * @private
   */
  isPhaseEvent(message) {
    return message && 
           typeof message.phase === 'string' && 
           message.event && 
           typeof message.event === 'object'
  }

  /**
   * Check if message is a phase complete event
   * @param {Object} message - Message to check
   * @returns {boolean}
   * @private
   */
  isPhaseCompleteEvent(message) {
    return message && 
           message.event && 
           message.event.eventType &&
           (message.event.eventType === 'PhaseComplete' || 
            (typeof message.event.eventType === 'object' && 'PhaseComplete' in message.event.eventType))
  }

  /**
   * Check if message is a reset event
   * @param {Object} message - Message to check
   * @returns {boolean}
   * @private
   */
  isResetEvent(message) {
    return message && 
           message.event && 
           message.event.eventType &&
           (message.event.eventType === 'Reset' || 
            (typeof message.event.eventType === 'object' && 'Reset' in message.event.eventType))
  }

  /**
   * Check if message is a lifecycle event
   * @param {Object} message - Message to check
   * @returns {boolean}
   * @private
   */
  isLifecycleEvent(message) {
    return message && typeof message.lifecycleType === 'string'
  }

  /**
   * Check if message is an error message
   * @param {Object} message - Message to check
   * @returns {boolean}
   * @private
   */
  isErrorMessage(message) {
    return message && typeof message.error === 'string'
  }
}

// Create singleton instance
const phaseService = new PhaseService()

export default phaseService