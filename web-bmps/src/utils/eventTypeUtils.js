/**
 * Event Type Utilities
 * Shared functions for extracting and handling event types
 */

/**
 * Extract event type from an event
 * @param {Object} event - Event object
 * @returns {string|null} Event type or null if not found
 */
export function extractEventType(event) {
  if (!event) return null

  // Handle direct string eventType
  if (typeof event.eventType === 'string') {
    return event.eventType
  }

  // Handle object eventType (like {Candle: {...}})
  if (typeof event.eventType === 'object' && event.eventType !== null) {
    const keys = Object.keys(event.eventType)
    if (keys.length > 0) {
      return keys[0] // Return first key
    }
  }

  // Check if event has candle property directly (alternative structure)
  if (event.candle) {
    return 'Candle'
  }

  // Check if event has swingPoint property
  if (event.swingPoint) {
    return 'SwingPoint'
  }

  return null
}

/**
 * Filter events by event type
 * @param {Array} events - Array of events
 * @param {string} eventType - Event type to filter by
 * @returns {Array} Filtered events
 */
export function filterEventsByType(events, eventType) {
  return events.filter(event => {
    const actualEvent = event.event || event
    return extractEventType(actualEvent) === eventType
  })
}