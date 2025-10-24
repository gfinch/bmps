package bmps.core.services

import bmps.core.brokers.rest.{XGBoostModelBroker, PointPredictionResponse}
import bmps.core.models.Order
import bmps.core.models.EntryType
import bmps.core.models.SystemState
import bmps.core.models.OrderType
import bmps.core.models.Candle
import bmps.core.models.ModelPrediction

class AIOrderBlockService(marketFeaturesService: MarketFeaturesService, xgBoostBroker: XGBoostModelBroker) {

    def processState(state: SystemState): SystemState = {
        require(state.tradingCandles.nonEmpty, "There must be at least one candle in state.")
        require(state.contractSymbol.isDefined, "The contract symbol must be defined before creating orders.")
        
        val contract = state.contractSymbol.get
        val features = marketFeaturesService.computeMarketFeatures(state)
        val featureVector = features.toFeatureVector

        val candle = state.tradingCandles.last
        val priorCandle = state.tradingCandles.init.last

        val predictions = xgBoostBroker.predictPointMoves(featureVector, features.atr)
        val newOrder: Option[Order] = buildOrder(state, features, predictions)
        val modelPredictions: List[ModelPrediction] = state.tradingModelPredictions ++ buildModelPredictions(state, predictions)

        newOrder.map { order =>
            state.copy(orders = state.orders :+ order, tradingModelPredictions = modelPredictions)
        }.getOrElse(state.copy(tradingModelPredictions = modelPredictions))
    }

    private def buildOrder(state: SystemState, features: MarketFeaturesNormalized, predictions: PointPredictionResponse): Option[Order] = {
        println(predictions)
        None
    }

    private def buildModelPredictions(state: SystemState, predictions: PointPredictionResponse): List[ModelPrediction] = {
        val candle = state.tradingCandles.last
        predictions.predictions_points.zip(predictions.time_horizons).map { case(pointMove, horizon) =>
            val level = candle.close + pointMove
            val timestamp = horizon match {
                case "1min" => candle.timestamp + (2 * 60000) //Add an extra minute to get to close of the candle.
                case "2min" => candle.timestamp + (3 * 60000)
                case "5min" => candle.timestamp + (6 * 60000)
                case "10min" => candle.timestamp + (11 * 60000)
                case "20min" => candle.timestamp + (21 * 60000)
            }
            ModelPrediction(level, timestamp, horizon)
        }.toList
    }

}
