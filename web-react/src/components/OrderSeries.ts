// OrderSeries Custom Series for Trading View Lightweight Charts
// Renders orders with different visualizations based on order status

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
import { ProcessedOrder } from '../stores/eventStore';
import { ChartContext, drawTimeLine, drawTimeBox } from './ChartPrimitives';

interface OrderData extends CustomData {
  order: ProcessedOrder;
}

interface OrderColors {
  plannedLine: string;
  placedLine: string;
  filledLine: string;
  profitBox: { fill: string; stroke: string; };
  lossBox: { fill: string; stroke: string; };
  greyBox: { fill: string; stroke: string; };
  cancelledLine: string;
}

const DEFAULT_ORDER_COLORS: OrderColors = {
  plannedLine: '#2563eb',      // Blue
  placedLine: '#7c3aed',       // Purple
  filledLine: '#7c3aed',       // Purple
  profitBox: {
    fill: 'rgba(34, 197, 94, 0.15)',   // Green with transparency
    stroke: '#22c55e'
  },
  lossBox: {
    fill: 'rgba(239, 68, 68, 0.15)',   // Red with transparency
    stroke: '#ef4444'
  },
  greyBox: {
    fill: 'rgba(107, 114, 128, 0.12)', // Grey with transparency
    stroke: '#6b7280'
  },
  cancelledLine: '#6b7280'     // Grey
};

interface OrderSeriesOptions {
  orderColors?: Partial<OrderColors>;
}

class OrderRenderer implements ICustomSeriesPaneRenderer {
  private _data: PaneRendererCustomData<Time, OrderData> | null = null;
  private _options: OrderSeriesOptions;

  constructor(options: OrderSeriesOptions = {}) {
    this._options = {
      orderColors: DEFAULT_ORDER_COLORS,
      ...options,
    };
  }

  update(data: PaneRendererCustomData<Time, OrderData>, options: OrderSeriesOptions): void {
    this._data = data;
    this._options = { ...this._options, ...options };
  }

  draw(target: CanvasRenderingTarget2D, priceToCoordinate: PriceToCoordinateConverter): void {
    if (!this._data || !this._data.bars.length) return;

    const colors = { ...DEFAULT_ORDER_COLORS, ...this._options.orderColors };

    target.useMediaCoordinateSpace((scope) => {
      const ctx = scope.context;
      
      // Save context state
      ctx.save();
      
      try {
        this._data!.bars.forEach((barItem: CustomBarItemData<Time, OrderData>) => {
          if (!barItem.originalData || !barItem.originalData.order) return;

          const order = barItem.originalData.order;
          this._drawOrder(ctx, barItem, order, priceToCoordinate, colors, scope);
        });
      } finally {
        // Always restore context state
        ctx.restore();
      }
    });
  }

