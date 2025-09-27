/**
 * PlanZone Series Primitive for TradingView Lightweight Charts
 * Draws plan zones with demand/supply zone styling:
 * - Demand zones: solid green line at max, dashed gray at min and sides
 * - Supply zones: solid green line at min, dashed gray at max and sides
 */

import { 
  drawHorizontalLine, 
  drawVerticalLine, 
  drawLabel, 
  getTimeCoordinate, 
  getPriceCoordinate 
} from '../utils/drawingUtils.js'

/**
 * TradingView Series Primitive for drawing plan zones
 */
class PlanZonePrimitive {
  constructor(options = {}) {
    this.options = {
      demandColor: '#22C55E', // Green for demand zones
      supplyColor: '#EF4444', // Red for supply zones
      grayColor: '#6B7280', // Gray for secondary lines
      lineWidth: 2,
      labelColor: '#374151',
      labelSize: 12,
      labelOffset: 15, // pixels to the left of start time
      ...options
    }
    this.zones = []
    this.view = new PlanZonePaneView(this.options)
    this.chart = null
    this.series = null
    this.requestUpdate = null
  }

  updateZones(zones) {
    this.zones = zones
    this.view.updateZones(zones)
    
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
    
    console.debug('PlanZonePrimitive attached to series')
  }

  detached() {
    console.debug('PlanZonePrimitive detached from series')
    this.chart = null
    this.series = null
    this.requestUpdate = null
  }
}

/**
 * Pane View that renders the plan zones
 */
class PlanZonePaneView {
  constructor(options) {
    this.options = options
    this.zones = []
    this.paneRenderer = new PlanZonePaneRenderer(options)
    this.chart = null
  }

  setChartContext(chart, series) {
    this.chart = chart
    this.series = series
    this.paneRenderer.setChartContext(chart, series)
  }

  updateZones(zones) {
    this.zones = zones
    this.paneRenderer.updateZones(zones)
  }

  update() {
    this.paneRenderer.updateZones(this.zones)
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
class PlanZonePaneRenderer {
  constructor(options) {
    this.options = options
    this.zones = []
    this.chart = null
    this.series = null
  }

  setChartContext(chart, series) {
    this.chart = chart
    this.series = series
  }

  updateZones(zones) {
    this.zones = zones
  }

  draw(target) {
    if (!this.zones.length) return

    target.useMediaCoordinateSpace(scope => {
      const ctx = scope.context
      
      this.zones.forEach(zone => {
        this.drawPlanZone(ctx, zone, scope)
      })
    })
  }

  drawPlanZone(ctx, zone, scope) {
    // Get time coordinates
    const startX = getTimeCoordinate(zone.startTime, this.chart)
    if (startX === null) return

    const endX = zone.endTime 
      ? getTimeCoordinate(zone.endTime, this.chart)
      : null // null means infinite (draw to right edge)

    // Get price coordinates
    const maxY = getPriceCoordinate(zone.maxLevel, this.series, this.chart)
    const minY = getPriceCoordinate(zone.minLevel, this.series, this.chart)

    if (maxY === null || minY === null) {
      console.warn('Could not convert zone prices to coordinates:', zone)
      return
    }

    // Determine if this is a demand or supply zone
    const isDemandZone = zone.type === 'demand' || zone.zoneType === 'demand'
    
    // Draw the primary line (solid green)
    const primaryY = isDemandZone ? maxY : minY
    const primaryLevel = isDemandZone ? zone.maxLevel : zone.minLevel
    
    drawHorizontalLine(ctx, {
      startX,
      endX,
      y: primaryY,
      color: isDemandZone ? this.options.demandColor : this.options.supplyColor,
      width: this.options.lineWidth,
      lineDash: 'solid',
      canvasWidth: scope.mediaSize.width
    })

    // Draw the secondary line (dashed gray)
    const secondaryY = isDemandZone ? minY : maxY
    const secondaryLevel = isDemandZone ? zone.minLevel : zone.maxLevel
    
    drawHorizontalLine(ctx, {
      startX,
      endX,
      y: secondaryY,
      color: this.options.grayColor,
      width: this.options.lineWidth,
      lineDash: 'dashed',
      canvasWidth: scope.mediaSize.width
    })

    // Draw vertical lines at the sides (dashed gray)
    // Left side
    drawVerticalLine(ctx, {
      x: startX,
      startY: Math.min(maxY, minY),
      endY: Math.max(maxY, minY),
      color: this.options.grayColor,
      width: this.options.lineWidth,
      lineDash: 'dashed'
    })

    // Right side (only if there's an end time)
    if (endX !== null) {
      drawVerticalLine(ctx, {
        x: endX,
        startY: Math.min(maxY, minY),
        endY: Math.max(maxY, minY),
        color: this.options.grayColor,
        width: this.options.lineWidth,
        lineDash: 'dashed'
      })
    }

    // Draw label on primary line
    if (zone.label) {
      drawLabel(ctx, {
        text: zone.label,
        x: startX - this.options.labelOffset,
        y: primaryY,
        color: this.options.labelColor,
        size: this.options.labelSize,
        align: 'right',
        baseline: 'middle'
      })
    }
  }
}

export { PlanZonePrimitive }
export default PlanZonePrimitive