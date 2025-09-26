/**
 * WebSocket Hook for React components
 * Provides connection status and management capabilities
 */

import { useState, useEffect, useRef } from 'react'
import websocketService from '../services/websocketService.jsx'

/**
 * WebSocket Hook
 * @returns {Object} WebSocket state and methods
 */
export function useWebSocket() {
  const [status, setStatus] = useState(websocketService.getStatus())
  const [isConnected, setIsConnected] = useState(websocketService.isConnected())
  const [error, setError] = useState(null)
  const [connectionAttempts, setConnectionAttempts] = useState(0)
  
  // Use refs to track mounted state and prevent state updates on unmounted components
  const isMounted = useRef(true)

  useEffect(() => {
    isMounted.current = true
    
    // Configure WebSocket service with event handlers
    const originalConfig = {
      onStatusChange: websocketService.onStatusChange,
      onError: websocketService.onError,
      onOpen: websocketService.onOpen
    }

    // Set up our handlers
    const handleStatusChange = (newStatus) => {
      if (!isMounted.current) return
      
      console.debug(`useWebSocket: Status changed to ${newStatus}`)
      setStatus(newStatus)
      setIsConnected(newStatus === 'connected')
      
      if (newStatus === 'connecting') {
        setConnectionAttempts(prev => prev + 1)
      }
      
      if (newStatus === 'connected') {
        setError(null)
      }
      
      // Call original handler if it exists
      if (originalConfig.onStatusChange) {
        originalConfig.onStatusChange(newStatus)
      }
    }

    const handleError = (err) => {
      if (!isMounted.current) return
      
      console.error('useWebSocket: Error occurred:', err)
      setError(err.message || 'WebSocket error')
      
      // Call original handler if it exists
      if (originalConfig.onError) {
        originalConfig.onError(err)
      }
    }

    const handleOpen = () => {
      if (!isMounted.current) return
      
      console.debug('useWebSocket: Connection opened')
      setError(null)
      
      // Call original handler if it exists
      if (originalConfig.onOpen) {
        originalConfig.onOpen()
      }
    }

    // Configure the service with our handlers
    websocketService.configure({
      onStatusChange: handleStatusChange,
      onError: handleError,
      onOpen: handleOpen,
      // Preserve other existing configuration
      onMessage: websocketService.onMessage
    })

    // Initial connection if not already connected
    if (!websocketService.isConnected() && websocketService.getStatus() === 'disconnected') {
      console.debug('useWebSocket: Initiating connection')
      websocketService.connect()
    }

    // Cleanup function
    return () => {
      isMounted.current = false
      
      // Note: We don't disconnect here as other components might be using the service
      // The service will handle connection lifecycle independently
      console.debug('useWebSocket: Component unmounted')
    }
  }, []) // Empty dependency array - run once on mount

  // Methods to expose
  const connect = () => {
    console.debug('useWebSocket: Manual connect requested')
    setError(null)
    websocketService.connect()
  }

  const disconnect = () => {
    console.debug('useWebSocket: Manual disconnect requested')
    websocketService.disconnect()
  }

  const send = (data) => {
    if (!isConnected) {
      console.warn('useWebSocket: Attempted to send data while not connected')
      setError('Cannot send data: WebSocket not connected')
      return false
    }
    
    try {
      websocketService.send(data)
      return true
    } catch (err) {
      console.error('useWebSocket: Error sending data:', err)
      setError(err.message || 'Failed to send data')
      return false
    }
  }

  // Computed values
  const isConnecting = status === 'connecting'
  const isReconnecting = status === 'reconnecting'
  const hasError = status === 'error' || error !== null
  const isDisconnected = status === 'disconnected'

  return {
    // Status information
    status,
    isConnected,
    isConnecting,
    isReconnecting,
    isDisconnected,
    hasError,
    error,
    connectionAttempts,
    
    // Methods
    connect,
    disconnect,
    send,
    
    // Utility methods
    getStatusMessage: () => {
      switch (status) {
        case 'connected':
          return 'Connected to server'
        case 'connecting':
          return 'Connecting to server...'
        case 'reconnecting':
          return 'Reconnecting to server...'
        case 'disconnected':
          return 'Disconnected from server'
        case 'error':
          return error || 'Connection error'
        default:
          return 'Unknown status'
      }
    }
  }
}

export default useWebSocket