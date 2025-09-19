import { 
  ICustomSeriesPaneView, 
  ICustomSeriesPaneRenderer,
  PaneRendererCustomData,
  PriceToCoordinateConverter,
  Time,
  Coordinate,
  CustomData,
} from 'lightweight-charts';
import { CanvasRenderingTarget2D } from 'fancy-canvas';

// Interface for the actual extreme event data
export interface DaytimeExtremeEvent {
  session: string;
  level: number;
  startTime: number;
  endTime?: number;
}

// Interface for chart data points - must extend CustomData
export interface DaytimeExtremeData extends CustomData<Time> {
  extreme: DaytimeExtremeEvent;
}

// Options interface
interface DaytimeExtremeSeriesOptions {
  lineColor?: string;
  lineWidth?: number;
  labelColor?: string;
  labelSize?: number;
}

const DEFAULT_EXTREME_OPTIONS: Required<DaytimeExtremeSeriesOptions> = {
  lineColor: '#000000',
  lineWidth: 3,
  labelColor: '#000000',
  labelSize: 14,
};

class DaytimeExtremeRenderer implements ICustomSeriesPaneRenderer {
  private _data: any | null = null;
  private _options: Required<DaytimeExtremeSeriesOptions>;

  constructor(options: DaytimeExtremeSeriesOptions = {}) {
    this._options = {
      ...DEFAULT_EXTREME_OPTIONS,
      ...options,
    };
  }

  update(data: any, options: any): void {
    this._data = data;
    this._options = { ...this._options, ...options };
  }

  draw(target: CanvasRenderingTarget2D, priceToCoordinate: PriceToCoordinateConverter): void {
    if (!this._data || !this._data.bars.length) return;

    target.useMediaCoordinateSpace((scope) => {
      const ctx = scope.context;
      
      ctx.save();
      
      try {
        this._data!.bars.forEach((bar: any) => {
          const extreme = bar.originalData.extreme;
          
          try {
            // Get start X coordinate from the bar's time coordinate
            const startX = bar.x;
            
            // Get Y coordinate for the level
            const y = priceToCoordinate(extreme.level);
            if (y === null) return;
            
            // Determine end X coordinate
            let endX: number;
            if (extreme.endTime) {
              // Find the bar with the corresponding end time or use approximation
              const endTimeSeconds = Math.floor(extreme.endTime / 1000) as Time;
              const endBar = this._data!.bars.find((b: any) => b.originalData.time >= endTimeSeconds);
              endX = endBar ? endBar.x : this._data!.bars[this._data!.bars.length - 1].x + this._data!.barSpacing;
            } else {
              // Extend to the right edge of the chart for infinite lines
              endX = scope.mediaSize.width;
            }
            
            // Draw the horizontal line
            ctx.strokeStyle = this._options.lineColor;
            ctx.lineWidth = this._options.lineWidth;
            ctx.beginPath();
            ctx.moveTo(startX, y);
            ctx.lineTo(endX, y);
            ctx.stroke();
            
            // Get session abbreviation
            const abbreviation = this.getSessionAbbreviation(extreme.session);
            
            // Draw the label to the left of the start point (following old app pattern)
            const labelX = Math.max(4, startX - 6); // 6px left of start, minimum 4px from edge
            ctx.fillStyle = this._options.labelColor;
            ctx.font = `bold ${this._options.labelSize}px Arial`;
            ctx.textAlign = 'right'; // Right align since we're positioning to the left
            ctx.textBaseline = 'middle';
            ctx.fillText(abbreviation, labelX, y);
          } catch (error) {
            console.error('Error drawing daytime extreme:', error);
          }
        });
      } finally {
        ctx.restore();
      }
    });
  }

  private getSessionAbbreviation(session: string): string {
    const sessionLower = session.toLowerCase();
    if (sessionLower.includes('london')) return 'L';
    if (sessionLower.includes('asia') || sessionLower.includes('asian')) return 'A';
    if (sessionLower.includes('new york') || sessionLower.includes('newyork') || session.toUpperCase().includes('NY')) return 'NY';
    return session.charAt(0).toUpperCase();
  }
}

  export class DaytimeExtremeSeries implements ICustomSeriesPaneView {
  private _renderer: DaytimeExtremeRenderer;
  private _options: Required<DaytimeExtremeSeriesOptions>;

  constructor(options: DaytimeExtremeSeriesOptions = {}) {
    this._options = {
      ...DEFAULT_EXTREME_OPTIONS,
      ...options,
    };
    this._renderer = new DaytimeExtremeRenderer(this._options);
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
    if (!plotRow.extreme) return [];
    return [plotRow.extreme.level];
  }

  isWhitespace(data: any): data is any {
    return !data || !('extreme' in data) || !data.extreme;
  }

  defaultOptions(): any {
    return DEFAULT_EXTREME_OPTIONS;
  }

  destroy(): void {
    // Cleanup if needed
  }
}

// Helper function to convert DaytimeExtremeEvent to chart data
export function createDaytimeExtremeData(extreme: DaytimeExtremeEvent): DaytimeExtremeData {
  return {
    time: Math.floor(extreme.startTime / 1000) as Time, // Convert to TradingView time format (seconds)
    extreme: extreme,
  };
}

