import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import Layout from './components/Layout'
import PlanningChartPage from './pages/PlanningChartPage'
import TradingChartPage from './pages/TradingChartPage'
import ResultsPage from './pages/ResultsPage'

function AppContent() {
  return (
    <Layout>
      <Routes>
        <Route path="/" element={<Navigate to="/planning" replace />} />
        <Route path="/planning" element={<PlanningChartPage />} />
        <Route path="/trading" element={<TradingChartPage />} />
        <Route path="/results" element={<ResultsPage />} />
      </Routes>
    </Layout>
  )
}

function App() {
  return (
    <BrowserRouter>
      <AppContent />
    </BrowserRouter>
  )
}

export default App
