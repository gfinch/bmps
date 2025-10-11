/**
 * Event Buffer System for BMPS application
 * Manages phase-specific event storage with timestamp ordering
 */

/**
 * @typedef {Object} Event
 * @property {string} eventType - Type of the event
 * @property {number} timestamp - Event timestamp for ordering
 * @property {string} phase - Phase this event belongs to ('planning' or 'trading')
 * @property {Object} [data] - Additional event data
 */

/**
 * Event Buffer class for managing events with timestamp ordering
 */
class EventBuffer {
  /**
   * @param {string} phase - The phase this buffer manages ('planning' or 'trading')
   */
  constructor(phase) {
    this.phase = phase
    this.events = []
    this.listeners = new Set()
    this.newYorkOffset = null // New York offset in milliseconds
  }

  /**
   * Add an event to the buffer
   * Events are automatically sorted by timestamp
   * @param {Event} event - Event to add
   */
  addEvent(event) {
    // Validate event structure
    if (!event || typeof event !== 'object') {
      console.warn('Invalid event object:', event)
      return
    }

    if (typeof event.timestamp !== 'number') {
      console.warn('Event missing valid timestamp:', event)
      return
    }

    // Add phase if not present
    const eventWithPhase = {
      ...event,
      phase: event.phase || this.phase
    }

    console.debug(`Adding event to ${this.phase} buffer:`, eventWithPhase)
    
    this.events.push(eventWithPhase)
    
    // Sort by timestamp to maintain chronological order
    this.events.sort((a, b) => a.timestamp - b.timestamp)
    
    // Notify listeners of the change
    this.notifyListeners()
  }

  /**
   * Get all events (immutable copy)
   * @returns {Event[]} Array of events sorted by timestamp
   */
  getEvents() {
    return [...this.events]
  }

  /**
   * Get events of a specific type
   * @param {string} eventType - Type of events to filter
   * @returns {Event[]} Filtered events
   */
  getEventsByType(eventType) {
    return this.events.filter(event => event.eventType === eventType)
  }

  /**
   * Get events within a time range
   * @param {number} startTime - Start timestamp
   * @param {number} endTime - End timestamp
   * @returns {Event[]} Events within the time range
   */
  getEventsByTimeRange(startTime, endTime) {
    return this.events.filter(event => 
      event.timestamp >= startTime && event.timestamp <= endTime
    )
  }

  /**
   * Get the latest event
   * @returns {Event|null} Most recent event or null if no events
   */
  getLatestEvent() {
    return this.events.length > 0 ? this.events[this.events.length - 1] : null
  }

  /**
   * Get number of events in buffer
   * @returns {number} Event count
   */
  getEventCount() {
    return this.events.length
  }

  /**
   * Clear all events from the buffer
   */
  clearEvents() {
    console.log(`Clearing ${this.phase} event buffer (${this.events.length} events)`)
    this.events = []
    this.notifyListeners()
  }

  /**
   * Set the New York offset for this event dataset
   * @param {number} offset - New York offset in milliseconds
   */
  setNewYorkOffset(offset) {
    this.newYorkOffset = offset
    console.debug(`${this.phase} buffer: Set New York offset to ${offset}ms`)
  }

  /**
   * Get the New York offset
   * @returns {number|null} New York offset in milliseconds, or null if not set
   */
  getNewYorkOffset() {
    return this.newYorkOffset
  }

  /**
   * Replace events from a specific source phase while preserving events from other phases
   * Used when multiple phases write to the same buffer (e.g., preparing and trading both write to 'trading' buffer)
   * @param {Event[]} newEvents - New events to add/replace
   * @param {string} sourcePhase - The source phase of these events ('preparing' or 'trading')
   */
  replaceEventsForPhase(newEvents, sourcePhase) {
    if (!Array.isArray(newEvents)) {
      console.warn('replaceEventsForPhase called with non-array:', newEvents)
      return
    }

    console.debug(`Replacing events from ${sourcePhase} phase in ${this.phase} buffer`)
    console.debug(`Before: ${this.events.length} events`)
    
    // Filter out events from the source phase, keep events from other phases
    const eventsFromOtherPhases = this.events.filter(event => {
      // Keep events that don't have a sourcePhase metadata or have a different sourcePhase
      return event.sourcePhase !== sourcePhase
    })
    
    // Add sourcePhase metadata to new events
    const newEventsWithMetadata = newEvents.map(event => ({
      ...event,
      sourcePhase: sourcePhase,
      phase: event.phase || this.phase
    }))
    
    // Combine and sort by timestamp
    this.events = [...eventsFromOtherPhases, ...newEventsWithMetadata]
      .sort((a, b) => a.timestamp - b.timestamp)
    
    console.debug(`After: ${this.events.length} events (${eventsFromOtherPhases.length} preserved, ${newEventsWithMetadata.length} new)`)
    
    this.notifyListeners()
  }

