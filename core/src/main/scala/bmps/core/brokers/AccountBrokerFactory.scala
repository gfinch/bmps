package bmps.core.brokers

object AccountBrokerFactory {
    def buildAccountBroker(id: String, riskPerTrade: Double, brokerType: BrokerType) = {
        brokerType match {
            case BrokerType.LeadAccountBroker =>
                throw new IllegalArgumentException("The LeadAccountBroker can not be configured.")
            case BrokerType.SimulatedAccountBroker => 
                new SimulatedAccountBroker(id, riskPerTrade)
            case BrokerType.TradovateAccountBroker =>
                new SimulatedAccountBroker(id, riskPerTrade)
        }
    }
}
