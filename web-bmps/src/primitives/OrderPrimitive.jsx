/**
 * Order Series Primitive for TradingView Lightweight Charts
 * Draws orders with different visualizations based on order status:
 * - Planned: Purple line to infinity
 * - Placed: Purple line to placed time, then blue line to infinity  
 * - Filled: Lines as above + profit/loss boxes to infinity
 * - Profit/Loss: Lines + finite boxes, with winner/loser coloring
 * - Cancelled: Grey line from start to close
 */

import { 
  drawHorizontalLine, 
  drawRectangle,
  drawLabel, 
  getTimeCoordinate, 
  getPriceCoordinate 
} from '../utils/drawingUtils.js'

/**
 * TradingView Series Primitive for drawing orders
 */
class OrderPrimitive {
  constructor(options = {}) {
    this.options = {
      plannedColor: '#7C3AED',    // Purple for planned state
      placedColor: '#2563EB',     // Blue for placed state
      filledColor: '#2563EB',     // Blue for filled state  
      profitBoxFill: 'rgba(34, 197, 94, 0.15)',   // Green with transparency
      profitBoxStroke: '#22C55E',  // Green stroke
      lossBoxFill: 'rgba(239, 68, 68, 0.15)',     // Red with transparency  
      lossBoxStroke: '#EF4444',    // Red stroke
      greyBoxFill: 'rgba(107, 114, 128, 0.12)',   // Grey with transparency
      greyBoxStroke: '#6B7280',    // Grey stroke
      cancelledColor: '#6B7280',   // Grey for cancelled
      lineWidth: 2,
      labelColor: '#374151',
      labelSize: 12,
      labelOffset: 15,
      ...options
    }
    this.orders = []
    this.view = new OrderPaneView(this.options)
    this.chart = null
    this.series = null
    this.requestUpdate = null
  }

  updateOrders(orders) {
    this.orders = orders
    this.view.updateOrders(orders)
    
    // Request chart update if attached
    if (this.requestUpdate) {
      this.requestUpdate()
    }
  }

  // ISeriesPrimitive interface methods
  updateAllViews() {
    this.view.update()
  }

  paneViews() {
    return [this.view]
  }

  attached(param) {
    this.chart = param.chart
    this.series = param.series
    this.requestUpdate = param.requestUpdate
    
    // Pass chart context to the renderer
    this.view.setChartContext(param.chart, param.series)
    
    console.debug('OrderPrimitive attached to series')
  }

  detached() {
    console.debug('OrderPrimitive detached from series')
    this.chart = null
    this.series = null
    this.requestUpdate = null
  }
}

/**
 * Pane View that renders the orders
 */
class OrderPaneView {
  constructor(options) {
    this.options = options
    this.orders = []
    this.paneRenderer = new OrderPaneRenderer(options)
    this.chart = null
  }

  setChartContext(chart, series) {
    this.chart = chart
    this.series = series
    this.paneRenderer.setChartContext(chart, series)
  }

  updateOrders(orders) {
    this.orders = orders
    this.paneRenderer.updateOrders(orders)
  }

  update() {
    this.paneRenderer.updateOrders(this.orders)
  }

  renderer() {
    return this.paneRenderer
  }

  zOrder() {
    return 'normal' // Draw above candles but below crosshair
  }
}

/**
 * Renderer that does the actual canvas drawing
 */
class OrderPaneRenderer {
  constructor(options) {
    this.options = options
    this.orders = []
    this.chart = null
    this.series = null
  }

  setChartContext(chart, series) {
    this.chart = chart
    this.series = series
  }

  updateOrders(orders) {
    this.orders = orders
  }

  draw(target) {
    if (!this.orders.length) return

    target.useMediaCoordinateSpace(scope => {
      const ctx = scope.context
      
      this.orders.forEach(order => {
        this.drawOrder(ctx, order, scope)
      })
    })
  }

