package bmps.core.api.storage

import cats.effect.{IO, Ref}
import cats.implicits._
import bmps.core.models.{Event, SystemStatePhase}
import java.time.LocalDate

/**
 * EventStore maintains an in-memory store of events keyed by trading date and phase.
 * Each entry tracks a list of events and a completion flag.
 * 
 * Thread-safe storage using Ref[IO, ...] for concurrent access.
 */
class EventStore private (
    storeRef: Ref[IO, Map[(LocalDate, SystemStatePhase), (List[Event], Boolean)]]
) {

  /**
   * Add a single event to the store for the given trading date and phase.
   * If the phase is already marked complete, this is a no-op.
   */
  def addEvent(tradingDate: LocalDate, phase: SystemStatePhase, event: Event): IO[Unit] = {
    storeRef.update { store =>
      val key = (tradingDate, phase)
      val (events, isComplete) = store.getOrElse(key, (List.empty, false))
      if (isComplete) {
        // Phase already complete, don't add more events
        store
      } else {
        store.updated(key, (events :+ event, isComplete))
      }
    }
  }

  /**
   * Add multiple events to the store for the given trading date and phase.
   */
  def addEvents(tradingDate: LocalDate, phase: SystemStatePhase, events: List[Event]): IO[Unit] = {
    events.traverse_(event => addEvent(tradingDate, phase, event))
  }

  /**
   * Mark a phase as complete for the given trading date.
   * No more events can be added after this.
   */
  def markComplete(tradingDate: LocalDate, phase: SystemStatePhase): IO[Unit] = {
    storeRef.update { store =>
      val key = (tradingDate, phase)
      val (events, _) = store.getOrElse(key, (List.empty, false))
      store.updated(key, (events, true))
    }
  }

  /**
   * Check if a phase is complete for the given trading date.
   */
  def isComplete(tradingDate: LocalDate, phase: SystemStatePhase): IO[Boolean] = {
    storeRef.get.map { store =>
      val key = (tradingDate, phase)
      store.get(key).map(_._2).getOrElse(false)
    }
  }

  /**
   * Get all events for the given trading date and phase, along with completion status.
   * Returns (events, isComplete).
   */
  def getEvents(tradingDate: LocalDate, phase: SystemStatePhase): IO[(List[Event], Boolean)] = {
    storeRef.get.flatMap { store =>
      val key = (tradingDate, phase)
      val result = store.getOrElse(key, (List.empty, false))
      val eventCounts = result._1.groupBy(_.eventType).view.mapValues(_.size).toMap
      // IO.println(s"Event counts for $tradingDate $phase: $eventCounts") *> 
      IO.pure(result)
    }
  }

  /**
   * Clear all stored events for the given trading date and phase.
   * Useful for reset operations.
   */
  def clear(tradingDate: LocalDate, phase: SystemStatePhase): IO[Unit] = {
    storeRef.update { store =>
      store - ((tradingDate, phase))
    }
  }

  /**
   * Clear all stored events across all dates and phases.
   */
  def clearAll(): IO[Unit] = {
    storeRef.set(Map.empty)
  }

  /**
   * Get all unique trading dates that have at least one event stored.
   * Returns dates in sorted order (oldest to newest).
   */
  def getAvailableDates(): IO[List[LocalDate]] = {
    storeRef.get.map { store =>
      store.keys
        .map(_._1)  // Extract LocalDate from (LocalDate, SystemStatePhase) tuple
        .toSet      // Remove duplicates
        .toList
        .sorted     // Sort chronologically
    }
  }
}

object EventStore {
  /**
   * Create a new EventStore with empty storage.
   */
  def create(): IO[EventStore] = {
    Ref.of[IO, Map[(LocalDate, SystemStatePhase), (List[Event], Boolean)]](Map.empty)
      .map(new EventStore(_))
  }
}
