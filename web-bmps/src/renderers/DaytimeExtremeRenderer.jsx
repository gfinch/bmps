/**
 * DaytimeExtremeRenderer - Renders horizontal lines with labels using Series Primitives
 * Draws from startTimestamp to endTimestamp (or infinity if no endTimestamp)
 */
import { BaseRenderer } from '../services/chartRenderingService.jsx'
import { DaytimeExtremePrimitive } from '../primitives/DaytimeExtremePrimitive.jsx'

class DaytimeExtremeRenderer extends BaseRenderer {
  constructor(chart, options = {}) {
    super(chart)
    this.options = {
      highExtremeColor: '#EF4444',  // Red for high extremes
      lowExtremeColor: '#22C55E',   // Green for low extremes
      lineWidth: 2,
      labelSize: 12,
      labelOffset: 15, // pixels to the left of start time
      ...options
    }
    this.primitive = null
    this.extremeLines = []
  }

  initialize() {
    // Create a Series Primitive that draws on the candlestick series
    this.primitive = new DaytimeExtremePrimitive(this.options)
    
    // Attach to the candlestick series (assumes it exists)
    if (this.chart.candlestickSeries) {
      this.chart.candlestickSeries.attachPrimitive(this.primitive)
    } else {
      console.warn('DaytimeExtremeRenderer: No candlestick series found to attach primitive')
    }
  }

  update(events, currentTimestamp = null, newYorkOffset = 0) {
    if (!this.primitive) {
      return
    }

    // Store events and timestamp for potential visibility changes
    this.lastEvents = events
    this.lastTimestamp = currentTimestamp

    // If not visible, pass empty lines to hide all extreme lines
    if (!this.visible) {
      this.primitive.updateLines([])
      return
    }


    // Filter and deduplicate events by description, keeping only the latest for each
    const validEvents = events.filter(event => this.isValidEvent(event))
    const deduplicatedEvents = this.deduplicateByDescription(validEvents)
    

    // Transform events into line data, passing the offset
    this.extremeLines = deduplicatedEvents.map(event => this.transformEventToLineData(event, newYorkOffset))


    // Update the primitive with new line data
    this.primitive.updateLines(this.extremeLines)
  }

  /**
   * Deduplicate events by market and extreme type combination, keeping only the latest for each.
   * If timestamps are equal, the last one in the array is kept (maintaining arrival order).
   * @param {Array} events - Array of valid events
   * @returns {Array} Deduplicated events
   */
  deduplicateByDescription(events) {
    const eventMap = new Map()
    
    events.forEach((event, index) => {
      const actualEvent = event.event || event
      const extreme = actualEvent.daytimeExtreme
      
      // Extract market from nested object structure
      let market = 'NewYork' // default
      if (extreme.market) {
        const marketKeys = Object.keys(extreme.market)
        if (marketKeys.length > 0) {
          market = marketKeys[0]
        }
      }
      
      // Extract extreme type from nested object structure
      let extremeType = 'High' // default
      if (extreme.extremeType) {
        const extremeTypeKeys = Object.keys(extreme.extremeType)
        if (extremeTypeKeys.length > 0) {
          extremeType = extremeTypeKeys[0]
        }
      }
      
      // Create a composite key from market and extreme type
      const key = `${market}-${extremeType}`
      const timestamp = actualEvent.timestamp
      
      // If we haven't seen this combination before, or this event is newer, or same timestamp but later in array, store it
      if (!eventMap.has(key) || 
          timestamp > eventMap.get(key).timestamp ||
          (timestamp === eventMap.get(key).timestamp && index > eventMap.get(key).index)) {
        eventMap.set(key, { 
          event: event, 
          timestamp: timestamp,
          index: index,
          market: market,
          extremeType: extremeType
        })
      }
    })
    
    // Return the events, sorted by timestamp for consistent ordering
    return Array.from(eventMap.values())
      .sort((a, b) => a.timestamp - b.timestamp)
      .map(item => item.event)
  }

  isValidEvent(event) {
    const actualEvent = event.event || event
    const extreme = actualEvent.daytimeExtreme
    if (!extreme || typeof actualEvent.timestamp !== 'number') {
      return false
    }
    
    const isValid = typeof extreme.level === 'number'

    if (!isValid) {
    }

    return isValid
  }

  transformEventToLineData(event, newYorkOffset = 0) {
    const actualEvent = event.event || event
    const extreme = actualEvent.daytimeExtreme
    
    // Extract extreme type from nested object structure like {High: {}} or {Low: {}}
    let extremeType = 'High' // default
    if (extreme.extremeType) {
      const extremeTypeKeys = Object.keys(extreme.extremeType)
      if (extremeTypeKeys.length > 0) {
        extremeType = extremeTypeKeys[0] // Take the first key (High or Low)
      }
    }

    // Extract market from nested object structure like {NewYork: {}}, {Asia: {}}, {London: {}}
    let market = 'NewYork' // default
    if (extreme.market) {
      const marketKeys = Object.keys(extreme.market)
      if (marketKeys.length > 0) {
        market = marketKeys[0] // Take the first key (NewYork, Asia, or London)
      }
    }

    // Determine colors based on extreme type
    const isHighExtreme = extremeType === 'High'
    const lineColor = isHighExtreme ? this.options.highExtremeColor : this.options.lowExtremeColor
    
    const lineData = {
      id: `extreme-${actualEvent.timestamp}`,
      level: extreme.level,
      startTime: actualEvent.timestamp + newYorkOffset,
      endTime: extreme.endTime ? extreme.endTime + newYorkOffset : null, // null means infinite, otherwise apply offset
      label: this.generateLabel(market),
      extremeType: extremeType,
      market: market,
      style: {
        lineColor: lineColor,
        lineWidth: this.options.lineWidth,
        labelColor: lineColor,
        labelSize: this.options.labelSize
      }
    }

    return lineData
  }

  generateLabel(market) {
    // Generate session abbreviation based on Market enum value
    switch (market) {
      case 'NewYork':
        return 'NY'
      case 'London':
        return 'L'
      case 'Asia':
        return 'A'
      default:
        return 'E' // Fallback for unknown market
    }
  }

  destroy() {
    
    if (this.primitive && this.chart.candlestickSeries) {
      this.chart.candlestickSeries.detachPrimitive(this.primitive)
    }
    this.primitive = null
    this.extremeLines = []
  }
}

export default DaytimeExtremeRenderer