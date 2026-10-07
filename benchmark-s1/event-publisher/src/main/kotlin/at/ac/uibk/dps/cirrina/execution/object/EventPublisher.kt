package at.ac.uibk.dps.cirrina.execution.`object`

import at.ac.uibk.dps.cirrina.csm.Csml.EventChannel
import at.ac.uibk.dps.cirrina.execution.util.Serializer
import at.ac.uibk.dps.cirrina.spec.ContextVariable
import at.ac.uibk.dps.cirrina.spec.Event
import io.zenoh.Config
import io.zenoh.Zenoh
import io.zenoh.bytes.ZBytes
import io.zenoh.ext.CacheConfig
import io.zenoh.ext.HeartbeatMode
import io.zenoh.ext.HistoryConfig
import io.zenoh.ext.MissDetectionConfig
import io.zenoh.ext.RecoveryConfig
import io.zenoh.ext.RecoveryMode
import io.zenoh.keyexpr.KeyExpr
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong
import kotlin.time.Clock
import org.slf4j.LoggerFactory

val executorService: ScheduledExecutorService = Executors.newScheduledThreadPool(18)

val zenohConfig =
  System.getenv("ZENOH_CONFIG_URI")?.let { path -> Config.fromFile(File(path)).getOrThrow() }
    ?: Config.default()
val zenohSession = Zenoh.open(zenohConfig).getOrThrow()
val startBeamInterruptionTopic = "eBeamInterruptedStart"
val zenohStartPublisher =
  zenohSession
    .declareAdvancedPublisher(
      KeyExpr.tryFrom("events/peripheral/$startBeamInterruptionTopic").getOrThrow(),
      cacheConfig = CacheConfig(1000L),
      sampleMissDetection = MissDetectionConfig(HeartbeatMode.PeriodicHeartbeat(500L)),
      publisherDetection = true,
    )
    .getOrThrow()

val publishStartDelayMs: Long = System.getenv("PUBLISH_START_DELAY")?.toLong() ?: 0L
val partArrivalRatePerSec: Double = System.getenv("PART_ARRIVAl_RATE_PER_SEC")?.toDouble() ?: 1.0
val publishMode = System.getenv("PUBLISH_MODE")?.toInt() ?: 0

val logger = LoggerFactory.getLogger("at.ac.uibk.dps.cirrina.execution.object.EventPublisherKt")

val latch = CountDownLatch(1)

fun main() {
  logger.info("Part arrival rate = $partArrivalRatePerSec/sec")

  val jobDoneSubscriber =
    zenohSession
      .declareAdvancedSubscriber(
        KeyExpr.tryFrom("events/jobController/eJobDone").getOrThrow(),
        subscriberDetection = true,
        recoveryConfig = RecoveryConfig(RecoveryMode.Heartbeat),
        historyConfig = HistoryConfig(detectLatePublishers = true),
        callback = { _ ->
          logger.info("Stopping Publish")
          latch.countDown()
        },
      )
      .getOrThrow()

  val startTime = System.nanoTime() + (publishStartDelayMs * 1_000_000L)
  scheduleNextArrival(startTime.toDouble())

  latch.await()

  executorService.shutdownNow()
  zenohStartPublisher.close()
  zenohSession.close()
}

fun emitStartBeam() {
  try {
    val beamInterruptedStartEvent =
      Event(
        startBeamInterruptionTopic,
        EventChannel.PERIPHERAL,
        data = listOf(ContextVariable("detected", true)),
        source = "service",
        target = "assemblyController",
        emittedTime = getEmitTime(),
      )
    val eventPayload = ZBytes.from(Serializer.serialize(beamInterruptedStartEvent))

    zenohStartPublisher.put(eventPayload).onFailure { exe ->
      logger.error("failed to send event '$beamInterruptedStartEvent'", exe)
    }
  } catch (exe: Exception) {
    logger.error(exe.message, exe)
  }
}

fun scheduleNextArrival(prevArrivalTime: Double) {
  val newArrivalTime: Double =
    prevArrivalTime + getNextArrivalInterval(partArrivalRatePerSec) * 1_000_000_000.0
  val delay = maxOf(0L, newArrivalTime.roundToLong() - System.nanoTime())

  executorService.schedule(
    {
      emitStartBeam()
      scheduleNextArrival(newArrivalTime)
    },
    delay,
    TimeUnit.NANOSECONDS,
  )
}

fun getNextArrivalInterval(arrivalRate: Double): Double {
  if (publishMode == 1) return 1.0 / arrivalRate

  val expo = ThreadLocalRandom.current().nextExponential()
  return expo / arrivalRate
}

fun getEmitTime(): Long {
  val now = Clock.System.now()

  return (now.epochSeconds * 1_000_000_000L) + now.nanosecondsOfSecond
}