  drawOrder(ctx, order, scope) {
    // Get price coordinates
    const entryY = getPriceCoordinate(order.entryPoint, this.series, this.chart)
    const takeProfitY = getPriceCoordinate(order.takeProfit, this.series, this.chart)
    const stopLossY = getPriceCoordinate(order.stopLoss, this.series, this.chart)

    if (entryY === null || takeProfitY === null || stopLossY === null) {
      console.warn('Could not convert order prices to coordinates:', order)
      return
    }

    // Get time coordinates
    const startX = getTimeCoordinate(order.timestamp, this.chart)
    if (startX === null) return

    const placedX = order.placedTimestamp ? getTimeCoordinate(order.placedTimestamp, this.chart) : null
    const filledX = order.filledTimestamp ? getTimeCoordinate(order.filledTimestamp, this.chart) : null
    const closeX = order.closeTimestamp ? getTimeCoordinate(order.closeTimestamp, this.chart) : null

    // Determine profit and loss box boundaries
    // For Long orders: profit is above entry (takeProfit > entryPoint), loss is below
    // For Short orders: profit is below entry (takeProfit < entryPoint), loss is above
    const isLong = order.orderType === 'Long'
    
    const profitBoxTop = Math.min(entryY, takeProfitY)
    const profitBoxBottom = Math.max(entryY, takeProfitY)
    const lossBoxTop = Math.min(entryY, stopLossY)
    const lossBoxBottom = Math.max(entryY, stopLossY)

    // Draw based on order status
    switch (order.status) {
      case 'Planned':
        this.drawPlannedOrder(ctx, order, startX, entryY, scope)
        break
        
      case 'Placed':
        this.drawPlacedOrder(ctx, order, startX, placedX, entryY, scope)
        break
        
      case 'Filled':
        this.drawFilledOrder(ctx, order, startX, placedX, filledX, entryY, 
                           profitBoxTop, profitBoxBottom, lossBoxTop, lossBoxBottom, scope)
        break
        
      case 'Profit':
        this.drawProfitOrder(ctx, order, startX, placedX, filledX, closeX, entryY,
                           profitBoxTop, profitBoxBottom, lossBoxTop, lossBoxBottom, scope)
        break
        
      case 'Loss':
        this.drawLossOrder(ctx, order, startX, placedX, filledX, closeX, entryY,
                         profitBoxTop, profitBoxBottom, lossBoxTop, lossBoxBottom, scope)
        break
        
      case 'Cancelled':
        this.drawCancelledOrder(ctx, order, startX, closeX, entryY, scope)
        break
    }

    // No labels for orders - they weren't in the specification
  }

  drawPlannedOrder(ctx, order, startX, entryY, scope) {
    // Purple line from start to infinity
    drawHorizontalLine(ctx, {
      startX,
      endX: null,
      y: entryY,
      color: this.options.plannedColor,
      width: this.options.lineWidth,
      lineDash: 'solid',
      canvasWidth: scope.mediaSize.width
    })
  }

  drawPlacedOrder(ctx, order, startX, placedX, entryY, scope) {
    // Purple line from start to placed
    if (placedX !== null) {
      drawHorizontalLine(ctx, {
        startX,
        endX: placedX,
        y: entryY,
        color: this.options.plannedColor,
        width: this.options.lineWidth,
        lineDash: 'solid',
        canvasWidth: scope.mediaSize.width
      })

      // Blue line from placed to infinity
      drawHorizontalLine(ctx, {
        startX: placedX,
        endX: null,
        y: entryY,
        color: this.options.placedColor,
        width: this.options.lineWidth,
        lineDash: 'solid',
        canvasWidth: scope.mediaSize.width
      })
    }
  }

  drawFilledOrder(ctx, order, startX, placedX, filledX, entryY, 
                  profitBoxTop, profitBoxBottom, lossBoxTop, lossBoxBottom, scope) {
    // Lines: planned -> placed -> filled
    if (placedX !== null) {
      drawHorizontalLine(ctx, {
        startX,
        endX: placedX,
        y: entryY,
        color: this.options.plannedColor,
        width: this.options.lineWidth,
        lineDash: 'solid',
        canvasWidth: scope.mediaSize.width
      })
    }

    if (placedX !== null && filledX !== null) {
      drawHorizontalLine(ctx, {
        startX: placedX,
        endX: filledX,
        y: entryY,
        color: this.options.filledColor,
        width: this.options.lineWidth,
        lineDash: 'solid',
        canvasWidth: scope.mediaSize.width
      })

      // Infinite profit and loss boxes from filled time
      drawRectangle(ctx, {
        startX: filledX,
        endX: null,
        topY: profitBoxTop,
        bottomY: profitBoxBottom,
        fillColor: this.options.profitBoxFill,
        strokeColor: this.options.profitBoxStroke,
        strokeWidth: 1,
        canvasWidth: scope.mediaSize.width
      })

      drawRectangle(ctx, {
        startX: filledX,
        endX: null,
        topY: lossBoxTop,
        bottomY: lossBoxBottom,
        fillColor: this.options.lossBoxFill,
        strokeColor: this.options.lossBoxStroke,
        strokeWidth: 1,
        canvasWidth: scope.mediaSize.width
      })
    }
  }

