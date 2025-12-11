/**
 * PlanZoneRenderer - Renders plan zones with demand/supply zone styling using Series Primitives
 * Draws rectangular zones with solid lines for primary levels and dashed lines for secondary levels
 */
import { BaseRenderer } from '../services/chartRenderingService.jsx'
import { PlanZonePrimitive } from '../primitives/PlanZonePrimitive.jsx'

class PlanZoneRenderer extends BaseRenderer {
  constructor(chart, options = {}) {
    super(chart)
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
    this.primitive = null
    this.planZones = []
  }

  initialize() {
    // Create a Series Primitive that draws on the candlestick series
    this.primitive = new PlanZonePrimitive(this.options)
    
    // Attach to the candlestick series (assumes it exists)
    if (this.chart.candlestickSeries) {
      this.chart.candlestickSeries.attachPrimitive(this.primitive)
      console.debug('PlanZoneRenderer initialized and attached to candlestick series')
    } else {
      console.warn('PlanZoneRenderer: No candlestick series found to attach primitive')
    }
  }

  update(events, currentTimestamp = null, newYorkOffset = 0) {
    if (!this.primitive) {
      console.debug('PlanZoneRenderer: Primitive not initialized, skipping update')
      return
    }

    // Store events and timestamp for potential visibility changes
    this.lastEvents = events
    this.lastTimestamp = currentTimestamp

    // If not visible, pass empty zones to hide all plan zones
    if (!this.visible) {
      this.primitive.updateZones([])
      console.debug('PlanZoneRenderer: Hidden, updating with empty zones')
      return
    }

    console.debug(`PlanZoneRenderer: Updating with ${events.length} events`)

    // Filter and deduplicate events
    const validEvents = events.filter(event => this.isValidEvent(event))
    const deduplicatedEvents = this.deduplicateByStartTime(validEvents)
    
    console.debug(`PlanZoneRenderer: After deduplication: ${deduplicatedEvents.length} events`)

    // Transform events into zone data, passing the offset
    this.planZones = deduplicatedEvents.map(event => this.transformEventToZoneData(event, newYorkOffset))

    // Optionally filter to show only active zones (zones without endTime)
    let zonesToDisplay = this.planZones
    if (this.options.showOnlyActive) {
      zonesToDisplay = this.planZones.filter(zone => zone.endTime === null)
      console.debug(`PlanZoneRenderer: Filtering for active zones only. Total: ${this.planZones.length}, Active: ${zonesToDisplay.length}`)
    }

    console.debug(`PlanZoneRenderer: Displaying ${zonesToDisplay.length} zones`)

    // Update the primitive with zone data
    this.primitive.updateZones(zonesToDisplay)
  }

  /**
   * Deduplicate events by start time, keeping only the latest arriving event for each start time
   * @param {Array} events - Array of valid events
   * @returns {Array} Deduplicated events
   */
  deduplicateByStartTime(events) {
    const eventMap = new Map()
    
    events.forEach((event, index) => {
      const actualEvent = event.event || event
      const planZone = actualEvent.planZone
      const startTime = planZone.startTime || actualEvent.timestamp
      const eventTimestamp = actualEvent.timestamp
      
      // If we haven't seen this start time before, or this event comes later in the buffer, store it
      const existing = eventMap.get(startTime)
      if (!existing || index > existing.index) {
        eventMap.set(startTime, { 
          event: event, 
          index: index,
          timestamp: eventTimestamp,
          startTime: startTime
        })
      }
    })
    
    // Return the events, sorted by start time for consistent ordering
    return Array.from(eventMap.values())
      .sort((a, b) => a.startTime - b.startTime)
      .map(item => item.event)
  }

  isValidEvent(event) {
    const actualEvent = event.event || event
    
    // Check for PlanZone event structure
    const planZone = actualEvent.planZone
    if (!planZone) {
      return false
    }

    const isValid = typeof planZone.high === 'number' &&
           typeof planZone.low === 'number' &&
           typeof actualEvent.timestamp === 'number' &&
           planZone.planZoneType // Should have a planZoneType object

    if (!isValid) {
      console.debug('PlanZoneRenderer: Invalid event filtered out:', actualEvent)
    }

    return isValid
  }

  transformEventToZoneData(event, newYorkOffset = 0) {
    const actualEvent = event.event || event
    const planZone = actualEvent.planZone
    
    // Determine zone type from the planZoneType object
    let zoneType = 'zone' // default
    if (planZone.planZoneType) {
      if (planZone.planZoneType.Demand !== undefined) {
        zoneType = 'demand'
      } else if (planZone.planZoneType.Supply !== undefined) {
        zoneType = 'supply'
      }
    }
    
    // Try multiple possible locations for endTime
    const endTime = planZone.endTime || 
                   planZone.endTimestamp || 
                   planZone.end || 
                   actualEvent.endTime ||
                   null
    
    // Calculate start time with offset
    const startTime = (planZone.startTime || actualEvent.timestamp) + newYorkOffset
    
    const zoneData = {
      id: `planzone-${actualEvent.timestamp}`,
      minLevel: planZone.low,   // low is the min level
      maxLevel: planZone.high,  // high is the max level
      startTime: startTime,
      endTime: endTime ? endTime + newYorkOffset : null, // null means infinite, otherwise apply offset
      type: zoneType,
      zoneType: zoneType, // Backup property name
      label: this.generateLabel(planZone, zoneType)
    }

    console.debug('Transformed event to zone data:', zoneData)
    return zoneData
  }

  generateLabel(planZone, zoneType) {
    // Use the zone type and optionally other identifying info
    const typeLabel = zoneType === 'demand' ? 'D' : zoneType === 'supply' ? 'S' : 'Z'
    
    // If there's additional description or identifier, use it
    if (planZone.description) {
      return `${typeLabel}:${planZone.description.substring(0, 3)}`
    }
    
    return typeLabel
  }

  destroy() {
    console.debug('PlanZoneRenderer: Destroying renderer')
    
    if (this.primitive && this.chart.candlestickSeries) {
      this.chart.candlestickSeries.detachPrimitive(this.primitive)
    }
    this.primitive = null
    this.planZones = []
  }
}

export default PlanZoneRenderer