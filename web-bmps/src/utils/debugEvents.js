/**
 * Debug utilities for interrogating event buffers from the console
 * Load this in the browser console or add to your app for debugging
 */

window.debugEvents = {
  
  /**
   * Get all events from the current store
   */
  getAllEvents() {
    const store = window.useEventStore?.getState ? window.useEventStore.getState() : null
    if (!store) {
      console.warn('Event store not found. Make sure the app is running.')
      return { planning: [], trading: [] }
    }
    
    return {
      planning: store.planningEvents || [],
      trading: store.tradingEvents || []
    }
  },
  
  /**
   * Filter events by type across all phases
   */
  getEventsByType(eventType) {
    const { planning, trading } = this.getAllEvents()
    const allEvents = [...planning, ...trading]
    
    return allEvents.filter(event => {
      // Handle different event type structures
      if (typeof event.eventType === 'string') {
        return event.eventType === eventType
      }
      if (typeof event.eventType === 'object' && event.eventType !== null) {
        return Object.keys(event.eventType).includes(eventType)
      }
      return false
    })
  },
  
  /**
   * Get all PlanZone events
   */
  getPlanZoneEvents() {
    const { planning, trading } = this.getAllEvents()
    const allEvents = [...planning, ...trading]
    
    return allEvents.filter(event => {
      return event.planZone !== null && event.planZone !== undefined
    })
  },
  
  /**
   * Analyze PlanZone event structure
   */
  analyzePlanZones() {
    const planZones = this.getPlanZoneEvents()
    
    if (planZones.length === 0) {
      console.log('No PlanZone events found')
      return
    }
    
    console.log(`Found ${planZones.length} PlanZone events`)
    
    // Show first event structure
    console.log('First PlanZone event structure:')
    console.log(JSON.stringify(planZones[0], null, 2))
    
    // Analyze planZone properties
    const firstZone = planZones[0].planZone
    console.log('\nPlanZone properties:')
    console.log('- high:', firstZone.high)
    console.log('- low:', firstZone.low) 
    console.log('- startTime:', firstZone.startTime)
    console.log('- endTime:', firstZone.endTime)
    console.log('- planZoneType:', firstZone.planZoneType)
    
    // Check for zone types
    const demandZones = planZones.filter(e => e.planZone.planZoneType?.Demand !== undefined)
    const supplyZones = planZones.filter(e => e.planZone.planZoneType?.Supply !== undefined)
    
    console.log(`\nZone breakdown:`)
    console.log(`- Demand zones: ${demandZones.length}`)
    console.log(`- Supply zones: ${supplyZones.length}`)
    
    return {
      total: planZones.length,
      demand: demandZones.length,
      supply: supplyZones.length,
      events: planZones
    }
  },
  
  /**
   * Get events in a time range (timestamps in milliseconds)
   */
  getEventsByTimeRange(startTime, endTime, phase = 'both') {
    const events = this.getAllEvents()
    const targetEvents = phase === 'both' 
      ? [...events.planning, ...events.trading]
      : phase === 'planning' 
        ? events.planning 
        : events.trading
        
    return targetEvents.filter(event => 
      event.timestamp >= startTime && event.timestamp <= endTime
    )
  },
  
  /**
   * Show event statistics
   */
  getStats() {
    const { planning, trading } = this.getAllEvents()
    
    const planningTypes = {}
    const tradingTypes = {}
    
    planning.forEach(event => {
      const type = this.extractEventType(event)
      planningTypes[type] = (planningTypes[type] || 0) + 1
    })
    
    trading.forEach(event => {
      const type = this.extractEventType(event)
      tradingTypes[type] = (tradingTypes[type] || 0) + 1
    })
    
    const stats = {
      planning: {
        total: planning.length,
        types: planningTypes,
        timeRange: this.getTimeRange(planning)
      },
      trading: {
        total: trading.length, 
        types: tradingTypes,
        timeRange: this.getTimeRange(trading)
      }
    }
    
    console.table(stats)
    return stats
  },
  
  /**
   * Extract event type from event
   */
  extractEventType(event) {
    if (typeof event.eventType === 'string') {
      return event.eventType
    }
    if (typeof event.eventType === 'object' && event.eventType !== null) {
      const keys = Object.keys(event.eventType)
      return keys.length > 0 ? keys[0] : 'Unknown'
    }
    return 'Unknown'
  },
  
  /**
   * Get time range for a set of events
   */
  getTimeRange(events) {
    if (events.length === 0) return null
    
    const timestamps = events.map(e => e.timestamp).sort((a, b) => a - b)
    return {
      start: new Date(timestamps[0]).toISOString(),
      end: new Date(timestamps[timestamps.length - 1]).toISOString(),
      startTs: timestamps[0],
      endTs: timestamps[timestamps.length - 1]
    }
  }
}

// Auto-run basic stats on load
console.log('🔍 Event Debug Utilities loaded!')
console.log('Available commands:')
console.log('- debugEvents.getAllEvents()')
console.log('- debugEvents.getPlanZoneEvents()')
console.log('- debugEvents.analyzePlanZones()')
console.log('- debugEvents.getEventsByType("PlanZone")')
console.log('- debugEvents.getStats()')

// Show basic stats
window.debugEvents.getStats()