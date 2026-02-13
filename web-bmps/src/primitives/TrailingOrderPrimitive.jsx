/**
 * Trailing Order Series Primitive for TradingView Lightweight Charts
 * Draws trailing orders as triangles:
 * - Horizontal line at entry point from timestamp to close (or current)
 * - Diagonal line from entry at timestamp to exit at close (if closed)
 * - Vertical line from exit to end of horizontal line (if closed)
 * - Triangle filled green for profit, red for loss
 */

import { 
  drawHorizontalLine, 
  drawVerticalLine,
  drawTriangle,
  drawLabel,
  getTimeCoordinate, 
  getPriceCoordinate 
} from '../utils/drawingUtils.js'

/**
 * TradingView Series Primitive for drawing trailing orders
 */
class TrailingOrderPrimitive {
  constructor(options = {}) {
    this.options = {
      openColor: '#2563EB',           // Blue for open trailing orders
      profitFill: 'rgba(34, 197, 94, 0.85)',   // Green, almost opaque
      lossFill: 'rgba(239, 68, 68, 0.85)',     // Red, almost opaque
      strokeColor: '#000000',          // Black stroke for triangles
      lineWidth: 2,
      labelColor: '#374151',
      labelSize: 12,
      labelOffset: 15,
      ...options
    }
    this.orders = []
    this.view = new TrailingOrderPaneView(this.options)
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
    
  }

  detached() {
    this.chart = null
    this.series = null
    this.requestUpdate = null
  }
}

/**
 * Pane View that renders the trailing orders
 */
