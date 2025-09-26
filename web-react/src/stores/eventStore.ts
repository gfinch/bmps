// Zustand store for event buffer management

import { create } from 'zustand';
import { devtools } from 'zustand/middleware';
import { 
  PhaseEvent, 
  Event, 
  extractEventType,
  extractDirection,
  extractPlanZoneType,
  extractExtremeType,
  extractOrderType,
  extractOrderStatus,
  extractEntryType,
} from '../types/events';
import { ConnectionStatus } from '../services/websocket';

// Extend the window interface to include globalWebSocket
declare global {
  interface Window {
    globalWebSocket?: WebSocket;
  }
}

// Helper function to send WebSocket commands
const sendWebSocketCommand = (command: any) => {
  if (window.globalWebSocket && window.globalWebSocket.readyState === WebSocket.OPEN) {
    console.log('Sending WebSocket command:', command);
    window.globalWebSocket.send(JSON.stringify(command));
  } else {
    console.warn('Cannot send command - WebSocket not connected:', command);
  }
};

// Processed event data for easier chart consumption
export interface ProcessedCandle {
  time: number; // TradingView time format (seconds)
  open: number;
  high: number;
  low: number;
  close: number;
  timestamp: number; // Original Unix timestamp
}

export interface ProcessedSwingPoint {
  timestamp: number;
  level: number;
  direction: 'Up' | 'Down' | 'Doji';
  id: string; // Unique identifier for chart markers
}

export interface ProcessedPlanZone {
  timestamp: number;
  startTime: number;
  endTime?: number;
  low: number;
  high: number;
  type: 'Supply' | 'Demand';
  id: string; // Unique identifier for the zone
  isActive: boolean; // Whether the zone is still active (no end time)
}

export interface ProcessedDaytimeExtreme {
  timestamp: number;
  endTime?: number;
  level: number;
  extremeType: 'High' | 'Low';
  description: string;
  abbreviation: string; // NY, L, A
  id: string; // Unique identifier
}

export interface ProcessedOrder {
  timestamp: number;
  low: number;
  high: number;
  orderType: 'Long' | 'Short';
  entryType: 'EngulfingOrderBlock';
  status: 'Planned' | 'Placed' | 'Filled' | 'Profit' | 'Loss' | 'Cancelled';
  profitMultiplier: number;
  riskDollars: number;
  placedTimestamp?: number;
  filledTimestamp?: number;
  closeTimestamp?: number;
  entryPoint: number;
  stopLoss: number;
  takeProfit: number;
  contracts: number;
  atRisk: number;
  potential: number;
  id: string; // Unique identifier for the order
}

export interface PlaybackState {
  currentTime: number; // Current playback timestamp
  isPlaying: boolean;
  playbackSpeed: number; // Multiplier for playback speed
  totalDuration: number; // Total time span of all events
  startTime: number; // Earliest event timestamp
  endTime: number; // Latest event timestamp
}

export interface EventStore {
  // Connection state
  connectionStatus: ConnectionStatus;
  isConnected: boolean;
  
  // Phase completion tracking
  isPlanningPhaseComplete: boolean;
  isPreparingPhaseComplete: boolean;
  
  // Raw events buffer (separate for Planning and Trading)
  planningEvents: Event[];
  tradingEvents: Event[];
  
  // Processed data for charts
  planningCandles: ProcessedCandle[];
  planningSwingPoints: ProcessedSwingPoint[];
  planningZones: ProcessedPlanZone[];
  planningDaytimeExtremes: ProcessedDaytimeExtreme[];
  
  tradingCandles: ProcessedCandle[];
  tradingSwingPoints: ProcessedSwingPoint[];
  tradingZones: ProcessedPlanZone[];
  tradingDaytimeExtremes: ProcessedDaytimeExtreme[];
  tradingOrders: ProcessedOrder[];
  
  // Playback state (separate for each chart)
  planningPlayback: PlaybackState;
  tradingPlayback: PlaybackState;
  
