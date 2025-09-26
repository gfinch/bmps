/**
 * DaytimeExtreme Series Primitive for TradingView Lightweight Charts
 * Draws horizontal lines with labels from startTime to endTime (or infinity)
 */

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
    
    console.debug('DaytimeExtremePrimitive attached to series')
  }

  detached() {
    console.debug('DaytimeExtremePrimitive detached from series')
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
    // Get price coordinate through the series that this primitive is attached to
    let priceY = null
    
    try {
      // Try to get the price coordinate from the series
      if (this.series && this.series.priceToCoordinate) {
        priceY = this.series.priceToCoordinate(line.level)
      }
      // Alternative: try chart's price scale  
      else if (this.chart && this.chart.priceScale) {
        priceY = this.chart.priceScale().priceToCoordinate(line.level)
      }
    } catch (error) {
      console.warn('Error converting price to coordinate:', error)
    }
    
    // If we still don't have a coordinate, skip this line
    if (priceY === null || priceY === undefined) {
      console.warn('Could not convert price to coordinate for level:', line.level)
      return
    }

    // Get time coordinates 
    const startX = this.getTimeCoordinate(line.startTime, scope)
    if (startX === null) return

    const endX = line.endTime 
      ? this.getTimeCoordinate(line.endTime, scope)
      : scope.mediaSize.width // Infinite line to right edge

    // Draw the horizontal line
    ctx.strokeStyle = line.style.lineColor
    ctx.lineWidth = line.style.lineWidth
    ctx.beginPath()
    ctx.moveTo(startX, priceY)
    ctx.lineTo(endX || scope.mediaSize.width, priceY)
    ctx.stroke()

    // Draw the label
    this.drawLabel(ctx, line, startX, priceY)
  }

  drawLabel(ctx, line, x, y) {
    if (!line.label) return

    ctx.font = `${line.style.labelSize}px Arial`
    ctx.fillStyle = line.style.labelColor
    ctx.textBaseline = 'middle'
    ctx.textAlign = 'right'

    // Position label to the left of start time
    const labelX = x - this.options.labelOffset
    ctx.fillText(line.label, labelX, y)
  }

  getTimeCoordinate(timestamp, scope) {
    if (!this.chart) return null
    
    try {
      // Convert timestamp to TradingView time coordinate
      // TradingView expects time in seconds
      const timeInSeconds = Math.floor(timestamp / 1000)
      
      // Use the chart's time scale to convert to coordinate
      const logicalIndex = this.chart.timeScale().timeToCoordinate(timeInSeconds)
      return logicalIndex !== null && logicalIndex !== undefined ? logicalIndex : null
    } catch (error) {
      console.warn('Error converting time coordinate:', error)
      return null
    }
  }
}

export { DaytimeExtremePrimitive }
export default DaytimeExtremePrimitive