// Zustand store for event buffer management

import { create } from 'zustand';
import { devtools } from 'zustand/middleware';
import { 
  PhaseEvent, 
  Event, 
  Candle, 
  SwingPoint, 
  PlanZone, 
  extractEventType,
  extractCandleDuration,
  CandleDuration 
} from '../types/events';
import { ConnectionStatus } from '../services/websocket';

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
  id: string; // Unique identifier for chart rectangles
  isActive: boolean;
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
  
  // Raw events buffer (separate for Planning and Trading)
  planningEvents: Event[];
  tradingEvents: Event[];
  
  // Processed data for charts
  planningCandles: ProcessedCandle[];
  planningSwingPoints: ProcessedSwingPoint[];
  planningZones: ProcessedPlanZone[];
  
  tradingCandles: ProcessedCandle[];
  // Trading events will be added later
  
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
  
  // Utility methods
  getVisiblePlanningCandles: () => ProcessedCandle[];
  getVisiblePlanningSwingPoints: () => ProcessedSwingPoint[];
  getVisiblePlanningZones: () => ProcessedPlanZone[];
}

const createInitialPlaybackState = (): PlaybackState => ({
  currentTime: 0,
  isPlaying: false,
  playbackSpeed: 1,
  totalDuration: 0,
  startTime: 0,
  endTime: 0,
});

// Helper function to determine candle duration step interval in milliseconds
function getCandleDurationMs(duration: CandleDuration): number {
  switch (duration) {
    case CandleDuration.OneMinute: return 60 * 1000;
    case CandleDuration.TwoMinute: return 2 * 60 * 1000;
    case CandleDuration.FiveMinute: return 5 * 60 * 1000;
    case CandleDuration.FifteenMinute: return 15 * 60 * 1000;
    case CandleDuration.ThirtyMinute: return 30 * 60 * 1000;
    case CandleDuration.OneHour: return 60 * 60 * 1000;
    case CandleDuration.OneDay: return 24 * 60 * 60 * 1000;
    default: return 60 * 60 * 1000; // Default to 1 hour
  }
}

export const useEventStore = create<EventStore>()(
  devtools(
    (set, get) => ({
      // Initial state
      connectionStatus: 'disconnected',
      isConnected: false,
      
      planningEvents: [],
      tradingEvents: [],
      
      planningCandles: [],
      planningSwingPoints: [],
      planningZones: [],
      
      tradingCandles: [],
      
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
        } else {
          // Handle Trading events later
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
            planningPlayback: createInitialPlaybackState(),
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
        return planningZones.filter(zone => zone.timestamp <= planningPlayback.currentTime);
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
          const direction = Object.keys(event.swingPoint.direction)[0] as 'Up' | 'Down' | 'Doji';
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
          const type = Object.keys(event.planZone.planZoneType)[0] as 'Supply' | 'Demand';
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
    planningPlayback: {
      ...state.planningPlayback,
      startTime,
      endTime,
      totalDuration: endTime - startTime,
      currentTime: state.planningPlayback.currentTime || startTime,
    },
  };
}