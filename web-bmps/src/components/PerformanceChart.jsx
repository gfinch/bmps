/**
 * Simple Performance Chart Component
 * Shows cumulative P&L over time
 */

import { useEffect, useRef } from 'react'

/**
 * Simple line chart for P&L performance
 * @param {Object} props - Component props
 * @param {Array} props.data - Array of {timestamp, pnl, cumulativePnL} objects
 */
export default function PerformanceChart({ data }) {
  const canvasRef = useRef(null)

  useEffect(() => {
    if (!data || data.length === 0) return

    const canvas = canvasRef.current
    if (!canvas) return

    const ctx = canvas.getContext('2d')
    const { width, height } = canvas

    // Clear canvas
    ctx.clearRect(0, 0, width, height)

    // Chart styling
    const padding = 40
    const chartWidth = width - (padding * 2)
    const chartHeight = height - (padding * 2)

    // Find data bounds
    const minPnL = Math.min(0, Math.min(...data.map(d => d.cumulativePnL)))
    const maxPnL = Math.max(0, Math.max(...data.map(d => d.cumulativePnL)))
    const pnlRange = Math.max(Math.abs(minPnL), Math.abs(maxPnL)) * 2 || 1000
    
    const minTime = Math.min(...data.map(d => d.timestamp))
    const maxTime = Math.max(...data.map(d => d.timestamp))
    const timeRange = maxTime - minTime || 1

    // Helper functions
    const getX = (timestamp) => padding + ((timestamp - minTime) / timeRange) * chartWidth
    const getY = (pnl) => padding + chartHeight - ((pnl - minPnL) / (maxPnL - minPnL)) * chartHeight

    // Draw axes
    ctx.strokeStyle = '#E5E7EB'
    ctx.lineWidth = 1
    
    // Y-axis (left)
    ctx.beginPath()
    ctx.moveTo(padding, padding)
    ctx.lineTo(padding, height - padding)
    ctx.stroke()
    
    // X-axis (bottom)
    ctx.beginPath()
    ctx.moveTo(padding, height - padding)
    ctx.lineTo(width - padding, height - padding)
    ctx.stroke()

    // Zero line
    if (minPnL < 0 && maxPnL > 0) {
      const zeroY = getY(0)
      ctx.strokeStyle = '#9CA3AF'
      ctx.setLineDash([5, 5])
      ctx.beginPath()
      ctx.moveTo(padding, zeroY)
      ctx.lineTo(width - padding, zeroY)
      ctx.stroke()
      ctx.setLineDash([])
    }

    // Draw P&L line
    ctx.strokeStyle = '#3B82F6'
    ctx.lineWidth = 2
    ctx.beginPath()

    data.forEach((point, index) => {
      const x = getX(point.timestamp)
      const y = getY(point.cumulativePnL)
      
      if (index === 0) {
        ctx.moveTo(x, y)
      } else {
        ctx.lineTo(x, y)
      }
    })
    ctx.stroke()

    // Draw data points
    data.forEach(point => {
      const x = getX(point.timestamp)
      const y = getY(point.cumulativePnL)
      
      ctx.fillStyle = point.pnl >= 0 ? '#10B981' : '#EF4444'
      ctx.beginPath()
      ctx.arc(x, y, 4, 0, 2 * Math.PI)
      ctx.fill()
    })

    // Add labels
    ctx.fillStyle = '#6B7280'
    ctx.font = '12px system-ui'
    ctx.textAlign = 'center'
    
    // Y-axis labels
    const yLabels = [minPnL, 0, maxPnL].filter((v, i, arr) => arr.indexOf(v) === i)
    yLabels.forEach(value => {
      const y = getY(value)
      ctx.textAlign = 'right'
      ctx.fillText(`$${Math.round(value)}`, padding - 10, y + 4)
    })

    // Title
    ctx.fillStyle = '#374151'
    ctx.font = 'bold 14px system-ui'
    ctx.textAlign = 'center'
    ctx.fillText('Cumulative P&L', width / 2, 20)

  }, [data])

  return (
    <canvas
      ref={canvasRef}
      width={400}
      height={200}
      className="w-full h-full"
      style={{ maxHeight: '200px' }}
    />
  )
}