package org.constellation.util
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

import cats.effect.IO
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

abstract class PeriodicIO(taskName: String) extends StrictLogging {

  val timerPool: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(1))
  val executionNumber: AtomicLong = new AtomicLong(0)

  def trigger(): IO[Unit]

  def schedule(duration: FiniteDuration): Unit = {
    val delayedTask = IO
      .timer(timerPool)
      .sleep(duration)
      .flatMap(_ => IO(logger.debug(s"triggering periodic task ${taskName}")))
      .flatMap(
        _ =>
          trigger().handleErrorWith { ex =>
            IO(logger.error(s"Error when executing periodic task: $taskName due: ${ex.getMessage}", ex))
        }
      )
    delayedTask
      .unsafeToFuture()
      .onComplete { res =>
        val currNumber = executionNumber.incrementAndGet()
        logger.debug(s"Periodic task: $taskName has finished $res execution number: $currNumber")
        schedule(duration)
      }(timerPool)
  }

}