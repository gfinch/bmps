// React hook for WebSocket connection management

import { useEffect, useRef, useCallback } from 'react';
import { WebSocketManager, createWebSocketManager, ConnectionStatus } from '../services/websocket';
import { useEventStore } from '../stores/eventStore';
import { PhaseEvent, Lifecycle, ErrorMessage } from '../types/events';

export interface UseWebSocketOptions {
  url: string;
  autoConnect?: boolean;
  onOpen?: () => void;
  onError?: (error: Error) => void;
}

export function useWebSocket({ url, autoConnect = false, onOpen, onError }: UseWebSocketOptions) {
  const wsRef = useRef<WebSocketManager | null>(null);
  const urlRef = useRef<string>(url);
  const { setConnectionStatus, addEvent } = useEventStore();
  
  // Update URL ref when it changes
  urlRef.current = url;

  // Initialize WebSocket manager only once
  useEffect(() => {
    if (wsRef.current) {
      console.log('WebSocket manager already exists, skipping creation');
      return;
    }

    const handlePhaseEvent = (message: PhaseEvent) => {
      console.log('Processing PhaseEvent:', message);
      addEvent(message);
    };

    const handleLifecycle = (message: Lifecycle) => {
      console.log('Received lifecycle message:', message);
    };

    const handleError = (error: ErrorMessage | Error) => {
      if ('message' in error && typeof error.message === 'string') {
        // Server error message
        console.error('Server error:', error.message);
        onError?.(new Error(error.message));
      } else {
        // Connection error
        console.error('WebSocket error:', error);
        onError?.(error as Error);
      }
    };

    const handleStatusChange = (status: ConnectionStatus) => {
      console.log('WebSocket status changed:', status);
      setConnectionStatus(status);
    };

    const handleOpen = () => {
      onOpen?.();
    };

    wsRef.current = createWebSocketManager({
      url: urlRef.current,
      onPhaseEvent: handlePhaseEvent,
      onLifecycle: handleLifecycle,
      onError: handleError,
      onStatusChange: handleStatusChange,
      onOpen: handleOpen,
      reconnectInterval: 3000,
      maxReconnectAttempts: 10,
    });

    // Auto-connect if specified
    if (autoConnect) {
      wsRef.current.connect();
    }

    // Only cleanup on actual unmount, not on every re-render
    return () => {
      console.log('WebSocket hook cleanup - component unmounting');
      if (wsRef.current) {
        wsRef.current.disconnect();
        wsRef.current = null;
      }
    };
  }, []); // Empty dependency array - only create once

  // Handle URL changes separately
  useEffect(() => {
    if (wsRef.current && urlRef.current !== url) {
      console.log(`URL changed from ${urlRef.current} to ${url}, recreating WebSocket manager...`);
      urlRef.current = url;
      
      // Disconnect old manager
      wsRef.current.disconnect();
      
      // Create new manager with new URL - reuse the same handlers
      const handlePhaseEvent = (message: PhaseEvent) => {
        console.log('Processing PhaseEvent:', message);
        addEvent(message);
      };

      const handleLifecycle = (message: Lifecycle) => {
        console.log('Received lifecycle message:', message);
      };

      const handleError = (error: ErrorMessage | Error) => {
        if ('message' in error && typeof error.message === 'string') {
          onError?.(new Error(error.message));
        } else {
          onError?.(error as Error);
        }
      };

      const handleStatusChange = (status: ConnectionStatus) => {
        setConnectionStatus(status);
      };

      const handleOpen = () => {
        onOpen?.();
      };

      wsRef.current = createWebSocketManager({
        url: url,
        onPhaseEvent: handlePhaseEvent,
        onLifecycle: handleLifecycle,
        onError: handleError,
        onStatusChange: handleStatusChange,
        onOpen: handleOpen,
        reconnectInterval: 3000,
        maxReconnectAttempts: 10,
      });
    }
  }, [url, addEvent, setConnectionStatus, onOpen, onError]);

  // Return control functions
  const connect = () => {
    wsRef.current?.connect();
  };

  const disconnect = () => {
    wsRef.current?.disconnect();
  };

  const send = (data: any) => {
    wsRef.current?.send(data);
  };

  const getStatus = (): ConnectionStatus => {
    return wsRef.current?.getStatus() || 'disconnected';
  };

  return {
    connect,
    disconnect,
    send,
    getStatus,
  };
}