class TrailingOrderPaneView {
  constructor(options) {
    this.options = options
    this.orders = []
    this.paneRenderer = new TrailingOrderPaneRenderer(options)
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
 * Renderer that does the actual canvas drawing for trailing orders
 */
class TrailingOrderPaneRenderer {
  constructor(options) {
    this.options = options
    this.orders = []
    this.chart = null
    this.series = null
  }

  /**
   * Convert entry type/strategy to display label
   * New Order uses entryStrategy: {description: "..."} 
   * Legacy used entryType: {EngulfingOrderBlock: {}} or {Trendy: {description: "..."}}
   * @param {string|object} entryType - The entry type/strategy from the order
   * @returns {string} The label for display
   */
  getEntryTypeLabel(entryType) {
    // New structure: entryStrategy with description field
    if (entryType && typeof entryType === 'object' && entryType.description) {
      return entryType.description
    }
    
    // Legacy: entryType has a Trendy property with description
    if (entryType && typeof entryType === 'object' && entryType.Trendy?.description) {
      return entryType.Trendy.description
    }
    
    // Legacy: convert string entry type to abbreviated label
    const entryTypeString = typeof entryType === 'string' ? entryType : ''
    const entryTypeMap = {
      'EngulfingOrderBlock': 'EOB',
      'FairValueGapOrderBlock': 'FVG',
      'InvertedFairValueGapOrderBlock': 'IFV',
      'BreakerBlockOrderBlock': 'BBO',
      'MarketStructureShiftOrderBlock': 'MSS',
      'SupermanOrderBlock': 'SOB',
      'JediOrderBlock': 'JOB',
      'XGBoostOrderBlock': 'XGB',
      'BouncingOrderBlock': 'BOB',
      'MomentumOrderBlock': 'MOM',
      'TrendOrderBlock': 'TAB',
      'ConsolidationFadeOrderBlock': 'CFD'
    }
    return entryTypeMap[entryTypeString] || entryTypeString
  }

  /**
   * Apply minimum width logic to ensure triangles have visual width
   * When start and close times are the same or too close, add padding
   * @param {number} startX - Original start X coordinate
   * @param {number} closeX - Original close X coordinate  
   * @returns {Object} Object with adjustedStartX and adjustedCloseX
   */
  applyMinimumWidth(startX, closeX) {
    // Calculate the width between start and close
    const originalWidth = Math.abs(closeX - startX)
    const minimumWidth = 20 // Minimum pixels of width to ensure visibility
    
    // If the width is too small, add padding
    if (originalWidth < minimumWidth) {
      const padding = (minimumWidth - originalWidth) / 2
      const adjustedStartX = startX - padding
      const adjustedCloseX = closeX + padding
      
      return { adjustedStartX, adjustedCloseX }
    }
    
    // Width is sufficient, return original coordinates
    return { adjustedStartX: startX, adjustedCloseX: closeX }
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
        this.drawTrailingOrder(ctx, order, scope)
      })
    })
  }

  drawTrailingOrder(ctx, order, scope) {
    // Get coordinates
    const entryY = getPriceCoordinate(order.entryPoint, this.series, this.chart)
    if (entryY === null) {
      console.warn('Could not convert entry price to coordinate:', order)
      return
    }

    const startX = getTimeCoordinate(order.timestamp, this.chart)
    if (startX === null) return

    const isClosed = order.closeTimestamp !== null && order.closeTimestamp !== undefined
    const closeX = isClosed ? getTimeCoordinate(order.closeTimestamp, this.chart) : null
    
    // Get exit point Y coordinate (only relevant if closed)
    const exitY = order.exitPoint !== null && order.exitPoint !== undefined
      ? getPriceCoordinate(order.exitPoint, this.series, this.chart)
      : null

    if (isClosed && closeX !== null && exitY !== null) {
      // Closed order - draw the full triangle with minimum width applied
      const { adjustedStartX, adjustedCloseX } = this.applyMinimumWidth(startX, closeX)
      this.drawClosedTrailingOrder(ctx, order, adjustedStartX, adjustedCloseX, entryY, exitY, scope)
    } else {
      // Open order - just draw horizontal line to end of canvas
      this.drawOpenTrailingOrder(ctx, order, startX, entryY, scope)
    }
  }

  drawOpenTrailingOrder(ctx, order, startX, entryY, scope) {
    // Draw horizontal solid line at entry point from timestamp to end of canvas
    drawHorizontalLine(ctx, {
      startX,
      endX: null, // null = extend to canvas edge
      y: entryY,
      color: this.options.openColor,
      width: this.options.lineWidth,
      lineDash: 'solid',
      canvasWidth: scope.mediaSize.width
    })

    // Draw entry type label to the left of order start
    const entryTypeLabel = this.getEntryTypeLabel(order.entryType)
    drawLabel(ctx, {
      text: entryTypeLabel,
      x: startX - this.options.labelOffset,
      y: entryY,
      color: this.options.labelColor,
      size: this.options.labelSize,
      align: 'right',
      baseline: 'middle'
    })
  }

  drawClosedTrailingOrder(ctx, order, startX, closeX, entryY, exitY, scope) {
    // Determine fill color based on status
    let fillColor = null
    if (order.status === 'Profit') {
      fillColor = this.options.profitFill
    } else if (order.status === 'Loss') {
      fillColor = this.options.lossFill
    }

    // The three points of the triangle:
    // Point 1: (startX, entryY) - entry point at timestamp
    // Point 2: (closeX, exitY) - exit point at close timestamp
    // Point 3: (closeX, entryY) - end of horizontal line at close timestamp

    // If entry and exit are the same, it will appear as a line
    // Draw the filled triangle first (if closed with profit/loss status)
    if (fillColor) {
      drawTriangle(ctx, {
        x1: startX,
        y1: entryY,
        x2: closeX,
        y2: exitY,
        x3: closeX,
        y3: entryY,
        fillColor: fillColor,
        strokeColor: this.options.strokeColor,
        strokeWidth: this.options.lineWidth
      })
    }

    // Draw the three lines that form the triangle edges:
    
    // 1. Horizontal solid line at entry point from timestamp to close timestamp
    drawHorizontalLine(ctx, {
      startX,
      endX: closeX,
      y: entryY,
      color: this.options.strokeColor,
      width: this.options.lineWidth,
      lineDash: 'solid',
      canvasWidth: scope.mediaSize.width
    })

    // 2. Diagonal line from entry at timestamp to exit at close
    ctx.strokeStyle = this.options.strokeColor
    ctx.lineWidth = this.options.lineWidth
    ctx.setLineDash([])
    ctx.beginPath()
    ctx.moveTo(startX, entryY)
    ctx.lineTo(closeX, exitY)
    ctx.stroke()

    // 3. Vertical line from exit point to end of horizontal line
    drawVerticalLine(ctx, {
      x: closeX,
      startY: exitY,
      endY: entryY,
      color: this.options.strokeColor,
      width: this.options.lineWidth,
      lineDash: 'solid'
    })

    // Draw entry type label to the left of order start
    const entryTypeLabel = this.getEntryTypeLabel(order.entryType)
    drawLabel(ctx, {
      text: entryTypeLabel,
      x: startX - this.options.labelOffset,
      y: entryY,
      color: this.options.labelColor,
      size: this.options.labelSize,
      align: 'right',
      baseline: 'middle'
    })
  }
}

export { TrailingOrderPrimitive }
