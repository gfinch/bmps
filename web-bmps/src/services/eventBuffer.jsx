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
      trading: new EventBuffer('trading')
    }
  }

  /**
   * Get buffer for a specific phase
   * @param {string} phase - Phase name ('planning' or 'trading')
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
  }

  /**
   * Clear events for a specific phase
   * @param {string} phase - Phase to clear
   */
  clearPhase(phase) {
    // Normalize phase name to lowercase
    const normalizedPhase = phase ? phase.toLowerCase() : phase
    const buffer = this.buffers[normalizedPhase]
    if (buffer) {
      buffer.clearEvents()
    }
  }

  /**
   * Clear all events from all phases
   */
  clearAll() {
    Object.values(this.buffers).forEach(buffer => buffer.clearEvents())
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