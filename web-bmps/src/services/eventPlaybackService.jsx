/**
 * Event Playback Service for BMPS application
 * Manages phase-specific timeline navigation and event publishing
 */

import eventBufferManager from './eventBuffer.jsx'
import { extractEventType, filterEventsByType } from '../utils/eventTypeUtils.js'

/**
 * Event Playback Service - handles timeline navigation and event publishing
 */
class EventPlaybackService {
  constructor() {
    // Current playback state per phase
    this.currentTimestamp = {
      planning: null,    // Current timestamp pointer for planning
      trading: null      // Current timestamp pointer for trading
    }
    
    // Track the last known end timestamp for each phase (for auto-follow detection)
    this.lastEndTimestamp = {
      planning: null,
      trading: null
    }
    
    // Playback control
    this.isPlaying = {
      planning: false,
      trading: false
    }
    this.playIntervals = {
      planning: null,
      trading: null
    }
    this.playRate = 1000  // milliseconds between timestamp steps
    
    // Listeners for UI updates
    this.listeners = new Set()
    
    // Buffer change listeners to update timestamps when events arrive
    this.bufferListeners = new Map()
    this.setupBufferListeners()
  }

  /**
   * Setup listeners on event buffers to handle new events
   * @private
   */
  setupBufferListeners() {
    const phases = ['planning', 'trading']
    
    phases.forEach(phase => {
      const buffer = eventBufferManager.getBuffer(phase)
      const listener = () => this.handleBufferChange(phase)
      
      buffer.addListener(listener)
      this.bufferListeners.set(phase, listener)
    })
  }

  /**
   * Handle buffer changes - auto-follow latest events if at end
   * @param {string} phase - Phase that changed
   * @private
   */
  handleBufferChange(phase) {
    const buffer = eventBufferManager.getBuffer(phase)
    const events = buffer.getEvents()
    
    if (events.length === 0) {
      this.currentTimestamp[phase] = null
      this.lastEndTimestamp[phase] = null
      this.publishVisibleEvents(phase)
      return
    }
    
    const currentTs = this.currentTimestamp[phase]
    const previousEndTs = this.lastEndTimestamp[phase]
    const uniqueTimestamps = this.getUniqueTimestamps(phase)
    const newEndTs = uniqueTimestamps.length > 0 ? uniqueTimestamps[uniqueTimestamps.length - 1] : null
    
    // Auto-follow: if we were at the latest timestamp and new events arrived,
    // move to the new latest timestamp
    if (currentTs !== null && previousEndTs !== null && newEndTs !== null) {
      // Check if we were at the end before this update
      const wasAtEnd = currentTs === previousEndTs
      
      if (wasAtEnd && newEndTs > previousEndTs) {
        // We were at the end and new candles arrived - auto-follow to the new end
        this.currentTimestamp[phase] = newEndTs
        this.publishVisibleEvents(phase)
      } else if (!wasAtEnd) {
      }
    } else if (currentTs === null) {
      // Initialize to first timestamp when buffer is first populated
      if (uniqueTimestamps.length > 0) {
        const firstTimestamp = uniqueTimestamps[0]
        this.currentTimestamp[phase] = firstTimestamp
        this.publishVisibleEvents(phase)
      } else {
      }
    }
    
    // Update the tracked end timestamp for next comparison
    this.lastEndTimestamp[phase] = newEndTs
  }

  // Navigation API

  /**
   * Reset playback state for all phases
   * Clears timestamps and stops any playing intervals
   */
  reset() {
    // Stop any playing intervals
    Object.keys(this.playIntervals).forEach(phase => {
      if (this.playIntervals[phase]) {
        clearInterval(this.playIntervals[phase])
        this.playIntervals[phase] = null
      }
    })
    
    // Reset playback state
    this.isPlaying = {
      planning: false,
      trading: false
    }
    
    // Reset timestamps
    this.currentTimestamp = {
      planning: null,
      trading: null
    }
    
    // Reset tracked end timestamps
    this.lastEndTimestamp = {
      planning: null,
      trading: null
    }
    
    this.notifyListeners()
  }

  /**
   * Move to first timestamp in phase buffer
   * @param {string} phase - Phase to rewind ('planning' or 'trading')
   */
  rewind(phase) {
    const uniqueTimestamps = this.getUniqueTimestamps(phase)
    
    if (uniqueTimestamps.length === 0) {
      this.currentTimestamp[phase] = null
    } else {
      const firstTimestamp = uniqueTimestamps[0]
      this.currentTimestamp[phase] = firstTimestamp
    }
    
    this.publishVisibleEvents(phase)
  }