  // Actions
  setConnectionStatus: (status: ConnectionStatus) => void;
  addEvent: (message: PhaseEvent) => void;
  clearEvents: (phase?: 'Planning' | 'Trading') => void;
  
  // Playback actions
  setPlanningCurrentTime: (time: number) => void;
  setPlanningPlaying: (playing: boolean) => void;
  stepPlanningForward: () => void;
  stepPlanningBackward: () => void;
  resetPlanningPlayback: () => void;
  fastForwardToEnd: () => void;
  
  // Trading playback actions
  setTradingCurrentTime: (time: number) => void;
  setTradingPlaying: (playing: boolean) => void;
  stepTradingForward: () => void;
  stepTradingBackward: () => void;
  resetTradingPlayback: () => void;
  fastForwardTradingToEnd: () => void;
  
  // Utility methods
  getVisiblePlanningCandles: () => ProcessedCandle[];
  getVisiblePlanningSwingPoints: () => ProcessedSwingPoint[];
  getVisiblePlanningZones: () => ProcessedPlanZone[];
  getVisiblePlanningDaytimeExtremes: () => ProcessedDaytimeExtreme[];
  
  // Trading utility methods
  getVisibleTradingCandles: () => ProcessedCandle[];
  getVisibleTradingSwingPoints: () => ProcessedSwingPoint[];
  getVisibleTradingZones: () => ProcessedPlanZone[];
  getVisibleTradingDaytimeExtremes: () => ProcessedDaytimeExtreme[];
  getVisibleTradingOrders: () => ProcessedOrder[];
}

const createInitialPlaybackState = (): PlaybackState => ({
  currentTime: 0,
  isPlaying: false,
  playbackSpeed: 1,
  totalDuration: 0,
  startTime: 0,
  endTime: 0,
});