  drawProfitOrder(ctx, order, startX, placedX, filledX, closeX, entryY,
                  profitBoxTop, profitBoxBottom, lossBoxTop, lossBoxBottom, scope) {
    // Lines: planned -> placed -> filled
    if (placedX !== null) {
      drawHorizontalLine(ctx, {
        startX,
        endX: placedX,
        y: entryY,
        color: this.options.plannedColor,
        width: this.options.lineWidth,
        lineDash: 'solid',
        canvasWidth: scope.mediaSize.width
      })
    }

    if (placedX !== null && filledX !== null) {
      drawHorizontalLine(ctx, {
        startX: placedX,
        endX: filledX,
        y: entryY,
        color: this.options.filledColor,
        width: this.options.lineWidth,
        lineDash: 'solid',
        canvasWidth: scope.mediaSize.width
      })

      if (closeX !== null) {
        // Finite boxes: filled -> closed
        // Winner: profit box (green), Loser: loss box (grey)
        drawRectangle(ctx, {
          startX: filledX,
          endX: closeX,
          topY: profitBoxTop,
          bottomY: profitBoxBottom,
          fillColor: this.options.profitBoxFill,
          strokeColor: this.options.profitBoxStroke,
          strokeWidth: 1,
          canvasWidth: scope.mediaSize.width
        })

        drawRectangle(ctx, {
          startX: filledX,
          endX: closeX,
          topY: lossBoxTop,
          bottomY: lossBoxBottom,
          fillColor: this.options.greyBoxFill,
          strokeColor: this.options.greyBoxStroke,
          strokeWidth: 1,
          canvasWidth: scope.mediaSize.width
        })
      }
    }
  }

  drawLossOrder(ctx, order, startX, placedX, filledX, closeX, entryY,
                profitBoxTop, profitBoxBottom, lossBoxTop, lossBoxBottom, scope) {
    // Lines: planned -> placed -> filled
    if (placedX !== null) {
      drawHorizontalLine(ctx, {
        startX,
        endX: placedX,
        y: entryY,
        color: this.options.plannedColor,
        width: this.options.lineWidth,
        lineDash: 'solid',
        canvasWidth: scope.mediaSize.width
      })
    }

    if (placedX !== null && filledX !== null) {
      drawHorizontalLine(ctx, {
        startX: placedX,
        endX: filledX,
        y: entryY,
        color: this.options.filledColor,
        width: this.options.lineWidth,
        lineDash: 'solid',
        canvasWidth: scope.mediaSize.width
      })

      if (closeX !== null) {
        // Finite boxes: filled -> closed
        // Winner: loss box (red), Loser: profit box (grey)
        drawRectangle(ctx, {
          startX: filledX,
          endX: closeX,
          topY: profitBoxTop,
          bottomY: profitBoxBottom,
          fillColor: this.options.greyBoxFill,
          strokeColor: this.options.greyBoxStroke,
          strokeWidth: 1,
          canvasWidth: scope.mediaSize.width
        })

        drawRectangle(ctx, {
          startX: filledX,
          endX: closeX,
          topY: lossBoxTop,
          bottomY: lossBoxBottom,
          fillColor: this.options.lossBoxFill,
          strokeColor: this.options.lossBoxStroke,
          strokeWidth: 1,
          canvasWidth: scope.mediaSize.width
        })
      }
    }
  }

  drawCancelledOrder(ctx, order, startX, closeX, entryY, scope) {
    // Grey line from start to close
    if (closeX !== null) {
      drawHorizontalLine(ctx, {
        startX,
        endX: closeX,
        y: entryY,
        color: this.options.cancelledColor,
        width: this.options.lineWidth,
        lineDash: 'solid',
        canvasWidth: scope.mediaSize.width
      })
    }
  }
}

export { OrderPrimitive }
export default OrderPrimitive