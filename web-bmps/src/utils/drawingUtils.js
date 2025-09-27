/**
 * Drawing Utilities for TradingView Chart Primitives
 * Shared functions for canvas drawing operations
 */

/**
 * Draw a horizontal line on the canvas
 * @param {CanvasRenderingContext2D} ctx - Canvas context
 * @param {Object} options - Drawing options
 * @param {number} options.startX - Start X coordinate
 * @param {number} options.endX - End X coordinate (or null for infinity)
 * @param {number} options.y - Y coordinate
 * @param {string} options.color - Line color
 * @param {number} options.width - Line width
 * @param {string} options.lineDash - Line dash pattern ('solid' or 'dashed')
 * @param {number} options.canvasWidth - Canvas width for infinite lines
 */
export function drawHorizontalLine(ctx, options) {
  const { startX, endX, y, color, width, lineDash = 'solid', canvasWidth } = options
  
  ctx.strokeStyle = color
  ctx.lineWidth = width
  
  // Set dash pattern
  if (lineDash === 'dashed') {
    ctx.setLineDash([5, 5])
  } else {
    ctx.setLineDash([])
  }
  
  ctx.beginPath()
  ctx.moveTo(startX, y)
  ctx.lineTo(endX || canvasWidth, y)
  ctx.stroke()
  
  // Reset dash pattern
  ctx.setLineDash([])
}

/**
 * Draw a vertical line on the canvas
 * @param {CanvasRenderingContext2D} ctx - Canvas context
 * @param {Object} options - Drawing options
 * @param {number} options.x - X coordinate
 * @param {number} options.startY - Start Y coordinate
 * @param {number} options.endY - End Y coordinate
 * @param {string} options.color - Line color
 * @param {number} options.width - Line width
 * @param {string} options.lineDash - Line dash pattern ('solid' or 'dashed')
 */
export function drawVerticalLine(ctx, options) {
  const { x, startY, endY, color, width, lineDash = 'solid' } = options
  
  ctx.strokeStyle = color
  ctx.lineWidth = width
  
  // Set dash pattern
  if (lineDash === 'dashed') {
    ctx.setLineDash([5, 5])
  } else {
    ctx.setLineDash([])
  }
  
  ctx.beginPath()
  ctx.moveTo(x, startY)
  ctx.lineTo(x, endY)
  ctx.stroke()
  
  // Reset dash pattern
  ctx.setLineDash([])
}

/**
 * Draw a text label on the canvas
 * @param {CanvasRenderingContext2D} ctx - Canvas context
 * @param {Object} options - Drawing options
 * @param {string} options.text - Text to draw
 * @param {number} options.x - X coordinate
 * @param {number} options.y - Y coordinate
 * @param {string} options.color - Text color
 * @param {number} options.size - Font size
 * @param {string} options.align - Text alignment ('left', 'center', 'right')
 * @param {string} options.baseline - Text baseline ('top', 'middle', 'bottom')
 */
export function drawLabel(ctx, options) {
  const { 
    text, 
    x, 
    y, 
    color, 
    size = 12, 
    align = 'left', 
    baseline = 'middle' 
  } = options
  
  ctx.font = `${size}px Arial`
  ctx.fillStyle = color
  ctx.textAlign = align
  ctx.textBaseline = baseline
  ctx.fillText(text, x, y)
}

/**
 * Draw a filled rectangle on the canvas
 * @param {CanvasRenderingContext2D} ctx - Canvas context
 * @param {Object} options - Drawing options
 * @param {number} options.startX - Start X coordinate
 * @param {number} options.endX - End X coordinate (or null for infinity)
 * @param {number} options.topY - Top Y coordinate
 * @param {number} options.bottomY - Bottom Y coordinate
 * @param {string} options.fillColor - Fill color (with transparency)
 * @param {string} options.strokeColor - Stroke color
 * @param {number} options.strokeWidth - Stroke width
 * @param {number} options.canvasWidth - Canvas width for infinite rectangles
 */
export function drawRectangle(ctx, options) {
  const { 
    startX, 
    endX, 
    topY, 
    bottomY, 
    fillColor, 
    strokeColor, 
    strokeWidth = 1, 
    canvasWidth 
  } = options
  
  const rectEndX = endX || canvasWidth
  const rectWidth = rectEndX - startX
  const rectHeight = Math.abs(bottomY - topY)
  const rectY = Math.min(topY, bottomY)
  
  // Fill rectangle
  if (fillColor) {
    ctx.fillStyle = fillColor
    ctx.fillRect(startX, rectY, rectWidth, rectHeight)
  }
  
  // Stroke rectangle
  if (strokeColor) {
    ctx.strokeStyle = strokeColor
    ctx.lineWidth = strokeWidth
    ctx.setLineDash([]) // Solid lines for rectangles
    ctx.strokeRect(startX, rectY, rectWidth, rectHeight)
  }
}

/**
 * Convert timestamp to coordinate using chart's time scale
 * @param {number} timestamp - Timestamp in milliseconds
 * @param {Object} chart - TradingView chart instance
 * @returns {number|null} X coordinate or null if conversion fails
 */
export function getTimeCoordinate(timestamp, chart) {
  if (!chart) return null
  
  try {
    // Convert timestamp to TradingView time coordinate
    // TradingView expects time in seconds
    const timeInSeconds = Math.floor(timestamp / 1000)
    
    // Use the chart's time scale to convert to coordinate
    const logicalIndex = chart.timeScale().timeToCoordinate(timeInSeconds)
    return logicalIndex !== null && logicalIndex !== undefined ? logicalIndex : null
  } catch (error) {
    console.warn('Error converting time coordinate:', error)
    return null
  }
}

/**
 * Convert price to coordinate using series
 * @param {number} price - Price value
 * @param {Object} series - TradingView series instance
 * @param {Object} chart - TradingView chart instance (fallback)
 * @returns {number|null} Y coordinate or null if conversion fails
 */
export function getPriceCoordinate(price, series, chart) {
  let priceY = null
  
  try {
    // Try to get the price coordinate from the series
    if (series && series.priceToCoordinate) {
      priceY = series.priceToCoordinate(price)
    }
    // Alternative: try chart's price scale  
    else if (chart && chart.priceScale) {
      priceY = chart.priceScale().priceToCoordinate(price)
    }
  } catch (error) {
    console.warn('Error converting price to coordinate:', error)
  }
  
  return priceY
}