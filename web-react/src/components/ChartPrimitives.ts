// Chart Primitives - Time-aware drawing components for TradingView Lightweight Charts
// Handles time-to-coordinate conversion and chart zoom scaling centrally

import { Time, CustomBarItemData, PriceToCoordinateConverter } from 'lightweight-charts';

// Context for time/price coordinate conversion
export interface ChartContext {
  bars: readonly CustomBarItemData<Time, any>[];
  priceToCoordinate: PriceToCoordinateConverter;
  scope: any; // TradingView scope with mediaSize, etc.
}

// Time-aware line configuration
export interface TimeLineConfig {
  value: number;           // Price level for the line
  startTime: number;       // Unix timestamp (milliseconds)
  endTime?: number;        // Unix timestamp (undefined = infinite to right edge)
  color: string;           // Line color
  width: number;           // Line width in pixels
}

// Time-aware box configuration  
export interface TimeBoxConfig {
  topValue: number;        // Top price level
  bottomValue: number;     // Bottom price level
  startTime: number;       // Unix timestamp (milliseconds)
  endTime?: number;        // Unix timestamp (undefined = infinite to right edge)
  fillColor: string;       // Interior fill color
  strokeColor?: string;    // Default edge color for all sides
  topStrokeColor?: string; // Override for top edge
  bottomStrokeColor?: string; // Override for bottom edge  
  leftStrokeColor?: string;   // Override for left edge
  rightStrokeColor?: string;  // Override for right edge
  strokeWidth?: number;    // Edge line width (default: 1)
}

/**
 * Convert Unix timestamp (milliseconds) to chart X coordinate
 * Uses the chart's bar data to find the appropriate X position
 */
export function timestampToX(timestamp: number, context: ChartContext): number | null {
  if (!context.bars.length) return null;
  
  const targetTimeSeconds = Math.floor(timestamp / 1000);
  
  console.log('timestampToX:', {
    timestamp,
    targetTimeSeconds,
    barsCount: context.bars.length,
    firstBarTime: context.bars[0]?.time,
    lastBarTime: context.bars[context.bars.length - 1]?.time,
    firstBar: context.bars[0],
    lastBar: context.bars[context.bars.length - 1]
  });
  
  // Find the bar that contains or is closest to this timestamp
  const bar = context.bars.find((b: any, index: number) => {
    const nextBar = context.bars[index + 1];
    const currentTime = b.time as number;
    const nextTime = nextBar?.time ? (nextBar.time as number) : Infinity;
    return targetTimeSeconds >= currentTime && targetTimeSeconds < nextTime;
  });
  
  if (bar) {
    console.log('Found exact bar at X:', bar.x);
    return bar.x;
  }
  
  // Fallback: interpolate beyond available data
  const lastBar = context.bars[context.bars.length - 1];
  const secondLastBar = context.bars[context.bars.length - 2];
  
  if (lastBar && secondLastBar) {
    const timeDiff = targetTimeSeconds - (lastBar.time as number);
    const barTimeDiff = (lastBar.time as number) - (secondLastBar.time as number);
    const barPixelDiff = lastBar.x - secondLastBar.x;
    
    console.log('Interpolating:', {
      timeDiff,
      barTimeDiff,
      barPixelDiff,
      pixelsPerSecond: barTimeDiff > 0 ? barPixelDiff / barTimeDiff : 0
    });
    
    if (barTimeDiff > 0) {
      const pixelsPerSecond = barPixelDiff / barTimeDiff;
      const result = lastBar.x + (timeDiff * pixelsPerSecond);
      console.log('Interpolated X:', result);
      return result;
    }
  }
  
  // Final fallback: use last bar position
  console.log('Using fallback X:', lastBar ? lastBar.x : null);
  return lastBar ? lastBar.x : null;
}

/**
 * Draw a time-aware horizontal line
 * Handles time-to-coordinate conversion and proper scaling
 */
