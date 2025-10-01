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
      console.debug('DaytimeExtremeRenderer initialized and attached to candlestick series')
    } else {
      console.warn('DaytimeExtremeRenderer: No candlestick series found to attach primitive')
    }
  }

  update(events, currentTimestamp = null) {
    if (!this.primitive) {
      console.debug('DaytimeExtremeRenderer: Primitive not initialized, skipping update')
      return
    }

    // Store events and timestamp for potential visibility changes
    this.lastEvents = events
    this.lastTimestamp = currentTimestamp

    // If not visible, pass empty lines to hide all extreme lines
    if (!this.visible) {
      this.primitive.updateLines([])
      console.debug('DaytimeExtremeRenderer: Hidden, updating with empty lines')
      return
    }

    console.debug(`DaytimeExtremeRenderer: Updating with ${events.length} events`)

    // Filter and deduplicate events by description, keeping only the latest for each
    const validEvents = events.filter(event => this.isValidEvent(event))
    const deduplicatedEvents = this.deduplicateByDescription(validEvents)
    
    console.debug(`DaytimeExtremeRenderer: After deduplication: ${deduplicatedEvents.length} events`)

    // Transform events into line data
    this.extremeLines = deduplicatedEvents.map(event => this.transformEventToLineData(event))

    console.debug(`DaytimeExtremeRenderer: Transformed to ${this.extremeLines.length} lines:`, this.extremeLines)

    // Update the primitive with new line data
    this.primitive.updateLines(this.extremeLines)
  }

  /**
   * Deduplicate events by description, keeping only the latest timestamp for each description
   * @param {Array} events - Array of valid events
   * @returns {Array} Deduplicated events
   */
  deduplicateByDescription(events) {
    const eventMap = new Map()
    
    events.forEach(event => {
      const actualEvent = event.event || event
      const extreme = actualEvent.daytimeExtreme
      const description = extreme.description || ''
      const timestamp = actualEvent.timestamp
      
      // If we haven't seen this description before, or this event is newer, store it
      if (!eventMap.has(description) || timestamp > eventMap.get(description).timestamp) {
        eventMap.set(description, { 
          event: event, 
          timestamp: timestamp,
          description: description 
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
    const isValid = actualEvent.daytimeExtreme && 
           typeof actualEvent.daytimeExtreme.level?.value === 'number' &&
           typeof actualEvent.timestamp === 'number'

    if (!isValid) {
      console.debug('DaytimeExtremeRenderer: Invalid event filtered out:', actualEvent)
    }

    return isValid
  }

  transformEventToLineData(event) {
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

    // Determine colors based on extreme type
    const isHighExtreme = extremeType === 'High'
    const lineColor = isHighExtreme ? this.options.highExtremeColor : this.options.lowExtremeColor
    
    const lineData = {
      id: `extreme-${actualEvent.timestamp}`,
      level: extreme.level.value,
      startTime: actualEvent.timestamp,
      endTime: extreme.endTime || null, // null means infinite
      label: this.generateLabel(extreme),
      extremeType: extremeType,
      style: {
        lineColor: lineColor,
        lineWidth: this.options.lineWidth,
        labelColor: lineColor,
        labelSize: this.options.labelSize
      }
    }

    console.debug('Transformed event to line data:', lineData)
    return lineData
  }

  generateLabel(extreme) {
    const description = extreme.description || ''
    
    // Generate session abbreviation (NY, L, A)
    if (description.toLowerCase().includes('new york')) return 'NY'
    if (description.toLowerCase().includes('london')) return 'L'  
    if (description.toLowerCase().includes('asia')) return 'A'
    
    // Fallback to first character
    return description.charAt(0).toUpperCase() || 'E'
  }

  destroy() {
    console.debug('DaytimeExtremeRenderer: Destroying renderer')
    
    if (this.primitive && this.chart.candlestickSeries) {
      this.chart.candlestickSeries.detachPrimitive(this.primitive)
    }
    this.primitive = null
    this.extremeLines = []
  }
}

export default DaytimeExtremeRenderer