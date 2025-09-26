/**
 * CandlestickRenderer - Renders candlestick series from Candle events
 */
import { CandlestickSeries } from 'lightweight-charts'
import { BaseRenderer } from '../services/chartRenderingService.jsx'

class CandlestickRenderer extends BaseRenderer {
  constructor(chart) {
    super(chart)
    this.series = null
  }

  initialize() {
    if (this.series) return
    this.series = this.chart.addSeries(CandlestickSeries, {
      upColor: '#26a69a',
      downColor: '#ef5350',
      borderVisible: false,
      wickUpColor: '#26a69a',
      wickDownColor: '#ef5350',
    })
  }

  update(candleEvents) {
    if (!this.series) return
    const chartData = this.processCandleEvents(candleEvents)
    this.series.setData(chartData)
  }

  processCandleEvents(candleEvents) {
    // Handle duplicate timestamps - take the latest one found
    const uniqueCandles = new Map()
    candleEvents.forEach(eventWrapper => {
      const actualEvent = eventWrapper.event || eventWrapper
      const timestamp = actualEvent.timestamp
      if (!uniqueCandles.has(timestamp)) {
        uniqueCandles.set(timestamp, eventWrapper)
      } else {
        uniqueCandles.set(timestamp, eventWrapper)
      }
    })
    return Array.from(uniqueCandles.values())
      .map(eventWrapper => {
        const actualEvent = eventWrapper.event || eventWrapper
        const candle = actualEvent.candle
        if (!candle) return null
        const open = candle.open?.value
        const high = candle.high?.value
        const low = candle.low?.value
        const close = candle.close?.value
        if ([open, high, low, close].some(v => v == null || isNaN(v))) return null
        return {
          time: Math.floor(actualEvent.timestamp / 1000),
          open: Number(open),
          high: Number(high),
          low: Number(low),
          close: Number(close)
        }
      })
      .filter(candle => candle !== null)
      .sort((a, b) => a.time - b.time)
  }

  destroy() {
    if (this.series) {
      this.chart.removeSeries(this.series)
      this.series = null
    }
  }
}

export default CandlestickRenderer
