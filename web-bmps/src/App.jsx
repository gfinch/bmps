import { BrowserRouter, Routes, Route, Navigate, useNavigate } from 'react-router-dom'
import Layout from './components/Layout'
import ConfigurationPage from './pages/ConfigurationPage'
import PlanningChartPage from './pages/PlanningChartPage'
import TradingChartPage from './pages/TradingChartPage'
import ResultsPage from './pages/ResultsPage'

function AppContent() {
  const navigate = useNavigate()

  const handleNavigate = (path) => {
    navigate(`/${path}`)
  }

  return (
    <Layout>
      <Routes>
        <Route path="/" element={<Navigate to="/config" replace />} />
        <Route path="/config" element={<ConfigurationPage onNavigate={handleNavigate} />} />
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
