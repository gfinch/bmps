// Event type definitions matching the Scala models

export interface Level {
  value: number;
}

export interface Line {
  level: number;
  startTime: number;
  endTime?: number;
}

export interface Range {
  low: Level;
  high: Level;
}

export interface Zone {
  low: Level;
  high: Level;
  startTime: number;
  endTime?: number;
}

export enum CandleDuration {
  OneMinute = 'OneMinute',
  TwoMinute = 'TwoMinute', 
  FiveMinute = 'FiveMinute',
  FifteenMinute = 'FifteenMinute',
  ThirtyMinute = 'ThirtyMinute',
  OneHour = 'OneHour',
  OneDay = 'OneDay'
}

export enum Direction {
  Up = 'Up',
  Down = 'Down',
  Doji = 'Doji'
}

export interface Candle {
  open: Level;
  high: Level;
  low: Level;
  close: Level;
  timestamp: number;
  duration: {
    [K in CandleDuration]?: {};
  };
}

export interface SwingPoint {
  level: Level;
  direction: {
    [K in Direction]?: {};
  };
  timestamp: number;
}

export enum PlanZoneType {
  Supply = 'Supply',
  Demand = 'Demand'
}

export interface PlanZone {
  planZoneType: {
    [K in PlanZoneType]?: {};
  };
  low: Level;
  high: Level;
  startTime: number;
  endTime?: number;
}

export enum ExtremeType {
  High = 'High',
  Low = 'Low'
}

export interface DaytimeExtreme {
  level: Level;
  extremeType: {
    [K in ExtremeType]?: {};
  };
  timestamp: number;
  endTime?: number;
  description: string;
}

export enum OrderStatus {
  Planned = 'Planned',
  Placed = 'Placed',
  Filled = 'Filled',
  Profit = 'Profit',
  Loss = 'Loss',
  Cancelled = 'Cancelled'
}

export enum OrderType {
  Long = 'Long',
  Short = 'Short'
}

export enum EntryType {
  EngulfingOrderBlock = 'EngulfingOrderBlock'
}

export interface SerializableOrder {
  low: Level;
  high: Level;
  timestamp: number;
  orderType: {
    [K in OrderType]?: {};
  };
  entryType: {
    [K in EntryType]?: {};
  };
  status: {
    [K in OrderStatus]?: {};
  };
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
}

export enum EventType {
  Candle = 'Candle',
  SwingPoint = 'SwingPoint',
  PlanZone = 'PlanZone',
  DaytimeExtreme = 'DaytimeExtreme',
  Order = 'Order',
  PhaseComplete = 'PhaseComplete',
  PhaseErrored = 'PhaseErrored'
}

export interface Event {
  eventType: {
    [K in EventType]?: {};
  };
  timestamp: number;
  candle?: Candle;
  swingPoint?: SwingPoint;
  planZone?: PlanZone;
  daytimeExtreme?: DaytimeExtreme;
  order?: SerializableOrder;
}

// WebSocket message structure based on Protocol.scala
export interface PhaseEvent {
  type: 'event';
  phase: string;
  event: Event;
}

export interface Lifecycle {
  type: 'lifecycle';
  phase: string;
  status: string;
}

export interface ErrorMessage {
  type: 'error';
  message: string;
}

export type ServerMessage = PhaseEvent | Lifecycle | ErrorMessage;

// Client command structure based on Protocol.scala
export interface StartPhase {
  command: 'startPhase';
  phase: string;
  options?: { [key: string]: string };
}

export interface SubscribePhase {
  command: 'subscribePhase';
  phase: string;
}

export interface Status {
  command: 'status';
}

export type ClientCommand = StartPhase | SubscribePhase | Status;

// Legacy interface for backward compatibility (remove if not needed)
export interface LegacyServerMessage {
  phase: string;
  event: Event;
}

// Utility functions to extract enum values from the Scala-style objects
export function extractEventType(eventType: Event['eventType']): EventType {
  return Object.keys(eventType)[0] as EventType;
}

export function extractDirection(direction: SwingPoint['direction']): Direction {
  return Object.keys(direction)[0] as Direction;
}

export function extractPlanZoneType(planZoneType: PlanZone['planZoneType']): PlanZoneType {
  return Object.keys(planZoneType)[0] as PlanZoneType;
}

export function extractCandleDuration(duration: Candle['duration']): CandleDuration {
  return Object.keys(duration)[0] as CandleDuration;
}

export function extractExtremeType(extremeType: DaytimeExtreme['extremeType']): ExtremeType {
  return Object.keys(extremeType)[0] as ExtremeType;
}

export function extractOrderType(orderType: SerializableOrder['orderType']): OrderType {
  return Object.keys(orderType)[0] as OrderType;
}

export function extractOrderStatus(status: SerializableOrder['status']): OrderStatus {
  return Object.keys(status)[0] as OrderStatus;
}

export function extractEntryType(entryType: SerializableOrder['entryType']): EntryType {
  return Object.keys(entryType)[0] as EntryType;
}