  private _drawOrder(
    ctx: CanvasRenderingContext2D,
    barItem: CustomBarItemData<Time, OrderData>,
    order: ProcessedOrder,
    priceToCoordinate: PriceToCoordinateConverter,
    colors: OrderColors,
    scope: any
  ): void {
    console.log('Drawing order:', order.status, order.timestamp);
    
    // Create chart context for primitives
    const context: ChartContext = {
      bars: this._data?.bars || [],
      priceToCoordinate,
      scope
    };
    
    console.log('Chart context:', {
      barsCount: context.bars.length,
      hasScope: !!context.scope,
      hasPriceConverter: !!context.priceToCoordinate
    });
    
    // Define order price levels based on direction
    const isLong = order.orderType === 'Long';
    const profitLevel = order.takeProfit;
    const lossLevel = order.stopLoss;
    
    // For Long orders: profit is above entry, loss is below
    // For Short orders: profit is below entry, loss is above
    const profitBoxTop = isLong ? Math.max(order.entryPoint, profitLevel) : Math.max(order.entryPoint, lossLevel);
    const profitBoxBottom = isLong ? Math.min(order.entryPoint, profitLevel) : Math.min(order.entryPoint, profitLevel);
    const lossBoxTop = isLong ? Math.max(order.entryPoint, lossLevel) : Math.max(order.entryPoint, profitLevel);
    const lossBoxBottom = isLong ? Math.min(order.entryPoint, lossLevel) : Math.min(order.entryPoint, lossLevel);
    
    console.log('About to draw for status:', order.status);
    
    switch (order.status) {
      case 'Planned':
        // Blue line from order creation infinitely to the future
        console.log('Drawing planned line');
        drawTimeLine(ctx, {
          value: order.entryPoint,
          startTime: order.timestamp,
          color: colors.plannedLine,
          width: 3
        }, context);
        break;
        
      case 'Placed':
        // Blue line from creation to placed, then purple line infinitely forward
        drawTimeLine(ctx, {
          value: order.entryPoint,
          startTime: order.timestamp,
          endTime: order.placedTimestamp,
          color: colors.plannedLine,
          width: 3
        }, context);
        
        if (order.placedTimestamp) {
          drawTimeLine(ctx, {
            value: order.entryPoint,
            startTime: order.placedTimestamp,
            color: colors.placedLine,
            width: 3
          }, context);
        }
        break;
        
      case 'Filled':
        // Lines: planned -> placed -> filled
        drawTimeLine(ctx, {
          value: order.entryPoint,
          startTime: order.timestamp,
          endTime: order.placedTimestamp,
          color: colors.plannedLine,
          width: 3
        }, context);
        
        if (order.placedTimestamp && order.filledTimestamp) {
          drawTimeLine(ctx, {
            value: order.entryPoint,
            startTime: order.placedTimestamp,
            endTime: order.filledTimestamp,
            color: colors.filledLine,
            width: 3
          }, context);
          
          // Infinite profit and loss boxes from fill time
          drawTimeBox(ctx, {
            topValue: profitBoxTop,
            bottomValue: profitBoxBottom,
            startTime: order.filledTimestamp,
            fillColor: colors.profitBox.fill,
            strokeColor: colors.profitBox.stroke
          }, context);
          
          drawTimeBox(ctx, {
            topValue: lossBoxTop,
            bottomValue: lossBoxBottom,
            startTime: order.filledTimestamp,
            fillColor: colors.lossBox.fill,
            strokeColor: colors.lossBox.stroke
          }, context);
        }
        break;
        
      case 'Profit':
        // Lines: planned -> placed -> filled
        drawTimeLine(ctx, {
          value: order.entryPoint,
          startTime: order.timestamp,
          endTime: order.placedTimestamp,
          color: colors.plannedLine,
          width: 3
        }, context);
        
        if (order.placedTimestamp && order.filledTimestamp) {
          drawTimeLine(ctx, {
            value: order.entryPoint,
            startTime: order.placedTimestamp,
            endTime: order.filledTimestamp,
            color: colors.filledLine,
            width: 3
          }, context);
          
          if (order.closeTimestamp) {
            // Finite boxes: filled -> closed
            // Winner: profit box (green), Loser: loss box (grey)
            drawTimeBox(ctx, {
              topValue: profitBoxTop,
              bottomValue: profitBoxBottom,
              startTime: order.filledTimestamp,
              endTime: order.closeTimestamp,
              fillColor: colors.profitBox.fill,
              strokeColor: colors.profitBox.stroke
            }, context);
            
            drawTimeBox(ctx, {
              topValue: lossBoxTop,
              bottomValue: lossBoxBottom,
              startTime: order.filledTimestamp,
              endTime: order.closeTimestamp,
              fillColor: colors.greyBox.fill,
              strokeColor: colors.greyBox.stroke
            }, context);
          }
        }
        break;
        
      case 'Loss':
        // Lines: planned -> placed -> filled
        drawTimeLine(ctx, {
          value: order.entryPoint,
          startTime: order.timestamp,
          endTime: order.placedTimestamp,
          color: colors.plannedLine,
          width: 3
        }, context);
        
        if (order.placedTimestamp && order.filledTimestamp) {
          drawTimeLine(ctx, {
            value: order.entryPoint,
            startTime: order.placedTimestamp,
            endTime: order.filledTimestamp,
            color: colors.filledLine,
            width: 3
          }, context);
          
          if (order.closeTimestamp) {
            // Finite boxes: filled -> closed
            // Winner: loss box (red), Loser: profit box (grey)
            drawTimeBox(ctx, {
              topValue: profitBoxTop,
              bottomValue: profitBoxBottom,
              startTime: order.filledTimestamp,
              endTime: order.closeTimestamp,
              fillColor: colors.greyBox.fill,
              strokeColor: colors.greyBox.stroke
            }, context);
            
            drawTimeBox(ctx, {
              topValue: lossBoxTop,
              bottomValue: lossBoxBottom,
              startTime: order.filledTimestamp,
              endTime: order.closeTimestamp,
              fillColor: colors.lossBox.fill,
              strokeColor: colors.lossBox.stroke
            }, context);
          }
        }
        break;
        
      case 'Cancelled':
        // Grey line from creation to cancellation
        if (order.closeTimestamp) {
          drawTimeLine(ctx, {
            value: order.entryPoint,
            startTime: order.timestamp,
            endTime: order.closeTimestamp,
            color: colors.cancelledLine,
            width: 3
          }, context);
        }
        break;
    }
  }
}

export class OrderSeries implements ICustomSeriesPaneView {
  private _renderer: OrderRenderer;
  private _options: OrderSeriesOptions;

  constructor(options: OrderSeriesOptions = {}) {
    this._options = options;
    this._renderer = new OrderRenderer(options);
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
    if (!plotRow.order) return [];
    
    // Return key price levels for crosshair positioning
    const { entryPoint, takeProfit, stopLoss } = plotRow.order;
    return [takeProfit, entryPoint, stopLoss];
  }

  isWhitespace(data: any): data is any {
    return !('order' in data) || !data.order;
  }

  defaultOptions(): any {
    return {
      orderColors: DEFAULT_ORDER_COLORS,
    };
  }
}

// Helper function to create OrderData from ProcessedOrder
export function createOrderData(order: ProcessedOrder): OrderData {
  return {
    time: Math.floor(order.timestamp / 1000) as Time,
    order: order,
  };
}