package com.gu.util

import monix.eval.Task
import monix.execution.Scheduler
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import monix.execution.Scheduler.Implicits.global
import scala.util.Random

/*
 * See also https://gist.github.com/viktorklang/9414163
 */
object Retry {
  val logger = LoggerFactory.getLogger("Retry")

  object Delays {
    def withJitter(delays: Seq[FiniteDuration], maxJitter: Double, minJitter: Double): Seq[Duration] =
      delays.map(_ * (minJitter + (maxJitter - minJitter) * Random.nextDouble))

    val fibonacci: Stream[FiniteDuration] = 0.seconds #:: 1.seconds #:: (fibonacci zip fibonacci.tail).map{ t => t._1 + t._2 }
  }

  def retry[T](
    desc: String, f: => Future[T],
    delays: Seq[FiniteDuration]
  )(acceptable: T => Boolean): Future[T] = {
    f.filter(acceptable) recoverWith {
      case _ if delays.nonEmpty =>
        val retryDelay = delays.head
        logger.info(s"Will retry '$desc' after $retryDelay")

        Task(retry(desc, f, delays.tail)(acceptable)).delayExecution(retryDelay).runAsync.flatMap(foo => foo)
    }
  }
}