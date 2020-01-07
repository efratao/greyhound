package com.wixpress.dst.greyhound.core.consumer

import com.wixpress.dst.greyhound.core._
import com.wixpress.dst.greyhound.core.consumer.retry.RetryPolicy
import com.wixpress.dst.greyhound.core.metrics.GreyhoundMetric.GreyhoundMetrics
import com.wixpress.dst.greyhound.core.metrics.{GreyhoundMetric, Metrics}
import com.wixpress.dst.greyhound.core.producer.{Producer, ProducerError}
import zio._
import zio.clock.Clock

trait RecordHandler[-R, +E, K, V] { self =>
  def topics: Set[TopicName]

  def handle(record: Record[K, V]): ZIO[R, E, Any]

  def contramap[K2, V2](f: Record[K2, V2] => Record[K, V]): RecordHandler[R, E, K2, V2] =
    contramapM(record => ZIO.succeed(f(record)))

  def contramapM[R1 <: R, E1 >: E, K2, V2](f: Record[K2, V2] => ZIO[R1, E1, Record[K, V]]): RecordHandler[R1, E1, K2, V2] =
    new RecordHandler[R1, E1, K2, V2] {
      override def topics: Set[TopicName] = self.topics
      override def handle(record: Record[K2, V2]): ZIO[R1, E1, Any] =
        f(record).flatMap(self.handle)
    }

  def mapError[E2](f: E => E2): RecordHandler[R, E2, K, V] =
    new RecordHandler[R, E2, K, V] {
      override def topics: Set[TopicName] = self.topics
      override def handle(record: Record[K, V]): ZIO[R, E2, Any] =
        self.handle(record).mapError(f)
    }

  def withErrorHandler[R1 <: R, E2](f: E => ZIO[R1, E2, Any]): RecordHandler[R1, E2, K, V] =
    new RecordHandler[R1, E2, K, V] {
      override def topics: Set[TopicName] = self.topics
      override def handle(record: Record[K, V]): ZIO[R1, E2, Any] =
        self.handle(record).catchAll(f)
    }

  def ignore: RecordHandler[R, Nothing, K, V] =
    withErrorHandler(_ => ZIO.unit)

  def provide(r: R): RecordHandler[Any, E, K, V] =
    new RecordHandler[Any, E, K, V] {
      override def topics: Set[TopicName] = self.topics
      override def handle(record: Record[K, V]): ZIO[Any, E, Any] =
        self.handle(record).provide(r)
    }

  def andThen[R1 <: R, E1 >: E](f: Record[K, V] => ZIO[R1, E1, Any]): RecordHandler[R1, E1, K, V] =
    new RecordHandler[R1, E1, K, V] {
      override def topics: Set[TopicName] = self.topics
      override def handle(record: Record[K, V]): ZIO[R1, E1, Any] =
        self.handle(record) *> f(record)
    }

  def combine[R1 <: R, E1 >: E](other: RecordHandler[R1, E1, K, V]): RecordHandler[R1, E1, K, V] =
    new RecordHandler[R1, E1, K, V] {
      type Handler = Record[K, V] => ZIO[R1, E1, Any]

      private val handlerByTopic: Map[TopicName, Handler] =
        List(self, other).foldLeft(Map.empty[TopicName, Handler]) { (acc, handler) =>
          handler.topics.foldLeft(acc) { (acc1, topic) =>
            val newHandler = acc1.get(topic).fold[Handler](handler.handle) { oldHandler =>
              record => oldHandler(record) &> handler.handle(record)
            }
            acc1 + (topic -> newHandler)
          }
        }

      override def topics: Set[TopicName] = self.topics union other.topics

      override def handle(record: Record[K, V]): ZIO[R1, E1, Any] =
        handlerByTopic.get(record.topic) match {
          case Some(handler) => handler(record)
          case None => ZIO.unit
        }
    }

