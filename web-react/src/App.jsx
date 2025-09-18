import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import Layout from './components/Layout'
import ConfigurationPage from './pages/ConfigurationPage'
import PlanningChartPage from './pages/PlanningChartPage'
import TradingChartPage from './pages/TradingChartPage'
import ResultsPage from './pages/ResultsPage'

function App() {
  return (
    <BrowserRouter>
      <div className="min-h-screen bg-gray-50">
        <Layout>
          <Routes>
            <Route path="/" element={<Navigate to="/config" replace />} />
            <Route path="/config" element={<ConfigurationPage />} />
            <Route path="/planning" element={<PlanningChartPage />} />
            <Route path="/trading" element={<TradingChartPage />} />
            <Route path="/results" element={<ResultsPage />} />
          </Routes>
        </Layout>
      </div>
    </BrowserRouter>
  )
}

export default App