  /**
   * Move to previous timestamp
   * @param {string} phase - Phase to step backward
   */
  stepBackward(phase) {
    const uniqueTimestamps = this.getUniqueTimestamps(phase)
    const currentTs = this.currentTimestamp[phase]
    
    if (uniqueTimestamps.length === 0) {
      return
    }
    
    if (currentTs === null) {
      // Start from the end if no current position
      this.fastForward(phase)
      return
    }
    
    const currentIndex = uniqueTimestamps.findIndex(ts => ts === currentTs)
    if (currentIndex > 0) {
      const previousTimestamp = uniqueTimestamps[currentIndex - 1]
      this.currentTimestamp[phase] = previousTimestamp
      this.publishVisibleEvents(phase)
    } else {
    }
  }

  /**
   * Move to next timestamp
   * @param {string} phase - Phase to step forward
   */
  stepForward(phase) {
    const uniqueTimestamps = this.getUniqueTimestamps(phase)
    const currentTs = this.currentTimestamp[phase]
    
    if (uniqueTimestamps.length === 0) {
      return
    }
    
    if (currentTs === null) {
      // Start from the beginning if no current position
      this.rewind(phase)
      return
    }
    
    const currentIndex = uniqueTimestamps.findIndex(ts => ts === currentTs)
    if (currentIndex < uniqueTimestamps.length - 1) {
      const nextTimestamp = uniqueTimestamps[currentIndex + 1]
      this.currentTimestamp[phase] = nextTimestamp
      this.publishVisibleEvents(phase)
    } else {
    }
  }

  /**
   * Move to latest timestamp
   * @param {string} phase - Phase to fast forward
   */
  fastForward(phase) {
    const uniqueTimestamps = this.getUniqueTimestamps(phase)
    
    if (uniqueTimestamps.length === 0) {
      this.currentTimestamp[phase] = null
    } else {
      const latestTimestamp = uniqueTimestamps[uniqueTimestamps.length - 1]
      this.currentTimestamp[phase] = latestTimestamp
    }
    
    this.publishVisibleEvents(phase)
  }

  // Playback Control

  /**
   * Start automatic forward playback
   * @param {string} phase - Phase to play
   */
  play(phase) {
    if (this.isPlaying[phase]) {
      return
    }
    
    const uniqueTimestamps = this.getUniqueTimestamps(phase)
    if (uniqueTimestamps.length === 0) {
      return
    }
    
    // If no current position, start from beginning
    if (this.currentTimestamp[phase] === null) {
      this.rewind(phase)
    }
    
    this.isPlaying[phase] = true
    
    this.playIntervals[phase] = setInterval(() => {
      const currentTs = this.currentTimestamp[phase]
      const timestamps = this.getUniqueTimestamps(phase)
      const currentIndex = timestamps.findIndex(ts => ts === currentTs)
      
      if (currentIndex < timestamps.length - 1) {
        this.stepForward(phase)
      } else {
        // Reached the end, stop playing
        this.pause(phase)
      }
    }, this.playRate)
    
    this.notifyListeners()
  }

  /**
   * Stop automatic playback
   * @param {string} phase - Phase to pause
   */
  pause(phase) {
    if (!this.isPlaying[phase]) {
      return
    }
    
    this.isPlaying[phase] = false
    
    if (this.playIntervals[phase]) {
      clearInterval(this.playIntervals[phase])
      this.playIntervals[phase] = null
    }
    
    this.notifyListeners()
  }

  /**
   * Configure playback speed
   * @param {number} ms - Milliseconds between timestamp steps
   */
  setPlayRate(ms) {
    const oldRate = this.playRate
    this.playRate = ms
    
    // Restart any active playback with new rate
    const playingPhases = Object.keys(this.isPlaying).filter(phase => this.isPlaying[phase])
    playingPhases.forEach(phase => {
      this.pause(phase)
      this.play(phase)
    })
    
    this.notifyListeners()
  }

  // State Access

  /**
   * Get current timestamp pointer for phase
   * @param {string} phase - Phase to check
   * @returns {number|null} Current timestamp or null if not set
   */
  getCurrentTimestamp(phase) {
    return this.currentTimestamp[phase]
  }

  /**
   * Jump to specific timestamp (for clip tool functionality)
   * @param {string} phase - Phase to jump in
   * @param {number} timestamp - Target timestamp
   */
  jumpToTimestamp(phase, timestamp) {
    const uniqueTimestamps = this.getUniqueTimestamps(phase)
    
    if (uniqueTimestamps.length === 0) {
      return
    }

    // Find the closest available timestamp
    let closestTimestamp = uniqueTimestamps[0]
    let minDiff = Math.abs(timestamp - closestTimestamp)
    
    for (const ts of uniqueTimestamps) {
      const diff = Math.abs(timestamp - ts)
      if (diff < minDiff) {
        minDiff = diff
        closestTimestamp = ts
      }
    }
    
    this.currentTimestamp[phase] = closestTimestamp
    this.publishVisibleEvents(phase)
  }

