package bmps.core.brokers

import com.typesafe.config.Config
import bmps.core.brokers.rest.TradovateBroker

object AccountBrokerFactory {
    def buildAccountBroker(brokerType: BrokerType, brokerConfig: Config) = {
        brokerType match {
            case BrokerType.LeadAccountBroker =>
                throw new IllegalArgumentException("The LeadAccountBroker can not be configured.")

            case BrokerType.SimulatedAccountBroker => 
                val accountId = brokerConfig.getString("account-id")
                val riskPerTrade = brokerConfig.getDouble("risk-per-trade")
                val feePerESContract: Double = brokerConfig.getDouble("fee-per-es-contract")
                val feePerMESContract: Double = brokerConfig.getDouble("fee-per-mes-contract")
                new SimulatedAccountBroker(accountId, riskPerTrade, feePerESContract, feePerMESContract)

            case BrokerType.TradovateAccountBroker =>
                val brokerName = brokerConfig.getString("account-id")
                val riskPerTrade = brokerConfig.getDouble("risk-per-trade")
                val feePerESContract: Double = brokerConfig.getDouble("fee-per-es-contract")
                val feePerMESContract: Double = brokerConfig.getDouble("fee-per-mes-contract")
                val userName = brokerConfig.getString("tradovate-username")
                val clientId = brokerConfig.getString("tradovate-client-id")
                val accountId = brokerConfig.getLong("tradovate-account-id")
                val accountName = brokerConfig.getString("tradovate-account-name")
                val baseUrl = brokerConfig.getString("tradovate-base-url")
                val password = sys.env.getOrElse("TRADOVATE_PASS", throw new IllegalArgumentException("env TRADOVATE_PASS missing."))
                val key = sys.env.getOrElse("TRADOVATE_KEY", throw new IllegalArgumentException("env TRADOVATE_KEY missing."))
                val device = sys.env.getOrElse("TRADOVATE_DEVICE", throw new IllegalArgumentException("env TRADOVATE_DEVICE missing."))
                val broker = new TradovateBroker(
                    username = userName,
                    password = password,
                    clientId = clientId,
                    clientSecret = key,
                    deviceId = device,
                    accountId = accountId,
                    accountSpec = accountName,
                    baseUrl = baseUrl
                )
                new TradovateAccountBroker(
                    accountId = brokerName,
                    riskPerTrade = riskPerTrade,
                    feePerESContract = feePerESContract,
                    feePerMESContract = feePerMESContract,
                    tradovateBroker = broker
                )
        }
    }
}