export const useEventStore = create<EventStore>()(
  devtools(
    (set, get) => ({
      // Initial state
      connectionStatus: 'disconnected',
      isConnected: false,
      
      isPlanningPhaseComplete: false,
      isPreparingPhaseComplete: false,
      
      planningEvents: [],
      tradingEvents: [],
      
      planningCandles: [],
      planningSwingPoints: [],
      planningZones: [],
      planningDaytimeExtremes: [],
      
      tradingCandles: [],
      tradingSwingPoints: [],
      tradingZones: [],
      tradingDaytimeExtremes: [],
      tradingOrders: [],
      
      planningPlayback: createInitialPlaybackState(),
      tradingPlayback: createInitialPlaybackState(),
      
      // Connection actions
      setConnectionStatus: (status: ConnectionStatus) => set((state) => ({
        connectionStatus: status,
        isConnected: status === 'connected',
      })),
      
      // Event processing
      addEvent: (message: PhaseEvent) => set((state) => {
        const { phase, event } = message;
        const eventType = extractEventType(event.eventType);
        
        if (phase === 'Planning') {
          // Check if this is a PhaseComplete event
          if (eventType === 'PhaseComplete') {
            console.log('Planning phase complete detected! Starting preparing phase...');
            
            // Automatically start the preparing phase
            const startPreparingCommand = {
              command: 'startPhase',
              phase: 'preparing',
              options: {}
            };
            
            const subscribePreparingCommand = {
              command: 'subscribePhase',
              phase: 'preparing'
            };
            
            // Send the commands after a brief delay to ensure the current state update is processed
            setTimeout(() => {
              sendWebSocketCommand(startPreparingCommand);
              // Subscribe to preparing events right after starting the phase
              setTimeout(() => {
                sendWebSocketCommand(subscribePreparingCommand);
              }, 100);
            }, 100);
            
            return {
              ...state,
              isPlanningPhaseComplete: true,
            };
          }
          
          // Add to planning events buffer
          const updatedEvents = [...state.planningEvents];
          
          // Handle out-of-order events by finding insertion point
          const insertIndex = updatedEvents.findIndex(e => e.timestamp > event.timestamp);
          if (insertIndex === -1) {
            updatedEvents.push(event);
          } else {
            updatedEvents.splice(insertIndex, 0, event);
          }
          
          // Update processed data
          const newState = {
            ...state,
            planningEvents: updatedEvents,
          };
          
          // Reprocess all events to maintain order
          return reprocessPlanningEvents(newState);
        } else if (phase === 'Preparing' || phase === 'Trading') {
          // Check if this is a PhaseComplete event
          if (eventType === 'PhaseComplete') {
            if (phase === 'Preparing') {
              console.log('Preparing phase complete detected! Starting trading phase...');
              
              // Automatically start the trading phase
              const startTradingCommand = {
                command: 'startPhase',
                phase: 'trading',
                options: {}
              };
              
              const subscribeTradingCommand = {
                command: 'subscribePhase',
                phase: 'trading'
              };
              
              // Send the commands after a brief delay to ensure the current state update is processed
              setTimeout(() => {
                sendWebSocketCommand(startTradingCommand);
                // Subscribe to trading events right after starting the phase
                setTimeout(() => {
                  sendWebSocketCommand(subscribeTradingCommand);
                }, 100);
              }, 100);
              
              return {
                ...state,
                isPreparingPhaseComplete: true,
              };
            } else {
              // Trading phase complete
              console.log('Trading phase complete!');
              return state;
            }
          }
          
          // Handle Preparing and Trading events (both go to trading store since they're unified)
          // Add to trading events buffer
          const updatedEvents = [...state.tradingEvents];
          
          // Handle out-of-order events by finding insertion point
          const insertIndex = updatedEvents.findIndex(e => e.timestamp > event.timestamp);
          if (insertIndex === -1) {
            updatedEvents.push(event);
          } else {
            updatedEvents.splice(insertIndex, 0, event);
          }
          
          // Update processed data
          const newState = {
            ...state,
            tradingEvents: updatedEvents,
          };
          
          // Reprocess all events to maintain order
          return reprocessTradingEvents(newState);
        } else {
          return state;
        }
      }),
      
      clearEvents: (phase?: 'Planning' | 'Trading') => set((state) => {
        if (!phase || phase === 'Planning') {
          return {
            ...state,
            planningEvents: [],
            planningCandles: [],
            planningSwingPoints: [],
            planningZones: [],
            planningDaytimeExtremes: [],
            planningPlayback: createInitialPlaybackState(),
            isPlanningPhaseComplete: false,
            isPreparingPhaseComplete: false,
          };
        } else if (phase === 'Trading') {
          return {
            ...state,
            tradingEvents: [],
            tradingCandles: [],
            tradingSwingPoints: [],
            tradingZones: [],
            tradingDaytimeExtremes: [],
            tradingOrders: [],
            tradingPlayback: createInitialPlaybackState(),
            isPreparingPhaseComplete: false,
          };
        }
        return state;
      }),
      
      // Playback actions
      setPlanningCurrentTime: (time: number) => set((state) => ({
        planningPlayback: { ...state.planningPlayback, currentTime: time },
      })),
      
      setPlanningPlaying: (playing: boolean) => set((state) => ({
        planningPlayback: { ...state.planningPlayback, isPlaying: playing },
      })),
      
      stepPlanningForward: () => set((state) => {
        const { planningCandles, planningPlayback } = state;
        if (planningCandles.length === 0) return state;
        
        // Find next candle timestamp
        const currentTime = planningPlayback.currentTime;
        const nextCandle = planningCandles.find(c => c.timestamp > currentTime);
        
        if (nextCandle) {
          return {
            planningPlayback: {
              ...planningPlayback,
              currentTime: nextCandle.timestamp,
            },
          };
        }
        
        return state;
      }),
      
      stepPlanningBackward: () => set((state) => {
        const { planningCandles, planningPlayback } = state;
        if (planningCandles.length === 0) return state;
        
        // Find previous candle timestamp
        const currentTime = planningPlayback.currentTime;
        const reversedCandles = [...planningCandles].reverse();
        const prevCandle = reversedCandles.find(c => c.timestamp < currentTime);
        
        if (prevCandle) {
          return {
            planningPlayback: {
              ...planningPlayback,
              currentTime: prevCandle.timestamp,
            },
          };
        }
        
        return state;
      }),
      
      resetPlanningPlayback: () => set((state) => {
        const { planningCandles } = state;
        const startTime = planningCandles.length > 0 ? planningCandles[0].timestamp : 0;
        
        return {
          planningPlayback: {
            ...state.planningPlayback,
            currentTime: startTime,
            isPlaying: false,
          },
        };
      }),
      
      fastForwardToEnd: () => set((state) => {
        const { planningCandles } = state;
        if (planningCandles.length === 0) return state;
        
        // Find the latest timestamp from all candles
        const latestCandle = planningCandles[planningCandles.length - 1];
        
        return {
          planningPlayback: {
            ...state.planningPlayback,
            currentTime: latestCandle.timestamp,
            isPlaying: false, // Stop playing when we reach the end
          },
        };
      }),
      
      // Trading playback actions
      setTradingCurrentTime: (time: number) => set((state) => ({
        tradingPlayback: { ...state.tradingPlayback, currentTime: time },
      })),
      
      setTradingPlaying: (playing: boolean) => set((state) => ({
        tradingPlayback: { ...state.tradingPlayback, isPlaying: playing },
      })),
      
      stepTradingForward: () => set((state) => {
        const { tradingCandles, tradingPlayback } = state;
        if (tradingCandles.length === 0) return state;
        
        // Find next candle timestamp
        const currentTime = tradingPlayback.currentTime;
        const nextCandle = tradingCandles.find(c => c.timestamp > currentTime);
        
        if (nextCandle) {
          return {
            tradingPlayback: {
              ...tradingPlayback,
              currentTime: nextCandle.timestamp,
            },
          };
        }
        
        return state;
      }),
      
      stepTradingBackward: () => set((state) => {
        const { tradingCandles, tradingPlayback } = state;
        if (tradingCandles.length === 0) return state;
        
        // Find previous candle timestamp
        const currentTime = tradingPlayback.currentTime;
        const reversedCandles = [...tradingCandles].reverse();
        const prevCandle = reversedCandles.find(c => c.timestamp < currentTime);
        
        if (prevCandle) {
          return {
            tradingPlayback: {
              ...tradingPlayback,
              currentTime: prevCandle.timestamp,
            },
          };
        }
        
        return state;
      }),
      
      resetTradingPlayback: () => set((state) => {
        const { tradingCandles } = state;
        const startTime = tradingCandles.length > 0 ? tradingCandles[0].timestamp : 0;
        
        return {
          tradingPlayback: {
            ...state.tradingPlayback,
            currentTime: startTime,
            isPlaying: false,
          },
        };
      }),
      
      fastForwardTradingToEnd: () => set((state) => {
        const { tradingCandles } = state;
        if (tradingCandles.length === 0) return state;
        
        // Find the latest timestamp from all candles
        const latestCandle = tradingCandles[tradingCandles.length - 1];
        
        return {
          tradingPlayback: {
            ...state.tradingPlayback,
            currentTime: latestCandle.timestamp,
            isPlaying: false, // Stop playing when we reach the end
          },
        };
      }),
      
      // Utility methods
      getVisiblePlanningCandles: () => {
        const { planningCandles, planningPlayback } = get();
        return planningCandles.filter(candle => candle.timestamp <= planningPlayback.currentTime);
      },
      
      getVisiblePlanningSwingPoints: () => {
        const { planningSwingPoints, planningPlayback } = get();
        return planningSwingPoints.filter(point => point.timestamp <= planningPlayback.currentTime);
      },
      
      getVisiblePlanningZones: () => {
        const { planningZones, planningPlayback } = get();
        return planningZones.filter(zone => {
          // Show zone if it's active at current time
          const zoneStart = zone.startTime;
          const zoneEnd = zone.endTime;
          const currentTime = planningPlayback.currentTime;
          
          return zoneStart <= currentTime;
        });
      },
      
      getVisiblePlanningDaytimeExtremes: () => {
        const { planningDaytimeExtremes, planningPlayback } = get();
        return planningDaytimeExtremes.filter(extreme => extreme.timestamp <= planningPlayback.currentTime);
      },
      
      // Trading utility methods
      getVisibleTradingCandles: () => {
        const { tradingCandles, tradingPlayback } = get();
        return tradingCandles.filter(candle => candle.timestamp <= tradingPlayback.currentTime);
      },
      
      getVisibleTradingSwingPoints: () => {
        const { tradingSwingPoints, tradingPlayback } = get();
        return tradingSwingPoints.filter(point => point.timestamp <= tradingPlayback.currentTime);
      },
      
      getVisibleTradingZones: () => {
        const { tradingZones, tradingPlayback } = get();
        return tradingZones.filter(zone => {
          // Show zone if it's active at current time
          const zoneStart = zone.startTime;
          const zoneEnd = zone.endTime;
          const currentTime = tradingPlayback.currentTime;
          
          return zoneStart <= currentTime && 
                 (zoneEnd === undefined || zoneEnd === null || zoneEnd >= currentTime);
        });
      },
      
      getVisibleTradingDaytimeExtremes: () => {
        const { tradingDaytimeExtremes, tradingPlayback } = get();
        return tradingDaytimeExtremes.filter(extreme => extreme.timestamp <= tradingPlayback.currentTime);
      },
      
      getVisibleTradingOrders: () => {
        const { tradingOrders, tradingPlayback } = get();
        return tradingOrders.filter(order => {
          // Show order if it has been planned (always show once created)
          // Orders should remain visible regardless of status to show final outcome
          return order.timestamp <= tradingPlayback.currentTime;
        });
      },
    }),
    {
      name: 'event-store',
    }
  )
);