  /**
   * Add a listener for event changes
   * @param {function(): void} listener - Callback function
   */
  addListener(listener) {
    this.listeners.add(listener)
  }

  /**
   * Remove a listener
   * @param {function(): void} listener - Callback function to remove
   */
  removeListener(listener) {
    this.listeners.delete(listener)
  }

  /**
   * Notify all listeners of changes
   * @private
   */
  notifyListeners() {
    this.listeners.forEach(listener => {
      try {
        listener()
      } catch (error) {
        console.error('Error in event buffer listener:', error)
      }
    })
  }

  /**
   * Get buffer statistics
   * @returns {Object} Buffer statistics
   */
  getStats() {
    const eventTypes = {}
    this.events.forEach(event => {
      eventTypes[event.eventType] = (eventTypes[event.eventType] || 0) + 1
    })

    return {
      phase: this.phase,
      totalEvents: this.events.length,
      eventTypes,
      oldestTimestamp: this.events.length > 0 ? this.events[0].timestamp : null,
      newestTimestamp: this.events.length > 0 ? this.events[this.events.length - 1].timestamp : null
    }
  }
}

/**
 * Event Buffer Manager - manages multiple phase buffers
 */
class EventBufferManager {
  constructor() {
    this.buffers = {
      planning: new EventBuffer('planning'),
      trading: new EventBuffer('trading'),
      results: new EventBuffer('results')  // Order-only buffer for results tracking
    }
  }

  /**
   * Get buffer for a specific phase
   * @param {string} phase - Phase name ('planning', 'trading', or 'results')
   * @returns {EventBuffer} Event buffer for the phase
   */
  getBuffer(phase) {
    // Normalize phase name to lowercase
    const normalizedPhase = phase ? phase.toLowerCase() : phase
    
    if (!this.buffers[normalizedPhase]) {
      throw new Error(`Unknown phase: ${phase} (normalized: ${normalizedPhase})`)
    }
    return this.buffers[normalizedPhase]
  }

  /**
   * Add event to appropriate phase buffer
   * @param {Event} event - Event to add
   */
  addEvent(event) {
    if (!event.phase) {
      console.warn('Event missing phase, cannot route:', event)
      return
    }

    // Normalize phase name to lowercase
    const normalizedPhase = event.phase.toLowerCase()
    const buffer = this.buffers[normalizedPhase]
    
    if (buffer) {
      // Update the event with normalized phase
      const normalizedEvent = {
        ...event,
        phase: normalizedPhase
      }
      buffer.addEvent(normalizedEvent)
    } else {
      console.warn(`Unknown phase '${event.phase}' (normalized: '${normalizedPhase}') for event:`, event)
    }
    
    // NEW: Also add order events to results buffer for efficient results tracking
    if (this.isOrderEvent(event)) {
      const resultsEvent = {
        ...event,
        phase: 'results'  // Mark as results event
      }
      console.debug('Adding order event to results buffer:', resultsEvent)
      this.buffers.results.addEvent(resultsEvent)
    }
  }

  /**
   * Check if event is an Order event
   * @param {Object} event - Event to check
   * @returns {boolean}
   * @private
   */
  isOrderEvent(event) {
    // Handle nested event structure (event.event.eventType)
    const actualEvent = event.event || event
    
    return (actualEvent.eventType === 'Order' || 
           (typeof actualEvent.eventType === 'object' && 
            actualEvent.eventType && 
            Object.keys(actualEvent.eventType).includes('Order'))) &&
           actualEvent.order !== null && actualEvent.order !== undefined
  }

  /**
   * Clear events for a specific phase
   * @param {string} phase - Phase to clear
   */
  clearPhase(phase) {
    // Normalize phase name to lowercase
    const normalizedPhase = phase ? phase.toLowerCase() : phase
    
    // Protect results buffer from being cleared - it should persist across resets
    if (normalizedPhase === 'results') {
      console.debug('Skipping clear of results buffer - results should persist across resets')
      return
    }
    
    const buffer = this.buffers[normalizedPhase]
    if (buffer) {
      buffer.clearEvents()
    }
  }

  /**
   * Clear all events from all phases
   */
  clearAll() {
    // Clear phase buffers but preserve results buffer
    Object.entries(this.buffers).forEach(([phase, buffer]) => {
      if (phase !== 'results') {
        buffer.clearEvents()
      } else {
        console.debug('Preserving results buffer during clearAll()')
      }
    })
  }

  /**
   * Clear results buffer specifically (for manual reset of results)
   */
  clearResults() {
    console.debug('Manually clearing results buffer')
    this.buffers.results.clearEvents()
  }

  /**
   * Get stats for all buffers
   * @returns {Object} Statistics for all phases
   */
  getAllStats() {
    return Object.fromEntries(
      Object.entries(this.buffers).map(([phase, buffer]) => [
        phase,
        buffer.getStats()
      ])
    )
  }
}

// Create singleton instance
const eventBufferManager = new EventBufferManager()

export { EventBuffer, eventBufferManager }
export default eventBufferManager