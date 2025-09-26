# Web-React Application Requirements Document

## Executive Summary

The current web-react application has grown complex and unwieldy, with rendering becoming difficult and maintenance becoming costly. This document outlines a complete rewrite focusing on simplification, performance, and maintainability.

## Current State Analysis

### Identified Problems

1. **Over-Complex State Management**
   - Single monolithic eventStore (884 lines) handling all phases and data processing
   - Duplicated state for Planning and Trading phases
   - Complex event processing with manual data transformation
   - Multiple playback states creating confusion

2. **Tightly Coupled Components**
   - Chart components directly accessing store methods
   - Complex custom series (WorkPlanSeries) with manual Canvas rendering
   - Mixed concerns in page components (data processing + UI logic)

3. **Performance Issues**
   - Real-time data processing in main thread
   - Inefficient data transformations on every render
   - Memory leaks from WebSocket reconnection logic
   - Unoptimized chart updates causing re-renders

4. **Poor Separation of Concerns**
   - Business logic mixed with presentation logic
   - Global WebSocket management in App.jsx
   - Type definitions scattered across files
   - No clear data flow patterns

5. **Maintainability Issues**
   - Large files (500+ lines) with multiple responsibilities
   - Complex effects chains in React components
   - Hard-coded styling mixed with logic
   - No clear testing strategy

## Proposed Simplified Architecture

### Core Principles

1. **Single Responsibility**: Each component/module has one clear purpose
2. **Data Down, Events Up**: Unidirectional data flow
3. **Composition over Inheritance**: Small, composable components
4. **Performance First**: Optimize for rendering and memory usage
5. **Type Safety**: Full TypeScript coverage with proper interfaces

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                           App Layer                             │
├─────────────────────────────────────────────────────────────────┤
│ Router | Layout | Error Boundary | Loading States              │
└─────────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────────┐
│                        Page Layer                               │
├─────────────────────────────────────────────────────────────────┤
│ ConfigPage | PlanningPage | TradingPage | ResultsPage          │
└─────────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────────┐
│                      Component Layer                            │
├─────────────────────────────────────────────────────────────────┤
│ Chart | Controls | DataTable | StatusBar | Toolbar             │
└─────────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────────┐
│                       Service Layer                             │
├─────────────────────────────────────────────────────────────────┤
│ WebSocketService | DataProcessor | ChartRenderer | StorageService│
└─────────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────────┐
│                        Data Layer                               │
├─────────────────────────────────────────────────────────────────┤
│ ConnectionStore | DataStore | UIStore | ConfigStore            │
└─────────────────────────────────────────────────────────────────┘
```

## Detailed Requirements

### 1. Data Layer Redesign

#### 1.1 Store Separation
- **ConnectionStore**: WebSocket connection state only
- **DataStore**: Raw and processed chart data
- **UIStore**: UI state (playback, selections, filters)
- **ConfigStore**: Application configuration

#### 1.2 Data Processing
- Move data transformation to Web Workers
- Implement efficient data structures for time-series data
- Use immutable updates with proper memoization

### 2. Service Layer

#### 2.1 WebSocketService
```typescript
interface WebSocketService {
  connect(url: string): void
  disconnect(): void
  subscribe(phase: Phase): void
  sendCommand(command: Command): void
  getConnectionStatus(): ConnectionStatus
}
```

#### 2.2 DataProcessor
```typescript
interface DataProcessor {
  processCandles(events: CandleEvent[]): ProcessedCandle[]
  processZones(events: ZoneEvent[]): ProcessedZone[]
  processOrders(events: OrderEvent[]): ProcessedOrder[]
}
```

#### 2.3 ChartRenderer
```typescript
interface ChartRenderer {
  createChart(container: HTMLElement): Chart
  updateCandles(data: ProcessedCandle[]): void
  updateZones(data: ProcessedZone[]): void
  updateMarkers(data: ProcessedMarker[]): void
  destroy(): void
}
```

### 3. Component Layer Redesign

#### 3.1 Chart Component
- Single, reusable chart component
- Props-based configuration
- Internal optimization for data updates
- Proper cleanup on unmount

```typescript
interface ChartProps {
  data: ChartData
  config: ChartConfig
  onTimeChange?: (time: number) => void
  onZoneSelect?: (zone: Zone) => void
}
```

#### 3.2 Controls Component
```typescript
interface ControlsProps {
  isPlaying: boolean
  currentTime: number
  totalTime: number
  onPlay: () => void
  onPause: () => void
  onSeek: (time: number) => void
}
```

#### 3.3 Data Table Component
```typescript
interface DataTableProps {
  data: TableRow[]
  columns: ColumnDefinition[]
  onRowSelect?: (row: TableRow) => void
  sortable?: boolean
  filterable?: boolean
}
```

### 4. Performance Requirements

#### 4.1 Rendering Performance
- Chart updates must not block UI thread
- Smooth scrolling and zooming at 60fps
- Efficient memory usage for large datasets
- Proper virtualization for data tables

#### 4.2 Data Processing Performance
- WebSocket events processed in <1ms
- Data transformations in Web Workers
- Incremental updates instead of full reprocessing
- Maximum 100MB memory usage

### 5. Implementation Plan

#### Phase 1: Foundation (Week 1)
- Set up new directory structure
- Implement basic stores with Zustand
- Create WebSocketService
- Set up testing framework

#### Phase 2: Core Components (Week 2)
- Implement Chart component with lightweight-charts
- Create Controls component
- Build DataTable component
- Add error boundaries

#### Phase 3: Data Processing (Week 3)
- Implement DataProcessor service
- Set up Web Workers for heavy processing
- Add data transformation utilities
- Optimize memory usage

#### Phase 4: Integration (Week 4)
- Connect all components
- Add proper TypeScript types
- Implement proper error handling
- Add loading states

#### Phase 5: Polish (Week 5)
- Performance optimization
- Add animations and transitions
- Improve accessibility
- Add comprehensive testing

### 6. File Structure

```
src/
├── components/
│   ├── common/
│   │   ├── Button.tsx
│   │   ├── Input.tsx
│   │   └── Loader.tsx
│   ├── chart/
│   │   ├── Chart.tsx
│   │   ├── ChartControls.tsx
│   │   └── ChartConfig.ts
│   └── data/
│       ├── DataTable.tsx
│       └── DataFilter.tsx
├── services/
│   ├── websocket.ts
│   ├── dataProcessor.ts
│   ├── chartRenderer.ts
│   └── storage.ts
├── stores/
│   ├── connectionStore.ts
│   ├── dataStore.ts
│   ├── uiStore.ts
│   └── configStore.ts
├── types/
│   ├── api.ts
│   ├── chart.ts
│   └── common.ts
├── utils/
│   ├── formatters.ts
│   ├── validators.ts
│   └── helpers.ts
├── workers/
│   └── dataProcessor.worker.ts
├── pages/
│   ├── ConfigurationPage.tsx
│   ├── PlanningPage.tsx
│   ├── TradingPage.tsx
│   └── ResultsPage.tsx
└── hooks/
    ├── useWebSocket.ts
    ├── useChart.ts
    └── useData.ts