  /**
   * Get all events visible at current timestamp (timestamp <= current)
   * @param {string} phase - Phase to get events for
   * @returns {Array} Events visible at current timestamp
   */
  getVisibleEvents(phase) {
    const buffer = eventBufferManager.getBuffer(phase)
    const events = buffer.getEvents()
    const currentTs = this.currentTimestamp[phase]
    
    if (currentTs === null) {
      return []
    }
    
    return events.filter(event => event.timestamp <= currentTs)
  }

  /**
   * Get sorted array of unique timestamps for phase
   * Only considers events with eventType 'Candle' for timeline navigation
   * @param {string} phase - Phase to get timestamps for
   * @returns {Array<number>} Sorted unique timestamps
   */
  getUniqueTimestamps(phase) {
    const buffer = eventBufferManager.getBuffer(phase)
    const events = buffer.getEvents()
    
    // Filter events to only include Candle events for timeline navigation
    const candleEvents = filterEventsByType(events, 'Candle')
    
    const uniqueTimestamps = [...new Set(candleEvents.map(event => {
      const actualEvent = event.event || event
      return actualEvent.timestamp
    }))]
    return uniqueTimestamps.sort((a, b) => a - b)
  }

  /**
   * Get time range for phase buffer
   * @param {string} phase - Phase to get range for
   * @returns {Object} {earliest: number|null, latest: number|null}
   */
  getTimeRange(phase) {
    const uniqueTimestamps = this.getUniqueTimestamps(phase)
    
    return {
      earliest: uniqueTimestamps.length > 0 ? uniqueTimestamps[0] : null,
      latest: uniqueTimestamps.length > 0 ? uniqueTimestamps[uniqueTimestamps.length - 1] : null
    }
  }

  /**
   * Get the New York offset for a phase
   * @param {string} phase - Phase to get offset for
   * @returns {number|null} New York offset in milliseconds, or null if not available
   */
  getNewYorkOffset(phase) {
    const buffer = eventBufferManager.getBuffer(phase)
    return buffer.getNewYorkOffset()
  }

  /**
   * Get playback state for phase
   * @param {string} phase - Phase to check
   * @returns {boolean} True if playing
   */
  isPhasePlaying(phase) {
    return this.isPlaying[phase]
  }

  /**
   * Get current playback rate
   * @returns {number} Milliseconds between steps
   */
  getPlayRate() {
    return this.playRate
  }

  // Event Publishing

  /**
   * Publish visible events to UI listeners
   * @param {string} phase - Phase that changed
   * @private
   */
  publishVisibleEvents(phase) {
    const visibleEvents = this.getVisibleEvents(phase)
    const currentTs = this.getCurrentTimestamp(phase)
    
    // Log all visible events for debugging
    visibleEvents.forEach(event => {
    })
    
    // Notify all listeners of the change
    this.notifyListeners({
      phase,
      currentTimestamp: currentTs,
      visibleEvents,
      totalEvents: eventBufferManager.getBuffer(phase).getEventCount()
    })
  }

  // Listener Management

  /**
   * Add listener for playback state changes
   * @param {function(Object): void} listener - Callback function
   */
  addListener(listener) {
    this.listeners.add(listener)
  }

  /**
   * Remove listener
   * @param {function(Object): void} listener - Callback function to remove
   */
  removeListener(listener) {
    this.listeners.delete(listener)
  }

  /**
   * Notify all listeners of changes
   * @param {Object} changeInfo - Information about the change
   * @private
   */
  notifyListeners(changeInfo = {}) {
    this.listeners.forEach(listener => {
      try {
        listener(changeInfo)
      } catch (error) {
        console.error('Error in event playback listener:', error)
      }
    })
  }

  // Cleanup

  /**
   * Reset playback state (used during application resets)
   */
  resetPlaybackState() {
    
    // Stop all playback first
    Object.keys(this.isPlaying).forEach(phase => {
      this.pause(phase)
    })
    
    // Reset timestamps to null
    this.currentTimestamp = {
      planning: null,
      trading: null
    }
    
    // Reset playing states (pause() should have handled this, but be explicit)
    this.isPlaying = {
      planning: false,
      trading: false
    }
    
    // Clear any remaining intervals (pause() should have cleared these)
    this.playIntervals = {
      planning: null,
      trading: null
    }
    
    // Notify listeners of the reset
    this.notifyListeners({ reset: true })
    
  }

  /**
   * Clean up resources
   */
  destroy() {
    // Stop all playback
    Object.keys(this.isPlaying).forEach(phase => {
      this.pause(phase)
    })
    
    // Remove buffer listeners
    this.bufferListeners.forEach((listener, phase) => {
      const buffer = eventBufferManager.getBuffer(phase)
      buffer.removeListener(listener)
    })
    this.bufferListeners.clear()
    
    // Clear listeners
    this.listeners.clear()
    
  }
}

// Create singleton instance
const eventPlaybackService = new EventPlaybackService()

export { EventPlaybackService, eventPlaybackService }
export default eventPlaybackService