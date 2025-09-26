/**
 * WebSocket Service for BMPS application
 * Handles connection management, auto-reconnection, and message routing
 */

const WS_URL = 'ws://localhost:8080/ws'

/**
 * @typedef {'disconnected' | 'connecting' | 'connected' | 'reconnecting' | 'error'} ConnectionStatus
 */

/**
 * WebSocket Service Configuration
 * @typedef {Object} WebSocketConfig
 * @property {function(Object): void} [onMessage] - Message handler callback
 * @property {function(Error): void} [onError] - Error handler callback  
 * @property {function(ConnectionStatus): void} [onStatusChange] - Status change callback
 * @property {function(): void} [onOpen] - Connection opened callback
 * @property {number} [reconnectInterval] - Reconnection interval in ms (default: 3000)
 * @property {number} [maxReconnectAttempts] - Max reconnection attempts (default: 10)
 */

class WebSocketService {
  constructor() {
    this.ws = null
    this.status = 'disconnected'
    this.reconnectInterval = 3000
    this.maxReconnectAttempts = 10
    this.reconnectAttempts = 0
    this.reconnectTimeoutId = null
    this.shouldReconnect = true
    this.messageQueue = []
    
    // Event handlers
    this.onMessage = null
    this.onError = null
    this.onStatusChange = null
    this.onOpen = null
  }

  /**
   * Configure the WebSocket service
   * @param {WebSocketConfig} config - Configuration object
   */
  configure(config) {
    this.onMessage = config.onMessage || null
    this.onError = config.onError || null
    this.onStatusChange = config.onStatusChange || null
    this.onOpen = config.onOpen || null
    this.reconnectInterval = config.reconnectInterval || 3000
    this.maxReconnectAttempts = config.maxReconnectAttempts || 10
  }

  /**
   * Connect to WebSocket server
   */
  connect() {
    if (this.ws?.readyState === WebSocket.OPEN) {
      console.debug('WebSocket already connected')
      return
    }
    
    if (this.ws?.readyState === WebSocket.CONNECTING) {
      console.debug('WebSocket already connecting')
      return
    }

    this.setStatus('connecting')
    console.debug(`Connecting to WebSocket: ${WS_URL}`)

    try {
      this.ws = new WebSocket(WS_URL)
      
      this.ws.onopen = this.handleOpen.bind(this)
      this.ws.onmessage = this.handleMessage.bind(this)
      this.ws.onclose = this.handleClose.bind(this)
      this.ws.onerror = this.handleError.bind(this)
    } catch (error) {
      console.error('Failed to create WebSocket connection:', error)
      this.handleConnectionError(error)
    }
  }

  /**
   * Disconnect from WebSocket server
   */
  disconnect() {
    console.debug('Disconnecting WebSocket')
    this.shouldReconnect = false
    this.clearReconnectTimeout()
    
    if (this.ws) {
      const readyState = this.ws.readyState
      console.debug(`WebSocket readyState during disconnect: ${readyState}`)
      
      if (readyState === WebSocket.CONNECTING || readyState === WebSocket.OPEN) {
        this.ws.close()
      }
      this.ws = null
    }
    
    this.setStatus('disconnected')
  }

  /**
   * Send data to WebSocket server
   * @param {Object} data - Data to send (will be JSON stringified)
   */
  send(data) {
    const message = JSON.stringify(data)
    
    if (this.ws?.readyState === WebSocket.OPEN) {
      console.debug('Sending WebSocket message:', data)
      this.ws.send(message)
    } else {
      console.debug('WebSocket not connected, queuing message:', data)
      this.messageQueue.push(message)
      
      // Try to connect if not already connecting
      if (this.status === 'disconnected') {
        this.connect()
      }
    }
  }

  /**
   * Get current connection status
   * @returns {ConnectionStatus}
   */
  getStatus() {
    return this.status
  }

  /**
   * Check if WebSocket is connected
   * @returns {boolean}
   */
  isConnected() {
    return this.status === 'connected'
  }

  // Private methods

  handleOpen() {
    console.debug('WebSocket connected')
    this.reconnectAttempts = 0
    this.setStatus('connected')
    
    // Process any queued messages
    if (this.messageQueue.length > 0) {
      console.debug(`Processing ${this.messageQueue.length} queued messages`)
      this.messageQueue.forEach(message => {
        this.ws.send(message)
      })
      this.messageQueue = []
    }
    
    // Call the onOpen callback if provided
    if (this.onOpen) {
      this.onOpen()
    }
  }

  handleMessage(event) {
    try {
      const message = JSON.parse(event.data)
      console.debug('Received WebSocket message:', message)
      
      if (this.onMessage) {
        this.onMessage(message)
      }
    } catch (error) {
      console.error('Failed to parse WebSocket message:', error)
      console.error('Raw message:', event.data)
      
      if (this.onError) {
        this.onError(new Error('Failed to parse WebSocket message'))
      }
    }
  }

  handleClose(event) {
    console.debug('WebSocket closed:', event.code, event.reason)
    this.ws = null

    if (this.shouldReconnect && this.status !== 'disconnected') {
      this.attemptReconnect()
    } else {
      this.setStatus('disconnected')
    }
  }

  handleError(event) {
    console.error('WebSocket error:', event)
    this.handleConnectionError(new Error('WebSocket connection error'))
  }

  handleConnectionError(error) {
    this.setStatus('error')
    
    if (this.onError) {
      this.onError(error)
    }
    
    if (this.shouldReconnect) {
      this.attemptReconnect()
    }
  }

  attemptReconnect() {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      console.error(`Max reconnection attempts (${this.maxReconnectAttempts}) reached`)
      this.setStatus('error')
      
      if (this.onError) {
        this.onError(new Error('Max reconnection attempts reached'))
      }
      return
    }

    this.reconnectAttempts++
    this.setStatus('reconnecting')
    
    console.debug(`Attempting to reconnect... (${this.reconnectAttempts}/${this.maxReconnectAttempts})`)
    
    this.reconnectTimeoutId = setTimeout(() => {
      this.connect()
    }, this.reconnectInterval)
  }

  clearReconnectTimeout() {
    if (this.reconnectTimeoutId) {
      clearTimeout(this.reconnectTimeoutId)
      this.reconnectTimeoutId = null
    }
  }

  setStatus(status) {
    if (this.status !== status) {
      this.status = status
      console.debug(`WebSocket status changed to: ${status}`)
      
      if (this.onStatusChange) {
        this.onStatusChange(status)
      }
    }
  }
}

// Create singleton instance
const websocketService = new WebSocketService()

export default websocketService