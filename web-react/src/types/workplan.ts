// WorkPlan types for Supply and Demand zones

import { Time, CustomData } from 'lightweight-charts';

export enum WorkPlanType {
  Supply = 'Supply',   // Bearish/Short zones
  Demand = 'Demand'    // Bullish/Long zones
}

export interface WorkPlanEvent {
  type: WorkPlanType;
  startTime: number;    // Unix timestamp in milliseconds
  endTime?: number;     // Optional end timestamp - undefined means infinite
  high: number;         // High price level
  low: number;          // Low price level
  id: string;          // Unique identifier
}

export interface ProcessedWorkPlan {
  id: string;
  type: WorkPlanType;
  startTime: number;
  endTime?: number;
  high: number;
  low: number;
  isActive: boolean;    // True if endTime is undefined (infinite zone)
  isEnded: boolean;     // True if endTime is set (gray shading)
}

// Data structure for the custom series - must extend CustomData
export interface WorkPlanData extends CustomData<Time> {
  time: Time;          // TradingView time format
  workPlan: WorkPlanEvent;
}

// Colors configuration for rendering
export interface WorkPlanColors {
  supply: {
    fill: string;        // Red shading for Supply zones
    topLine: string;     // Red top line
    bottomLine: string;  // Green bottom line
    sides: string;       // Black sides
  };
  demand: {
    fill: string;        // Green shading for Demand zones
    topLine: string;     // Green top line
    bottomLine: string;  // Red bottom line
    sides: string;       // Black sides
  };
  ended: {
    fill: string;        // Gray shading for ended zones
  };
}

export const DEFAULT_WORKPLAN_COLORS: WorkPlanColors = {
  supply: {
    fill: 'rgba(255, 0, 0, 0.2)',      // Red with 20% opacity
    topLine: '#ff0000',                 // Red
    bottomLine: '#00ff00',              // Green
    sides: '#000000',                   // Black
  },
  demand: {
    fill: 'rgba(0, 255, 0, 0.2)',      // Green with 20% opacity
    topLine: '#00ff00',                 // Green
    bottomLine: '#ff0000',              // Red
    sides: '#000000',                   // Black
  },
  ended: {
    fill: 'rgba(128, 128, 128, 0.2)',  // Gray with 20% opacity
  },
};