// Helper function to reprocess all planning events
function reprocessPlanningEvents(state: EventStore): EventStore {
  const candles: ProcessedCandle[] = [];
  const swingPoints: ProcessedSwingPoint[] = [];
  const zones: ProcessedPlanZone[] = [];
  const daytimeExtremes: ProcessedDaytimeExtreme[] = [];
  
  // Process events in chronological order
  state.planningEvents.forEach((event, index) => {
    const eventType = extractEventType(event.eventType);
    
    switch (eventType) {
      case 'Candle':
        if (event.candle) {
          candles.push({
            time: Math.floor(event.timestamp / 1000), // TradingView format
            open: event.candle.open.value,
            high: event.candle.high.value,
            low: event.candle.low.value,
            close: event.candle.close.value,
            timestamp: event.timestamp,
          });
        }
        break;
        
      case 'SwingPoint':
        if (event.swingPoint) {
          const direction = extractDirection(event.swingPoint.direction);
          swingPoints.push({
            timestamp: event.timestamp,
            level: event.swingPoint.level.value,
            direction,
            id: `swing-${event.timestamp}-${index}`,
          });
        }
        break;
        
      case 'PlanZone':
        if (event.planZone) {
          const type = extractPlanZoneType(event.planZone.planZoneType);
          const existingZoneIndex = zones.findIndex(z => 
            z.startTime === event.planZone!.startTime && z.type === type
          );

          const newZone: ProcessedPlanZone = {
            timestamp: event.timestamp,
            startTime: event.planZone.startTime,
            endTime: event.planZone.endTime,
            low: event.planZone.low.value,
            high: event.planZone.high.value,
            type,
            id: `zone-${event.planZone.startTime}-${type}`,
            isActive: !event.planZone.endTime,
          };
          
          if (existingZoneIndex >= 0) {
            // Replace existing zone (out-of-order update)
            zones[existingZoneIndex] = newZone;
          } else {
            zones.push(newZone);
          }
        }
        break;
        
      case 'DaytimeExtreme':
        if (event.daytimeExtreme) {
          const extremeType = extractExtremeType(event.daytimeExtreme.extremeType);
          const description = event.daytimeExtreme.description;
          
          // Generate abbreviation from description (NY, L, A)
          let abbreviation = '';
          if (description.toLowerCase().includes('new york')) abbreviation = 'NY';
          else if (description.toLowerCase().includes('london')) abbreviation = 'L';
          else if (description.toLowerCase().includes('asia')) abbreviation = 'A';
          else abbreviation = description.charAt(0).toUpperCase();
          
          const existingExtremeIndex = daytimeExtremes.findIndex(e => 
            e.description === description
          );

          const newExtreme: ProcessedDaytimeExtreme = {
            timestamp: event.timestamp,
            endTime: event.daytimeExtreme.endTime,
            level: event.daytimeExtreme.level.value,
            extremeType,
            description,
            abbreviation,
            id: `extreme-${event.daytimeExtreme.timestamp}-${extremeType}`,
          };
          
          if (existingExtremeIndex >= 0) {
            // Replace existing extreme (out-of-order update)
            daytimeExtremes[existingExtremeIndex] = newExtreme;
          } else {
            daytimeExtremes.push(newExtreme);
          }
        }
        break;
    }
  });
  
  // Update playback duration
  const timestamps = state.planningEvents.map(e => e.timestamp);
  const startTime = timestamps.length > 0 ? Math.min(...timestamps) : 0;
  const endTime = timestamps.length > 0 ? Math.max(...timestamps) : 0;
  
  return {
    ...state,
    planningCandles: candles,
    planningSwingPoints: swingPoints,
    planningZones: zones,
    planningDaytimeExtremes: daytimeExtremes,
    planningPlayback: {
      ...state.planningPlayback,
      startTime,
      endTime,
      totalDuration: endTime - startTime,
      currentTime: state.planningPlayback.currentTime || startTime,
    },
  };
}

