package bmps.core.brokers

import bmps.core.brokers.BrokerType.SimulatedAccountBroker

class SimulatedAccountBroker(val accountId: String, 
                            val riskPerTrade: Double,
                            val feePerESContract: Double,
                            val feePerMESContract: Double) extends SimulationAccountBroker {
    val brokerType: BrokerType = BrokerType.SimulatedAccountBroker

    val accountBalance: Option[Double] = None
}