export function drawTimeLine(ctx: CanvasRenderingContext2D, config: TimeLineConfig, context: ChartContext): void {
  console.log('drawTimeLine called:', {
    value: config.value,
    startTime: config.startTime,
    endTime: config.endTime,
    color: config.color
  });
  
  const startX = timestampToX(config.startTime, context);
  console.log('startX calculated:', startX);
  
  if (startX === null) {
    console.log('startX is null, returning');
    return;
  }
  
  let endX: number;
  if (config.endTime !== undefined) {
    const calculatedEndX = timestampToX(config.endTime, context);
    endX = calculatedEndX !== null ? calculatedEndX : context.scope.mediaSize.width;
  } else {
    // Infinite line - extend to right edge
    endX = context.scope.mediaSize.width;
  }
  
  const y = context.priceToCoordinate(config.value);
  console.log('y coordinate:', y);
  
  if (y === null) {
    console.log('y is null, returning');
    return;
  }
  
  console.log('Drawing line from', startX, y, 'to', endX, y);
  
  // Draw the line
  ctx.strokeStyle = config.color;
  ctx.lineWidth = config.width;
  ctx.beginPath();
  ctx.moveTo(startX, y);
  ctx.lineTo(endX, y);
  ctx.stroke();
  
  console.log('Line drawn successfully');
}

/**
 * Draw a time-aware rectangular box with flexible edge colors
 * Handles time-to-coordinate conversion and proper scaling
 */
export function drawTimeBox(ctx: CanvasRenderingContext2D, config: TimeBoxConfig, context: ChartContext): void {
  const startX = timestampToX(config.startTime, context);
  if (startX === null) return;
  
  let endX: number;
  if (config.endTime !== undefined) {
    const calculatedEndX = timestampToX(config.endTime, context);
    endX = calculatedEndX !== null ? calculatedEndX : context.scope.mediaSize.width;
  } else {
    // Infinite box - extend to right edge
    endX = context.scope.mediaSize.width;
  }
  
  const topY = context.priceToCoordinate(config.topValue);
  const bottomY = context.priceToCoordinate(config.bottomValue);
  
  if (topY === null || bottomY === null) return;
  
  // Ensure proper ordering (top should be above bottom on screen)
  const actualTopY = Math.min(topY, bottomY);
  const actualBottomY = Math.max(topY, bottomY);
  
  const width = endX - startX;
  const height = actualBottomY - actualTopY;
  
  if (width <= 0 || height <= 0) return;
  
  // Draw filled rectangle
  ctx.fillStyle = config.fillColor;
  ctx.fillRect(startX, actualTopY, width, height);
  
  // Draw edges with potentially different colors
  const strokeWidth = config.strokeWidth || 1;
  const defaultStroke = config.strokeColor || '#000000';
  
  ctx.lineWidth = strokeWidth;
  
  // Top edge
  ctx.strokeStyle = config.topStrokeColor || defaultStroke;
  ctx.beginPath();
  ctx.moveTo(startX, actualTopY);
  ctx.lineTo(endX, actualTopY);
  ctx.stroke();
  
  // Bottom edge  
  ctx.strokeStyle = config.bottomStrokeColor || defaultStroke;
  ctx.beginPath();
  ctx.moveTo(startX, actualBottomY);
  ctx.lineTo(endX, actualBottomY);
  ctx.stroke();
  
  // Left edge
  ctx.strokeStyle = config.leftStrokeColor || defaultStroke;
  ctx.beginPath();
  ctx.moveTo(startX, actualTopY);
  ctx.lineTo(startX, actualBottomY);
  ctx.stroke();
  
  // Right edge (only if box has finite width)
  if (config.endTime !== undefined) {
    ctx.strokeStyle = config.rightStrokeColor || defaultStroke;
    ctx.beginPath(); 
    ctx.moveTo(endX, actualTopY);
    ctx.lineTo(endX, actualBottomY);
    ctx.stroke();
  }
}