package bmps.core.services

import cats.effect.IO
import cats.implicits._
import java.time.LocalDate
import bmps.core.api.storage.EventStore
import bmps.core.brokers.{AccountBroker, LeadAccountBroker}
import bmps.core.models.{Event, EventType, SystemStatePhase, SerializableOrder}
import bmps.core.brokers.rest.OrderState
import bmps.core.models.OrderStatus

case class DateProfitability(
  date: LocalDate,
  profitable: Option[Boolean]  // Some(true)=profit, Some(false)=loss, None=neutral
)

class ReportService(
  eventStore: EventStore,
  leadAccountBroker: LeadAccountBroker
) {
  
  /**
   * Get all available dates with profitability status
   * Returns IO because eventStore operations are IO-based
   */
  def getAvailableDatesWithProfitability(
    accountIdOpt: Option[String]
  ): IO[List[DateProfitability]] = {
    for {
      dates <- eventStore.getAvailableDates()
      broker = findBroker(accountIdOpt).getOrElse(leadAccountBroker)
      results <- dates.traverse { date =>
        calculateDateProfitability(date, broker)
      }
    } yield results
  }
  
  /**
   * Get order report for a specific date and account
   * Returns None if broker not found
   */
  def getOrderReport(
    date: LocalDate,
    accountIdOpt: Option[String]
  ): IO[Option[bmps.core.brokers.OrderReport]] = {
    findBroker(accountIdOpt) match {
      case None => IO.pure(None)
      case Some(broker) =>
        eventStore.getEvents(date, SystemStatePhase.Trading).map { case (events, _) =>
          val orders = extractOrdersFromEvents(events)
          val report = broker.orderReport(orders)
          Some(report)
        }
    }
  }
  
  /**
   * Get aggregate order report across all available dates
   * Combines all orders from all trading days into a single report
   * Returns None if broker not found
   */
  def getAggregateOrderReport(
    accountIdOpt: Option[String]
  ): IO[Option[bmps.core.brokers.OrderReport]] = {
    findBroker(accountIdOpt) match {
      case None => IO.pure(None)
      case Some(broker) =>
        for {
          dates <- eventStore.getAvailableDates()
          allOrders <- dates.traverse { date =>
            eventStore.getEvents(date, SystemStatePhase.Trading).map { case (events, _) =>
              extractOrdersFromEvents(events)
            }
          }
          aggregatedOrders = allOrders.flatten
          report = broker.orderReport(aggregatedOrders)
        } yield {
          printScenarioWinLoss(report.orders)
          Some(report)
        }
    }
  }

  private def printScenarioWinLoss(orders: List[SerializableOrder]): Unit = {
    val stats = orders.groupBy(_.entryType).map { case (entryType, groupOrders) =>
      val wins = groupOrders.count(order => order.status == OrderStatus.Profit)
      val losses = groupOrders.count(order => order.status == OrderStatus.Loss)
      val total = wins + losses
      val winRate = if (total > 0) (wins.toDouble / total.toDouble) * 100 else 0.0
      (entryType, wins, losses, winRate)
    }.toList.sortBy(-_._4) // Sort by win rate descending
    
    stats.foreach { case (entryType, wins, losses, winRate) =>
      println(f"${entryType.toString()}%20s: Wins=$wins%3d, Losses=$losses%3d, WinRate=$winRate%6.2f%%")
    }
  }
  
  private def calculateDateProfitability(
    date: LocalDate, 
    broker: AccountBroker
  ): IO[DateProfitability] = {
    eventStore.getEvents(date, SystemStatePhase.Trading).map { case (events, _) =>
      val orders = extractOrdersFromEvents(events)
      val profitable = broker.isProfitable(orders)
      DateProfitability(date, profitable)
    }
  }
  
  private def findBroker(accountIdOpt: Option[String]): Option[AccountBroker] = {
    accountIdOpt match {
      case None => Some(leadAccountBroker)
      case Some(accountId) => 
        if (accountId == leadAccountBroker.accountId) Some(leadAccountBroker)
        else leadAccountBroker.brokers.find(_.accountId == accountId)
    }
  }
  
  private def extractOrdersFromEvents(events: List[Event]): List[SerializableOrder] = {
    val orderEvents = events.filter(_.eventType == EventType.Order)
    val deduplicatedOrders = orderEvents
      .zipWithIndex
      .groupBy(_._1.timestamp)
      .map { case (_, eventsWithIndex) => 
        eventsWithIndex.maxBy(_._2)._1
      }
      .toList
    deduplicatedOrders.flatMap(_.order)
  }
}