  def deserialize(keyDeserializer: Deserializer[K],
                  valueDeserializer: Deserializer[V]): RecordHandler[R, Either[SerializationError, E], Chunk[Byte], Chunk[Byte]] =
    mapError(Right(_)).contramapM { record =>
      (for {
        key <- ZIO.foreach(record.key)(keyDeserializer.deserialize(record.topic, record.headers, _))
        value <- valueDeserializer.deserialize(record.topic, record.headers, record.value)
      } yield Record(
        topic = record.topic,
        partition = record.partition,
        offset = record.offset,
        headers = record.headers,
        key = key.headOption,
        value = value)).mapError(e => Left(SerializationError(e)))
    }

  def withRetries[R2](retryPolicy: RetryPolicy[R2, E], producer: Producer)
                     (implicit evK: K <:< Chunk[Byte], evV: V <:< Chunk[Byte]): RecordHandler[R with R2 with Clock, Either[ProducerError, E], K, V] =
    new RecordHandler[R with R2 with Clock, Either[ProducerError, E], K, V] {
      override def topics: Set[TopicName] = for {
        topic <- self.topics
        retryTopic <- retryPolicy.retryTopics(topic)
      } yield retryTopic

      override def handle(record: Record[K, V]): ZIO[R with R2 with Clock, Either[ProducerError, E], Any] =
        retryPolicy.retryAttempt(record.topic, record.headers).flatMap { retryAttempt =>
          ZIO.foreach_(retryAttempt)(_.sleep) *> self.handle(record).catchAll { e =>
            retryPolicy.retryRecord(retryAttempt, record.bimap(evK, evV), e).flatMap {
              case Some(retryRecord) => producer.produce(retryRecord).mapError(Left(_))
              case None => ZIO.fail(Right(e))
            }
          }
        }
    }

  def parallel(n: Int, queueCapacity: Int = 128): URManaged[R with GreyhoundMetrics, RecordHandler[R with GreyhoundMetrics, E, K, V]] =
    ZManaged.foreach(0 until n)(makeQueue(queueCapacity)).map { queues =>
      new RecordHandler[R with GreyhoundMetrics, E, K, V] {
        override def topics: Set[TopicName] = self.topics
        override def handle(record: Record[K, V]): ZIO[R with GreyhoundMetrics, E, Any] =
          Metrics.report(SubmittingRecord(record)) *>
            queues(record.partition % queues.length).offer(record)
      }
    }

  private def makeQueue(queueCapacity: Int)(i: Int): URManaged[R with GreyhoundMetrics, Queue[Record[K, V]]] = {
    val queue = for {
      _ <- Metrics.report(StartingRecordsProcessor(i))
      queue <- Queue.bounded[Record[K, V]](queueCapacity)
      _ <- queue.take.flatMap { record =>
        Metrics.report(HandlingRecord(record, i)) *>
          self.handle(record)
      }.forever.fork
    } yield queue

    queue.toManaged { queue =>
      Metrics.report(StoppingRecordsProcessor(i)) *>
        queue.shutdown
    }
  }

}

case class SerializationError(cause: Throwable) extends RuntimeException(cause)

object RecordHandler {
  def apply[R, E, K, V](topics: TopicName*)(f: Record[K, V] => ZIO[R, E, Any]): RecordHandler[R, E, K, V] = {
    val topics1 = topics.toSet
    new RecordHandler[R, E, K, V] {
      override def topics: Set[TopicName] = topics1
      override def handle(record: Record[K, V]): ZIO[R, E, Any] = f(record)
    }
  }
}

sealed trait RecordHandlerMetric extends GreyhoundMetric
case class SubmittingRecord[K, V](record: Record[K, V]) extends RecordHandlerMetric
case class StartingRecordsProcessor(processor: Int) extends RecordHandlerMetric
case class StoppingRecordsProcessor(processor: Int) extends RecordHandlerMetric
case class HandlingRecord[K, V](record: Record[K, V], processor: Int) extends RecordHandlerMetric
