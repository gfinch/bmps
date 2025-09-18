package bmps.core.api.run

import cats.effect.{IO, Ref}
import cats.implicits._
import cats.effect.std.Queue
import fs2.Stream
import bmps.core.models.SystemStatePhase
import bmps.core.models.Event

/**
 * Broadcaster maintains a per-phase replay buffer and a set of subscriber
 * queues. Late subscribers receive the replay buffer for the requested phase
 * followed by live events delivered via a queue.
 */
class Broadcaster private (
    bufferRef: Ref[IO, Map[SystemStatePhase, Vector[Event]]],
    subscribersRef: Ref[IO, List[(SystemStatePhase, Queue[IO, Event])]],
    maxBufferPerPhase: Option[Int]
) {

  /** Publish an event for a given phase. The event is added to the per-phase
    * replay buffer (subject to the optional max size) and then offered to all
    * subscribers that are subscribed to that phase.
    */
  def publish(phase: SystemStatePhase, event: Event): IO[Unit] = for {
    _ <- bufferRef.update { m =>
      val vec = m.getOrElse(phase, Vector.empty) :+ event
      val trimmed = maxBufferPerPhase match {
        case Some(n) if vec.size > n => vec.takeRight(n)
        case _ => vec
      }
      m.updated(phase, trimmed)
    }
    subs <- subscribersRef.get
    // Offer to each subscriber queue; ignore failures to avoid blocking publisher
    _ <- subs.filter(_._1 == phase).traverse_ { case (_, q) => q.offer(event) }
  } yield ()

  /** Subscribe to events for a specific phase. First the replay buffer for
    * that phase is emitted, then the live stream of new events for that phase.
    * The returned Stream completes when the client stops consuming it; the
    * subscriber queue is removed when the stream finalizes.
    */
  def subscribe(phase: SystemStatePhase, queueSize: Int = 256): Stream[IO, Event] =
    Stream.eval(Queue.bounded[IO, Event](queueSize)).flatMap { q =>
      val register = subscribersRef.update(qs => (phase, q) :: qs)
      val unregister = subscribersRef.update(_.filterNot(_._2 == q))

      val replay = Stream.eval(bufferRef.get).flatMap(m => Stream.emits(m.getOrElse(phase, Vector.empty).toList))
      val live = Stream.repeatEval(q.take)

      Stream.eval(register) *> (replay ++ live).onFinalize(unregister)
    }
}

object Broadcaster {
  def create(maxBufferPerPhase: Option[Int] = Some(10000)): IO[Broadcaster] = for {
    buf <- Ref.of[IO, Map[SystemStatePhase, Vector[Event]]](Map.empty)
    subs <- Ref.of[IO, List[(SystemStatePhase, Queue[IO, Event])]](List.empty)
  } yield new Broadcaster(buf, subs, maxBufferPerPhase)
}

