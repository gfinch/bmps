package bmps.core.brokers.rest

import _root_.io.circe.generic.auto._
import _root_.io.circe.syntax._
import _root_.io.circe.parser._
import okhttp3.{MediaType, OkHttpClient, Request, RequestBody}
import scala.util.Using

case class InferenceFeatures(
    rsi: Double,
    adx: Double,
    trendStrength: Double,
    maSpread: Double,
    atr: Double,
    volume: Double,
    hour: Int,
    minute: Int,
    rule1: Boolean, rule2: Boolean, rule3: Boolean, rule4: Boolean, rule5: Boolean,
    rule6: Boolean, rule7: Boolean, rule8: Boolean, rule9: Boolean, rule10: Boolean,
    rule11: Boolean, rule12: Boolean,
    spreadChange1: Double, spreadChange2: Double, spreadChange3: Double, 
    spreadChange5: Double, spreadChange10: Double,
    rsiChange1: Double, rsiChange2: Double, rsiChange3: Double, 
    rsiChange5: Double, rsiChange10: Double
)

case class InferenceResponse(
    action: String,
    confidence: Double,
    threshold: Double
)

class InferenceBroker(baseUrl: String = "http://localhost:8001") {
    private val client = new OkHttpClient()
    private val JSON = MediaType.get("application/json; charset=utf-8")

    def predict(features: InferenceFeatures): Option[InferenceResponse] = {
        val json = features.asJson.noSpaces
        val body = RequestBody.create(json, JSON)
        val request = new Request.Builder()
            .url(s"$baseUrl/predict")
            .post(body)
            .build()

        try {
            Using(client.newCall(request).execute()) { response =>
                if (response.isSuccessful) {
                    val responseBody = response.body().string()
                    decode[InferenceResponse](responseBody) match {
                        case Right(resp) => Some(resp)
                        case Left(err) => 
                            println(s"Failed to parse inference response: $err")
                            None
                    }
                } else {
                    println(s"Inference API returned error: ${response.code}")
                    None
                }
            }.get
        } catch {
            case e: Exception =>
                println(s"Inference API call failed: ${e.getMessage}")
                None
        }
    }
}
