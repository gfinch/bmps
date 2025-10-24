package bmps.core.brokers.rest

import okhttp3._
import io.circe._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import java.util.concurrent.TimeUnit
import scala.util.{Try, Success, Failure}

case class PointPredictionResponse(
    predictions_atr: Seq[Float],
    predictions_points: Seq[Float],
    time_horizons: Seq[String],
    inference_time_ms: Float
)

case class HealthResponse(
    status: String,
    model_loaded: Boolean,
    inference_time_ms: Option[Float]
)

/**
 * Broker for communicating with the XGBoost regression model API.
 * 
 * Handles HTTP communication with the Python FastAPI service that serves
 * the trained XGBoost regression models for multi-horizon price movement predictions.
 * 
 * The new model predicts price movements (in ATR units) for 5 time horizons:
 * 1min, 2min, 5min, 10min, and 20min.
 * 
 * Uses synchronous blocking calls like TradovateBroker for consistency
 * with the rest of the BMPS system.
 */
case class XGBoostModelBroker(
    client: OkHttpClient,
    baseUrl: String = "http://localhost:8001"
) {

  case class PointPredictionRequest(
      features: Seq[Float],
      atr_value: Float
  )

  private val JSON = MediaType.get("application/json; charset=utf-8")

  /**
   * Execute an HTTP request and parse the JSON response synchronously.
   */
  private def executeRequest[T: Decoder](request: Request): Try[T] = {
    Try {
      val response = client.newCall(request).execute()
      try {
        if (response.isSuccessful) {
          val responseBody = response.body().string()
          decode[T](responseBody) match {
            case Right(result) => result
            case Left(error) => throw new RuntimeException(s"Failed to parse response: $error")
          }
        } else {
          throw new RuntimeException(s"HTTP request failed with status ${response.code()}: ${response.message()}")
        }
      } finally {
        response.close()
      }
    }
  }

  /**
   * Predict price movements converted from ATR units to actual price points.
   *
   * @param features Array of 22 ATR-normalized technical features
   * @param atrValue Current ATR value in points for conversion
   * @return PointPredictionResponse with predictions in both ATR units and price points
   */
  def predictPointMoves(
      features: Seq[Float],
      atrValue: Float
  ): PointPredictionResponse = {
    require(features.length == 22, s"Expected 22 features, got ${features.length}")
    require(atrValue > 0.0f, s"ATR value must be positive, got $atrValue")

    val requestData = PointPredictionRequest(features, atrValue)
    val requestBody = RequestBody.create(requestData.asJson.noSpaces, JSON)
    
    val request = new Request.Builder()
      .url(s"$baseUrl/predictedPointMoves")
      .post(requestBody)
      .build()

    executeRequest[PointPredictionResponse](request) match {
      case Success(response) => response
      case Failure(exception) => throw exception
    }
  }

  /**
   * Check if the model API is healthy and responsive.
   *
   * @return HealthResponse or exception
   */
  def healthCheck(): HealthResponse = {
    val request = new Request.Builder()
      .url(s"$baseUrl/health")
      .get()
      .build()

    executeRequest[HealthResponse](request) match {
      case Success(response) => response
      case Failure(exception) => throw exception
    }
  }

  /**
   * Test if the API is reachable and the model is loaded.
   *
   * @return Boolean indicating if the API is ready
   */
  def isReady(): Boolean = {
    Try(healthCheck()) match {
      case Success(health) => health.status == "healthy" && health.model_loaded
      case Failure(_) => false
    }
  }

  /**
   * Get the base URL of the model API.
   */
  def getBaseUrl: String = baseUrl

}

object XGBoostModelBroker {
  
  /**
   * Create a broker with default OkHttpClient configuration.
   */
  def withDefaults(baseUrl: String = "http://localhost:8001"): XGBoostModelBroker = {
    val client = new OkHttpClient.Builder()
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      .writeTimeout(30, TimeUnit.SECONDS)
      .build()
    
    XGBoostModelBroker(client, baseUrl)
  }
}


