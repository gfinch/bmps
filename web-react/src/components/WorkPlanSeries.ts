// WorkPlan Custom Series for Trading View Lightweight Charts
// Renders Supply and Demand zones with proper coloring and shading

import { 
  ICustomSeriesPaneView, 
  ICustomSeriesPaneRenderer,
  PaneRendererCustomData,
  CustomBarItemData,
  PriceToCoordinateConverter,
  CustomData,
  Time,
  CustomSeriesOptions,
  CustomSeriesPricePlotValues,
  CustomSeriesWhitespaceData,
  Coordinate,
} from 'lightweight-charts';
import { CanvasRenderingTarget2D } from 'fancy-canvas';
import { 
  WorkPlanEvent, 
  WorkPlanData, 
  WorkPlanType, 
  WorkPlanColors, 
  DEFAULT_WORKPLAN_COLORS 
} from '../types/workplan';

interface WorkPlanSeriesOptions {
  workPlanColors?: Partial<WorkPlanColors>;
}

class WorkPlanRenderer implements ICustomSeriesPaneRenderer {
  private _data: PaneRendererCustomData<Time, WorkPlanData> | null = null;
  private _options: WorkPlanSeriesOptions;

  constructor(options: WorkPlanSeriesOptions = {}) {
    this._options = {
      workPlanColors: DEFAULT_WORKPLAN_COLORS,
      ...options,
    };
  }

  update(data: PaneRendererCustomData<Time, WorkPlanData>, options: WorkPlanSeriesOptions): void {
    this._data = data;
    this._options = { ...this._options, ...options };
  }

  draw(target: CanvasRenderingTarget2D, priceToCoordinate: PriceToCoordinateConverter): void {
    if (!this._data || !this._data.bars.length) return;

    const colors = { ...DEFAULT_WORKPLAN_COLORS, ...this._options.workPlanColors };

    target.useMediaCoordinateSpace((scope) => {
      const ctx = scope.context;
      
      // Save context state
      ctx.save();
      
      try {
        this._data!.bars.forEach((barItem: CustomBarItemData<Time, WorkPlanData>) => {
          if (!barItem.originalData || !barItem.originalData.workPlan) return;

          const workPlan = barItem.originalData.workPlan;
          this._drawWorkPlanZone(ctx, barItem, workPlan, priceToCoordinate, colors, scope);
        });
      } finally {
        // Always restore context state
        ctx.restore();
      }
    });
  }

  private _drawWorkPlanZone(
    ctx: CanvasRenderingContext2D,
    barItem: CustomBarItemData<Time, WorkPlanData>,
    workPlan: WorkPlanEvent,
    priceToCoordinate: PriceToCoordinateConverter,
    colors: WorkPlanColors,
    scope: any
  ): void {
    // Calculate coordinates
    const startX = barItem.x;
    
    // Calculate end X coordinate
    let endX: number;
    if (workPlan.endTime) {
      // Zone has an end time - calculate the end position
      // For now, we'll extend it by a fixed width (this could be improved)
      // In a real implementation, you'd need access to the time scale to convert endTime to X coordinate
      endX = startX + 100; // Placeholder: should be calculated based on endTime
    } else {
      // Infinite zone - extend to the right edge of the visible area
      endX = scope.mediaSize.width;
    }

    const highY = priceToCoordinate(workPlan.high);
    const lowY = priceToCoordinate(workPlan.low);

    // Handle null coordinates
    if (highY === null || lowY === null) return;

    // Ensure proper ordering (high should be above low on screen)
    const topY = Math.min(highY, lowY);
    const bottomY = Math.max(highY, lowY);
    const width = endX - startX;
    const height = bottomY - topY;

    // Choose colors based on zone type and status
    let fillColor: string;
    let topLineColor: string;
    let bottomLineColor: string;
    const sideColor = colors.supply.sides; // Both use black sides

    if (workPlan.endTime) {
      // Zone is ended - use gray shading but keep line colors
      fillColor = colors.ended.fill;
      if (workPlan.type === WorkPlanType.Supply) {
        topLineColor = colors.supply.topLine;
        bottomLineColor = colors.supply.bottomLine;
      } else {
        topLineColor = colors.demand.topLine;
        bottomLineColor = colors.demand.bottomLine;
      }
    } else {
      // Active zone - use type-specific colors
      if (workPlan.type === WorkPlanType.Supply) {
        fillColor = colors.supply.fill;
        topLineColor = colors.supply.topLine;    // Red top line
        bottomLineColor = colors.supply.bottomLine; // Green bottom line
      } else { // Demand
        fillColor = colors.demand.fill;
        topLineColor = colors.demand.topLine;    // Green top line
        bottomLineColor = colors.demand.bottomLine; // Red bottom line
      }
    }

    // Draw the filled rectangle (zone area)
    ctx.fillStyle = fillColor;
    ctx.fillRect(startX, topY, width, height);

    // Draw the zone borders
    ctx.lineWidth = 1;

    // Top line
    ctx.strokeStyle = topLineColor;
    ctx.beginPath();
    ctx.moveTo(startX, topY);
    ctx.lineTo(endX, topY);
    ctx.stroke();

    // Bottom line
    ctx.strokeStyle = bottomLineColor;
    ctx.beginPath();
    ctx.moveTo(startX, bottomY);
    ctx.lineTo(endX, bottomY);
    ctx.stroke();

    // Side lines (black)
    ctx.strokeStyle = sideColor;
    
    // Left side
    ctx.beginPath();
    ctx.moveTo(startX, topY);
    ctx.lineTo(startX, bottomY);
    ctx.stroke();

    // Right side (only if zone has an end time)
    if (workPlan.endTime) {
      ctx.beginPath();
      ctx.moveTo(endX, topY);
      ctx.lineTo(endX, bottomY);
      ctx.stroke();
    }
  }
}

export class WorkPlanSeries implements ICustomSeriesPaneView {
  private _renderer: WorkPlanRenderer;
  private _options: WorkPlanSeriesOptions;

  constructor(options: WorkPlanSeriesOptions = {}) {
    this._options = options;
    this._renderer = new WorkPlanRenderer(options);
  }

  renderer(): ICustomSeriesPaneRenderer {
    return this._renderer;
  }

  update(
    data: any,
    seriesOptions: any
  ): void {
    this._renderer.update(data, { ...this._options, ...seriesOptions });
  }

  priceValueBuilder(plotRow: any): number[] {
    if (!plotRow.workPlan) return [];
    
    // Return [high, low, current] where current could be middle for crosshair positioning
    const { high, low } = plotRow.workPlan;
    const middle = (high + low) / 2;
    return [high, low, middle];
  }

  isWhitespace(data: any): data is any {
    return !('workPlan' in data) || !data.workPlan;
  }

  defaultOptions(): any {
    return {
      workPlanColors: DEFAULT_WORKPLAN_COLORS,
    };
  }

  destroy(): void {
    // Cleanup if needed - currently no resources to clean up
  }
}

// Helper function to convert WorkPlanEvent to chart data
export function createWorkPlanData(workPlan: WorkPlanEvent): WorkPlanData {
  return {
    time: Math.floor(workPlan.startTime / 1000) as Time, // Convert to TradingView time format (seconds)
    workPlan: workPlan,
  };
}

// Helper function to update an existing WorkPlan (e.g., adding an end time)
export function updateWorkPlanData(
  existingData: WorkPlanData, 
  updates: Partial<WorkPlanEvent>
): WorkPlanData {
  return {
    ...existingData,
    workPlan: {
      ...existingData.workPlan,
      ...updates,
    },
  };
}