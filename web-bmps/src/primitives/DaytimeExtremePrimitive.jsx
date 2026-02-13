/**
 * DaytimeExtreme Series Primitive for TradingView Lightweight Charts
 * Draws horizontal lines with labels from startTime to endTime (or infinity)
 */

import { 
  drawHorizontalLine, 
  drawLabel, 
  getTimeCoordinate, 
  getPriceCoordinate 
} from '../utils/drawingUtils.js'

/**
 * TradingView Series Primitive for drawing horizontal lines with labels
 */
class DaytimeExtremePrimitive {
  constructor(options = {}) {
    this.options = {
      lineColor: '#FF6B35',
      lineWidth: 2,
      labelColor: '#FF6B35',
      labelSize: 12,
      labelOffset: 15, // pixels to the left of start time
      extendAcrossChart: false, // If true, lines extend from left edge to right edge
      ...options
    }
    this.lines = []
    this.view = new DaytimeExtremePaneView(this.options)
    this.chart = null
    this.series = null
    this.requestUpdate = null
  }

  updateLines(lines) {
    this.lines = lines
    this.view.updateLines(lines)
    
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
 * Pane View that renders the horizontal lines
 */
class DaytimeExtremePaneView {
  constructor(options) {
    this.options = options
    this.lines = []
    this.paneRenderer = new DaytimeExtremePaneRenderer(options)
    this.chart = null
  }

  setChartContext(chart, series) {
    this.chart = chart
    this.series = series
    this.paneRenderer.setChartContext(chart, series)
  }

  updateLines(lines) {
    this.lines = lines
    this.paneRenderer.updateLines(lines)
  }

  update() {
    this.paneRenderer.updateLines(this.lines)
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
class DaytimeExtremePaneRenderer {
  constructor(options) {
    this.options = options
    this.lines = []
    this.chart = null
  }

  setChartContext(chart, series) {
    this.chart = chart
    this.series = series
  }

  updateLines(lines) {
    this.lines = lines
  }

  draw(target) {
    if (!this.lines.length) return

    target.useMediaCoordinateSpace(scope => {
      const ctx = scope.context
      
      this.lines.forEach(line => {
        this.drawHorizontalLine(ctx, line, scope)
      })
    })
  }

  drawHorizontalLine(ctx, line, scope) {
    // Get price coordinate
    const priceY = getPriceCoordinate(line.level, this.series, this.chart)
    
    // If we can't get a coordinate, skip this line
    if (priceY === null || priceY === undefined) {
      console.warn('Could not convert price to coordinate for level:', line.level)
      return
    }

    // Determine start and end X coordinates based on extendAcrossChart option
    let startX, endX
    
    if (this.options.extendAcrossChart) {
      // Extend from left edge to right edge of chart
      startX = 0
      endX = null // null means extend to right edge
    } else {
      // Use the line's time-based boundaries
      startX = getTimeCoordinate(line.startTime, this.chart)
      if (startX === null) return

      endX = line.endTime 
        ? getTimeCoordinate(line.endTime, this.chart)
        : null // null means infinite line to right edge
    }

    // Draw the horizontal line using shared utility
    drawHorizontalLine(ctx, {
      startX,
      endX,
      y: priceY,
      color: line.style.lineColor,
      width: line.style.lineWidth,
      lineDash: 'solid',
      canvasWidth: scope.mediaSize.width
    })

    // Draw the label using shared utility
    if (line.label) {
      // For label positioning, use the actual startTime coordinate if available
      const labelX = this.options.extendAcrossChart 
        ? getTimeCoordinate(line.startTime, this.chart) || this.options.labelOffset
        : startX
        
      drawLabel(ctx, {
        text: line.label,
        x: labelX - this.options.labelOffset,
        y: priceY,
        color: line.style.labelColor,
        size: line.style.labelSize,
        align: 'right',
        baseline: 'middle'
      })
    }
  }
}

export { DaytimeExtremePrimitive }
export default DaytimeExtremePrimitive