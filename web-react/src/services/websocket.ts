// WebSocket connection manager with auto-reconnection

import { ServerMessage, PhaseEvent, Lifecycle, ErrorMessage } from '../types/events';

export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'reconnecting' | 'error';

export interface WebSocketManagerOptions {
  url: string;
  onPhaseEvent?: (message: PhaseEvent) => void;
  onLifecycle?: (message: Lifecycle) => void;
  onError?: (error: ErrorMessage | Error) => void;
  onStatusChange?: (status: ConnectionStatus) => void;
  onOpen?: () => void;
  reconnectInterval?: number;
  maxReconnectAttempts?: number;
}

export class WebSocketManager {
  private ws: WebSocket | null = null;
  private url: string;
  private onPhaseEvent?: (message: PhaseEvent) => void;
  private onLifecycle?: (message: Lifecycle) => void;
  private onError?: (error: ErrorMessage | Error) => void;
  private onStatusChange?: (status: ConnectionStatus) => void;
  private onOpen?: () => void;
  private status: ConnectionStatus = 'disconnected';
  private reconnectInterval: number;
  private maxReconnectAttempts: number;
  private reconnectAttempts = 0;
  private reconnectTimeoutId: number | null = null;
  private shouldReconnect = true;

  constructor(options: WebSocketManagerOptions) {
    this.url = options.url;
    this.onPhaseEvent = options.onPhaseEvent;
    this.onLifecycle = options.onLifecycle;
    this.onError = options.onError;
    this.onStatusChange = options.onStatusChange;
    this.onOpen = options.onOpen;
    this.reconnectInterval = options.reconnectInterval || 3000; // 3 seconds
    this.maxReconnectAttempts = options.maxReconnectAttempts || 10;
  }

  connect(): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      console.log('WebSocket already connected');
      return;
    }
    
    if (this.ws?.readyState === WebSocket.CONNECTING) {
      console.log('WebSocket already connecting');
      return;
    }

    this.setStatus('connecting');
    console.log(`Connecting to WebSocket: ${this.url}`);

    try {
      this.ws = new WebSocket(this.url);
      
      this.ws.onopen = this.handleOpen.bind(this);
      this.ws.onmessage = this.handleMessage.bind(this);
      this.ws.onclose = this.handleClose.bind(this);
      this.ws.onerror = this.handleError.bind(this);
    } catch (error) {
      console.error('Failed to create WebSocket connection:', error);
      this.handleConnectionError(error as Error);
    }
  }

  disconnect(): void {
    console.log('Disconnecting WebSocket');
    this.shouldReconnect = false;
    this.clearReconnectTimeout();
    
    if (this.ws) {
      const readyState = this.ws.readyState;
      console.log(`WebSocket readyState during disconnect: ${readyState}`);
      
      if (readyState === WebSocket.CONNECTING || readyState === WebSocket.OPEN) {
        this.ws.close();
      }
      this.ws = null;
    }
    
    this.setStatus('disconnected');
  }

  send(data: any): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(data));
    } else {
      console.warn('Cannot send message: WebSocket not connected');
    }
  }

  getStatus(): ConnectionStatus {
    return this.status;
  }

  private handleOpen(): void {
    console.log('WebSocket connected');
    this.reconnectAttempts = 0;
    this.setStatus('connected');
    
    // Call the onOpen callback if provided
    if (this.onOpen) {
      this.onOpen();
    }
  }

  private handleMessage(event: MessageEvent): void {
    try {
      const message: ServerMessage = JSON.parse(event.data);
      console.log('Received WebSocket message:', message);
      
      // Handle different message types based on their structure
      if (this.isPhaseEvent(message)) {
        if (this.onPhaseEvent) {
          this.onPhaseEvent(message);
        }
      } else if (this.isLifecycleEvent(message)) {
        if (this.onLifecycle) {
          this.onLifecycle(message);
        }
      } else if (this.isErrorMessage(message)) {
        if (this.onError) {
          this.onError(message);
        }
      } else {
        console.log('Unknown message type:', message);
      }
    } catch (error) {
      console.error('Failed to parse WebSocket message:', error);
      if (this.onError) {
        this.onError(new Error('Failed to parse message'));
      }
    }
  }

  private isPhaseEvent(message: any): message is PhaseEvent {
    return message && typeof message.phase === 'string' && message.event;
  }

  private isLifecycleEvent(message: any): message is Lifecycle {
    return message && typeof message.lifecycleType === 'string';
  }

  private isErrorMessage(message: any): message is ErrorMessage {
    return message && typeof message.error === 'string';
  }

  private handleClose(event: CloseEvent): void {
    console.log('WebSocket closed:', event.code, event.reason);
    this.ws = null;

    if (this.shouldReconnect && this.status !== 'disconnected') {
      this.attemptReconnect();
    } else {
      this.setStatus('disconnected');
    }
  }

  private handleError(event: globalThis.Event): void {
    console.error('WebSocket error:', event);
    this.handleConnectionError(new Error('WebSocket connection error'));
  }

  private handleConnectionError(error: Error): void {
    this.setStatus('error');
    this.onError?.(error);
    
    if (this.shouldReconnect) {
      this.attemptReconnect();
    }
  }

  private attemptReconnect(): void {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      console.error(`Max reconnection attempts (${this.maxReconnectAttempts}) reached`);
      this.setStatus('error');
      this.onError?.(new Error('Max reconnection attempts reached'));
      return;
    }

    this.reconnectAttempts++;
    this.setStatus('reconnecting');
    
    console.log(`Attempting to reconnect... (${this.reconnectAttempts}/${this.maxReconnectAttempts})`);
    
    this.reconnectTimeoutId = window.setTimeout(() => {
      this.connect();
    }, this.reconnectInterval);
  }

  private clearReconnectTimeout(): void {
    if (this.reconnectTimeoutId) {
      clearTimeout(this.reconnectTimeoutId);
      this.reconnectTimeoutId = null;
    }
  }

  private setStatus(status: ConnectionStatus): void {
    if (this.status !== status) {
      this.status = status;
      console.log(`WebSocket status changed to: ${status}`);
      this.onStatusChange?.(status);
    }
  }

  private isValidServerMessage(data: any): data is ServerMessage {
    if (!data || typeof data !== 'object' || !data.type) {
      return false;
    }

    switch (data.type) {
      case 'event':
        return (
          typeof data.phase === 'string' &&
          data.event &&
          typeof data.event === 'object' &&
          data.event.eventType &&
          typeof data.event.timestamp === 'number'
        );
      case 'lifecycle':
        return (
          typeof data.phase === 'string' &&
          typeof data.status === 'string'
        );
      case 'error':
        return typeof data.message === 'string';
      default:
        return false;
    }
  }
}

// Factory function to create WebSocket manager
export function createWebSocketManager(options: WebSocketManagerOptions): WebSocketManager {
  return new WebSocketManager(options);
}