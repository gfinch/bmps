/**
 * React hook for Event Playback Service integration
 */

import { useState, useEffect, useCallback } from 'react'
import eventPlaybackService from '../services/eventPlaybackService.jsx'

/**
 * Custom hook for event playback functionality
 * @param {string} phase - Phase to control ('planning' or 'trading')
 * @returns {Object} Playback state and control functions
 */
export function useEventPlayback(phase) {
  // State for tracking playback
  const [currentTimestamp, setCurrentTimestamp] = useState(null)
  const [visibleEvents, setVisibleEvents] = useState([])
  const [totalTimestamps, setTotalTimestamps] = useState(0)
  const [isPlaying, setIsPlaying] = useState(false)
  const [playRate, setPlayRate] = useState(1000)
  const [timeRange, setTimeRange] = useState({ earliest: null, latest: null })

  // Update state from playback service
  const updateState = useCallback(() => {
    setCurrentTimestamp(eventPlaybackService.getCurrentTimestamp(phase))
    setVisibleEvents(eventPlaybackService.getVisibleEvents(phase))
    setTotalTimestamps(eventPlaybackService.getUniqueTimestamps(phase).length)
    setIsPlaying(eventPlaybackService.isPhasePlaying(phase))
    setPlayRate(eventPlaybackService.getPlayRate())
    setTimeRange(eventPlaybackService.getTimeRange(phase))
  }, [phase])

  // Set up listener for playback changes
  useEffect(() => {
    const listener = (changeInfo) => {
      // Update state if this change affects our phase or is a global change
      if (!changeInfo.phase || changeInfo.phase === phase) {
        updateState()
      }
    }

    eventPlaybackService.addListener(listener)
    
    // Initial state update
    updateState()

    // Cleanup
    return () => {
      eventPlaybackService.removeListener(listener)
    }
  }, [phase, updateState])

  // Navigation controls
  const controls = {
    /**
     * Move to first timestamp
     */
    rewind: useCallback(() => {
      eventPlaybackService.rewind(phase)
    }, [phase]),

    /**
     * Move to previous timestamp
     */
    stepBackward: useCallback(() => {
      eventPlaybackService.stepBackward(phase)
    }, [phase]),

    /**
     * Move to next timestamp
     */
    stepForward: useCallback(() => {
      eventPlaybackService.stepForward(phase)
    }, [phase]),

    /**
     * Move to latest timestamp
     */
    fastForward: useCallback(() => {
      eventPlaybackService.fastForward(phase)
    }, [phase]),

    /**
     * Start automatic playback
     */
    play: useCallback(() => {
      eventPlaybackService.play(phase)
    }, [phase]),

    /**
     * Stop automatic playback
     */
    pause: useCallback(() => {
      eventPlaybackService.pause(phase)
    }, [phase]),

    /**
     * Toggle play/pause
     */
    togglePlayPause: useCallback(() => {
      if (isPlaying) {
        eventPlaybackService.pause(phase)
      } else {
        eventPlaybackService.play(phase)
      }
    }, [phase, isPlaying]),

    /**
     * Set playback rate
     * @param {number} ms - Milliseconds between steps
     */
    setPlayRate: useCallback((ms) => {
      eventPlaybackService.setPlayRate(ms)
    }, []),

    /**
     * Jump to specific timestamp (for clip tool)
     * @param {number} timestamp - Target timestamp
     */
    jumpToTimestamp: useCallback((timestamp) => {
      eventPlaybackService.jumpToTimestamp(phase, timestamp)
    }, [phase])
  }

  // Navigation state
  const navigation = {
    /**
     * Check if we can step backward
     */
    canStepBackward: currentTimestamp !== null && currentTimestamp > timeRange.earliest,
    
    /**
     * Check if we can step forward
     */
    canStepForward: currentTimestamp !== null && currentTimestamp < timeRange.latest,
    
    /**
     * Check if we're at the beginning
     */
    isAtBeginning: currentTimestamp === timeRange.earliest,
    
    /**
     * Check if we're at the end
     */
    isAtEnd: currentTimestamp === timeRange.latest,

    /**
     * Get current position as a percentage (0-100)
     */
    getPositionPercent: () => {
      if (timeRange.earliest === null || timeRange.latest === null || currentTimestamp === null) {
        return 0
      }
      if (timeRange.earliest === timeRange.latest) {
        return 100
      }
      const uniqueTimestamps = eventPlaybackService.getUniqueTimestamps(phase)
      const currentIndex = uniqueTimestamps.findIndex(ts => ts === currentTimestamp)
      return currentIndex >= 0 ? (currentIndex / (uniqueTimestamps.length - 1)) * 100 : 0
    }
  }

  return {
    // Current state
    phase,
    currentTimestamp,
    visibleEvents,
    totalTimestamps,
    isPlaying,
    playRate,
    timeRange,
    
    // Controls
    ...controls,
    
    // Navigation helpers
    ...navigation
  }
}