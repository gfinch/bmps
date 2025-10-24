/**
 * ModelPredictionRenderer - Renders model prediction events as line series grouped by time horizon
 * Creates separate line series for each prediction horizon (1min, 2min, 5min, 10min, 20min)
 */
import { LineSeries } from 'lightweight-charts'
import { BaseRenderer } from '../services/chartRenderingService.jsx'
import { extractEventType } from '../utils/eventTypeUtils.js'

class ModelPredictionRenderer extends BaseRenderer {
  constructor(chart, options = {}) {
    super(chart)
    this.options = {
      lineColor: '#2563EB',     // Blue color for all lines
      lineWidth: 2,
      lineStyle: 0,             // Solid line (0 = solid, 1 = dotted, 2 = dashed)
      ...options
    }
    this.seriesByHorizon = new Map() // Map of horizon -> LineSeries
    this.predictionsByHorizon = new Map() // Map of horizon -> array of predictions
    
    // Define expected horizons
    this.horizons = ['1min', '2min', '5min', '10min', '20min']
  }

  initialize() {
    // Create a line series for each time horizon
    this.horizons.forEach(horizon => {
      const series = this.chart.addSeries(LineSeries, {
        color: this.options.lineColor,
        lineWidth: this.options.lineWidth,
        lineStyle: this.options.lineStyle,
        title: `${horizon} Predictions`, // Shows in legend if enabled
        priceLineVisible: false,        // Don't show price line for predictions
        lastValueVisible: true,         // Show last value label
        visible: false,                 // Start hidden - controlled by dropdown
      })
      
      this.seriesByHorizon.set(horizon, series)
      this.predictionsByHorizon.set(horizon, [])
    })
    
    console.debug('ModelPredictionRenderer initialized with horizons:', this.horizons)
  }

  update(events, currentTimestamp = null, newYorkOffset = 0) {
    if (this.seriesByHorizon.size === 0) {
      console.debug('ModelPredictionRenderer: Series not initialized, skipping update')
      return
    }

    // Filter for ModelPrediction events
    const predictionEvents = events.filter(eventWrapper => {
      const actualEvent = eventWrapper.event || eventWrapper
      const eventType = extractEventType(actualEvent)
      return eventType === 'ModelPrediction'
    })

    if (predictionEvents.length === 0) {
      return
    }

    // Process prediction events and group by horizon
    const newPredictionsByHorizon = new Map()
    
    predictionEvents.forEach(eventWrapper => {
      const actualEvent = eventWrapper.event || eventWrapper
      const modelPrediction = actualEvent.modelPrediction
      
      if (!modelPrediction) {
        console.warn('ModelPredictionRenderer: Event missing modelPrediction data')
        return
      }

      const { level, timestamp, horizon } = modelPrediction
      
      if (level == null || !timestamp || !horizon) {
        console.warn('ModelPredictionRenderer: Invalid prediction data', modelPrediction)
        return
      }

      // Initialize array for this horizon if not exists
      if (!newPredictionsByHorizon.has(horizon)) {
        newPredictionsByHorizon.set(horizon, [])
      }

      // Add prediction data point
      newPredictionsByHorizon.get(horizon).push({
        time: Math.floor((timestamp + newYorkOffset) / 1000), // Convert to seconds with NY offset
        value: Number(level)
      })
    })

    // Update each horizon's line series
    newPredictionsByHorizon.forEach((newPredictions, horizon) => {
      const series = this.seriesByHorizon.get(horizon)
      if (!series) {
        console.warn(`ModelPredictionRenderer: No series found for horizon: ${horizon}`)
        return
      }

      // Get existing predictions for this horizon
      const existingPredictions = this.predictionsByHorizon.get(horizon) || []
      
      // Combine existing and new predictions
      const allPredictions = [...existingPredictions, ...newPredictions]
      
      // Remove duplicates by timestamp (keep latest)
      const uniquePredictions = new Map()
      allPredictions.forEach(pred => {
        uniquePredictions.set(pred.time, pred)
      })
      
      // Convert back to array and sort by time
      const sortedPredictions = Array.from(uniquePredictions.values())
        .sort((a, b) => a.time - b.time)
      
      // Update the series with all predictions
      series.setData(sortedPredictions)
      
      // Store updated predictions for this horizon
      this.predictionsByHorizon.set(horizon, sortedPredictions)
      
      console.debug(`ModelPredictionRenderer: Updated ${horizon} with ${sortedPredictions.length} predictions`)
    })
  }

  /**
   * Set the visibility of predictions for a specific horizon
   * @param {string} horizon - The time horizon ('1min', '2min', etc.)
   * @param {boolean} visible - Whether the horizon should be visible
   */
  setHorizonVisibility(horizon, visible) {
    const series = this.seriesByHorizon.get(horizon)
    if (series) {
      if (visible) {
        series.applyOptions({ visible: true })
      } else {
        series.applyOptions({ visible: false })
      }
      console.debug(`ModelPredictionRenderer: Set ${horizon} visibility to ${visible}`)
    } else {
      console.warn(`ModelPredictionRenderer: No series found for horizon: ${horizon}`)
    }
  }

  /**
   * Get the visibility state of a specific horizon
   * @param {string} horizon - The time horizon to check
   * @returns {boolean|null} Visibility state, or null if horizon not found
   */
  getHorizonVisibility(horizon) {
    const series = this.seriesByHorizon.get(horizon)
    if (series) {
      // TradingView doesn't expose visibility directly, so we track it or assume visible by default
      return true // You may want to track this state separately if needed
    }
    return null
  }

  /**
   * Get all available horizons
   * @returns {Array<string>} Array of horizon strings
   */
  getAvailableHorizons() {
    return [...this.horizons]
  }

  /**
   * Clear all predictions for a specific horizon
   * @param {string} horizon - The horizon to clear
   */
  clearHorizon(horizon) {
    const series = this.seriesByHorizon.get(horizon)
    if (series) {
      series.setData([])
      this.predictionsByHorizon.set(horizon, [])
      console.debug(`ModelPredictionRenderer: Cleared predictions for ${horizon}`)
    }
  }

  /**
   * Clear all predictions for all horizons
   */
  clearAll() {
    this.seriesByHorizon.forEach((series, horizon) => {
      series.setData([])
      this.predictionsByHorizon.set(horizon, [])
    })
    console.debug('ModelPredictionRenderer: Cleared all predictions')
  }

  destroy() {
    // Remove all series from chart
    this.seriesByHorizon.forEach((series, horizon) => {
      try {
        this.chart.removeSeries(series)
        console.debug(`ModelPredictionRenderer: Removed series for ${horizon}`)
      } catch (error) {
        console.warn(`ModelPredictionRenderer: Error removing series for ${horizon}:`, error)
      }
    })
    
    // Clear internal state
    this.seriesByHorizon.clear()
    this.predictionsByHorizon.clear()
    
    console.debug('ModelPredictionRenderer: Destroyed')
  }
}

export default ModelPredictionRenderer