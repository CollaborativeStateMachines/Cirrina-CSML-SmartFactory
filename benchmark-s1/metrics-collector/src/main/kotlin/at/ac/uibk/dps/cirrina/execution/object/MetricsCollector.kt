package at.ac.uibk.dps.cirrina.execution.`object`

import at.ac.uibk.dps.cirrina.execution.util.Serializer
import at.ac.uibk.dps.cirrina.spec.Event
import io.zenoh.Config
import io.zenoh.Zenoh
import io.zenoh.ext.HistoryConfig
import io.zenoh.ext.RecoveryConfig
import io.zenoh.ext.RecoveryMode
import io.zenoh.keyexpr.KeyExpr
import java.io.File
import java.io.PrintWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

val zenohConfig =
  System.getenv("ZENOH_CONFIG_URI")?.let { path -> Config.fromFile(File(path)).getOrThrow() }
    ?: Config.default()

val zenohSession = Zenoh.open(zenohConfig).getOrThrow()

val productionStartTime = AtomicLong(0)

val runId = System.getenv("RUN_ID") ?: "0"

val metricsFiles =
  arrayOf(File("./metrics/ProductionTimes_run_${runId}.csv"), File("./metrics/arrival.csv"))

val metricFileWriters = arrayOf(PrintWriter(metricsFiles[0]), PrintWriter(metricsFiles[1]))

val obsCount = arrayOf(AtomicInteger(0))

val firstArrivalTime = AtomicLong(Long.MAX_VALUE)
val lastArrivalTime = AtomicLong(Long.MIN_VALUE)
val arrivalCount = AtomicInteger(0)

val logger = LoggerFactory.getLogger("at.ac.uibk.dps.cirrina.execution.object.MetricsCollectorKt")

val shutdownLatch = CountDownLatch(1)

fun main() {

  zenohSession
    .declareAdvancedSubscriber(
      KeyExpr.tryFrom("events/peripheral/eBeamInterruptedStart").getOrThrow(),
      subscriberDetection = true,
      recoveryConfig = RecoveryConfig(RecoveryMode.Heartbeat),
      historyConfig = HistoryConfig(detectLatePublishers = true),
      callback = { sample ->
        val arrivalTime = System.nanoTime()

        firstArrivalTime.accumulateAndGet(arrivalTime) { current, value -> minOf(current, value) }

        lastArrivalTime.accumulateAndGet(arrivalTime) { current, value -> maxOf(current, value) }

        arrivalCount.incrementAndGet()
      },
    )
    .getOrThrow()

  zenohSession
    .declareAdvancedSubscriber(
      KeyExpr.tryFrom("events/assemblyController/eProductionStarted").getOrThrow(),
      subscriberDetection = true,
      recoveryConfig = RecoveryConfig(RecoveryMode.Heartbeat),
      historyConfig = HistoryConfig(detectLatePublishers = true),
      callback = { sample ->
        val event = Serializer.deserialize<Event>(sample.payload.toBytes())

        productionStartTime.set(event.emittedTime)

        logger.info("Production started at ${productionStartTime.get()}")
      },
    )
    .getOrThrow()

  zenohSession
    .declareAdvancedSubscriber(
      KeyExpr.tryFrom("events/jobController/eJobDone").getOrThrow(),
      subscriberDetection = true,
      recoveryConfig = RecoveryConfig(RecoveryMode.Heartbeat),
      historyConfig = HistoryConfig(detectLatePublishers = true),
      callback = { sample ->
        val event = Serializer.deserialize<Event>(sample.payload.toBytes())

        val productionTime = (event.emittedTime - productionStartTime.get()).coerceAtLeast(0L)

        logger.info("Production time: ${productionTime / 1_000_000_000L} seconds")

        metricFileWriters[0].println(
          "index,start_time_ns,end_time_ns,completion_time_ns,completion_time_s"
        )
        metricFileWriters[0].println(
          "${obsCount[0].incrementAndGet()}," +
            "${productionStartTime.get()}," +
            "${event.emittedTime}," +
            "$productionTime," +
            "${productionTime / 1_000_000_000L}"
        )

        metricFileWriters[0].flush()

        val count = arrivalCount.get()

        if (count > 1) {
          val firstArrival = firstArrivalTime.get()

          val lastArrival = lastArrivalTime.get()

          val arrivalDurationSeconds = (lastArrival - firstArrival) / 1_000_000_000.0

          val arrivalRate = (count - 1) / arrivalDurationSeconds

          logger.info("Observed arrival rate: $arrivalRate events/second")

          metricFileWriters[1].println(
            "index,count,first_arrival_time_ns,last_arrival_time_ns,arrival_duration_s,arrival_rate_per_s"
          )
          metricFileWriters[1].println(
            "$runId," +
              "$count," +
              "$firstArrival," +
              "$lastArrival," +
              "$arrivalDurationSeconds," +
              "$arrivalRate"
          )

          metricFileWriters[1].flush()
        }

        firstArrivalTime.set(Long.MAX_VALUE)
        lastArrivalTime.set(Long.MIN_VALUE)
        arrivalCount.set(0)
      },
    )
    .getOrThrow()

  logger.info("Metrics collector started")

  shutdownLatch.await()

  for (writer in metricFileWriters) {
    writer.flush()
    writer.close()
  }
}