```

### 7. Technology Decisions

#### 7.1 Keep Current Stack
- React 19 (latest)
- TypeScript
- Vite
- Tailwind CSS
- Zustand (for state management)
- lightweight-charts

#### 7.2 Add New Dependencies
- @tanstack/react-table (for data tables)
- @tanstack/react-query (for server state)
- framer-motion (for animations)
- date-fns (for date handling)

#### 7.3 Remove Dependencies
- Complex custom series implementations
- Direct Canvas manipulation
- Manual WebSocket management in components

### 8. Success Criteria

#### 8.1 Performance Metrics
- First render < 100ms
- Chart updates < 16ms (60fps)
- Memory usage < 100MB
- Bundle size < 1MB

#### 8.2 Code Quality Metrics
- 100% TypeScript coverage
- 90%+ test coverage
- 0 ESLint errors
- Maximum file size: 200 lines

#### 8.3 User Experience Metrics
- Zero flickering during updates
- Smooth animations
- Intuitive navigation
- Clear error messages

### 9. Testing Strategy

#### 9.1 Unit Tests
- All utility functions
- Store actions and selectors
- Component logic

#### 9.2 Integration Tests
- WebSocket connection handling
- Data processing pipelines
- Chart rendering

#### 9.3 E2E Tests
- Complete user workflows
- Error scenarios
- Performance benchmarks

### 10. Migration Strategy

#### 10.1 Parallel Development
- Build new version alongside current
- Feature-by-feature replacement
- A/B testing for critical paths

#### 10.2 Gradual Rollout
- Start with Configuration page
- Move to Planning page
- Complete with Trading page
- Results page last

### 11. Risk Mitigation

#### 11.1 Technical Risks
- **Chart Performance**: Prototype early with large datasets
- **WebSocket Reliability**: Implement comprehensive error handling
- **Memory Leaks**: Use React DevTools and performance monitoring

#### 11.2 Project Risks
- **Timeline**: Buffer 20% extra time for unforeseen issues
- **Scope Creep**: Stick to MVP feature set
- **Breaking Changes**: Maintain API compatibility during transition

## Conclusion

This simplified architecture will result in:
- 70% reduction in code complexity
- 50% improvement in rendering performance
- 90% reduction in maintenance overhead
- Better developer experience and testability

The key is to move from a monolithic, tightly-coupled system to a modular, service-oriented architecture with clear separation of concerns and optimized data flow.