// Helper function to reprocess all trading events
function reprocessTradingEvents(state: EventStore): EventStore {
  const candles: ProcessedCandle[] = [];
  const swingPoints: ProcessedSwingPoint[] = [];
  const zones: ProcessedPlanZone[] = [];
  const daytimeExtremes: ProcessedDaytimeExtreme[] = [];
  const orders: ProcessedOrder[] = [];
  
  // Process events in chronological order
  state.tradingEvents.forEach((event, index) => {
    const eventType = extractEventType(event.eventType);
    
    switch (eventType) {
      case 'Candle':
        if (event.candle) {
          const candleTime = Math.floor(event.timestamp / 1000); // TradingView format
          const existingCandleIndex = candles.findIndex(c => c.time === candleTime);
          
          const newCandle: ProcessedCandle = {
            time: candleTime,
            open: event.candle.open.value,
            high: event.candle.high.value,
            low: event.candle.low.value,
            close: event.candle.close.value,
            timestamp: event.timestamp,
          };
          
          if (existingCandleIndex >= 0) {
            // Replace existing candle (out-of-order update or duplicate timestamp)
            candles[existingCandleIndex] = newCandle;
          } else {
            candles.push(newCandle);
          }
        }
        break;
        
      case 'SwingPoint':
        if (event.swingPoint) {
          const direction = extractDirection(event.swingPoint.direction);
          swingPoints.push({
            timestamp: event.timestamp,
            level: event.swingPoint.level.value,
            direction,
            id: `trading-swing-${event.timestamp}-${index}`,
          });
        }
        break;
        
      case 'PlanZone':
        if (event.planZone) {
          const type = extractPlanZoneType(event.planZone.planZoneType);
          const existingZoneIndex = zones.findIndex(z => 
            z.startTime === event.planZone!.startTime && z.type === type
          );

          const newZone: ProcessedPlanZone = {
            timestamp: event.timestamp,
            startTime: event.planZone.startTime,
            endTime: event.planZone.endTime,
            low: event.planZone.low.value,
            high: event.planZone.high.value,
            type,
            id: `trading-zone-${event.planZone.startTime}-${type}`,
            isActive: !event.planZone.endTime,
          };
          
          if (existingZoneIndex >= 0) {
            // Replace existing zone (out-of-order update)
            zones[existingZoneIndex] = newZone;
          } else {
            zones.push(newZone);
          }
        }
        break;
        
      case 'DaytimeExtreme':
        if (event.daytimeExtreme) {
          const extremeType = extractExtremeType(event.daytimeExtreme.extremeType);
          const description = event.daytimeExtreme.description;
          
          // Generate abbreviation from description (NY, L, A)
          let abbreviation = '';
          if (description.toLowerCase().includes('new york')) abbreviation = 'NY';
          else if (description.toLowerCase().includes('london')) abbreviation = 'L';
          else if (description.toLowerCase().includes('asia')) abbreviation = 'A';
          else abbreviation = description.charAt(0).toUpperCase();
          
          const existingExtremeIndex = daytimeExtremes.findIndex(e => 
            e.description === description
          );

          const newExtreme: ProcessedDaytimeExtreme = {
            timestamp: event.timestamp,
            endTime: event.daytimeExtreme.endTime,
            level: event.daytimeExtreme.level.value,
            extremeType,
            description,
            abbreviation,
            id: `trading-extreme-${event.daytimeExtreme.timestamp}-${extremeType}`,
          };
          
          if (existingExtremeIndex >= 0) {
            // Replace existing extreme (out-of-order update)
            daytimeExtremes[existingExtremeIndex] = newExtreme;
          } else {
            daytimeExtremes.push(newExtreme);
          }
        }
        break;
        
      case 'Order':
        if (event.order) {
          const orderType = extractOrderType(event.order.orderType);
          const status = extractOrderStatus(event.order.status);
          const entryType = extractEntryType(event.order.entryType);
          
          const existingOrderIndex = orders.findIndex(o => 
            o.timestamp === event.order!.timestamp && o.entryPoint === event.order!.entryPoint
          );

          const newOrder: ProcessedOrder = {
            timestamp: event.timestamp,
            low: event.order.low.value,
            high: event.order.high.value,
            orderType,
            entryType,
            status,
            profitMultiplier: event.order.profitMultiplier,
            riskDollars: event.order.riskDollars,
            placedTimestamp: event.order.placedTimestamp,
            filledTimestamp: event.order.filledTimestamp,
            closeTimestamp: event.order.closeTimestamp,
            entryPoint: event.order.entryPoint,
            stopLoss: event.order.stopLoss,
            takeProfit: event.order.takeProfit,
            contracts: event.order.contracts,
            atRisk: event.order.atRisk,
            potential: event.order.potential,
            id: `trading-order-${event.order.timestamp}-${orderType}`,
          };
          
          if (existingOrderIndex >= 0) {
            // Replace existing order (status update)
            orders[existingOrderIndex] = newOrder;
          } else {
            orders.push(newOrder);
          }
        }
        break;
    }
  });
  
  // Update playback duration
  const timestamps = state.tradingEvents.map(e => e.timestamp);
  const startTime = timestamps.length > 0 ? Math.min(...timestamps) : 0;
  const endTime = timestamps.length > 0 ? Math.max(...timestamps) : 0;
  
  // Sort candles by time to ensure proper ordering for TradingView
  const sortedCandles = candles.sort((a, b) => a.time - b.time);
  
  return {
    ...state,
    tradingCandles: sortedCandles,
    tradingSwingPoints: swingPoints,
    tradingZones: zones,
    tradingDaytimeExtremes: daytimeExtremes,
    tradingOrders: orders,
    tradingPlayback: {
      ...state.tradingPlayback,
      startTime,
      endTime,
      totalDuration: endTime - startTime,
      currentTime: state.tradingPlayback.currentTime || startTime,
    },
  };
}