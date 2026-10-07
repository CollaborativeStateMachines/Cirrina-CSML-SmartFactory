package at.ac.uibk.dps.cirrina.execution.`object`

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import at.ac.uibk.dps.cirrina.csm.Csml.EventChannel
import at.ac.uibk.dps.cirrina.execution.util.Serializer
import at.ac.uibk.dps.cirrina.spec.ContextVariable
import at.ac.uibk.dps.cirrina.spec.Event
import com.sun.net.httpserver.HttpServer
import io.zenoh.Config
import io.zenoh.Session
import io.zenoh.Zenoh
import io.zenoh.bytes.ZBytes
import io.zenoh.ext.CacheConfig
import io.zenoh.ext.HeartbeatMode
import io.zenoh.ext.MissDetectionConfig
import io.zenoh.keyexpr.KeyExpr
import io.zenoh.pubsub.AdvancedPublisher
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.use
import org.slf4j.LoggerFactory

val executorService: ScheduledExecutorService = Executors.newScheduledThreadPool(8)

val zenohConfig =
  System.getenv("ZENOH_CONFIG_URI")?.let { path -> Config.fromFile(File(path)).getOrThrow() }
    ?: Config.default()

val logger = LoggerFactory.getLogger("at.ac.uibk.dps.cirrina.execution.object.FactoryServiceKt")

val serviceRole =
  System.getenv("SERVICE_ROLE")
    ?: "" // The state machine role that the process provides services for

fun main() {
  try {
    val httpServer = HttpServer.create(InetSocketAddress(6000), 0)

    when (serviceRole) {
      "monitor" -> registerMonitorEndpoints(httpServer)
      "mp" -> registerMessageProcessorEndpoints(httpServer)
      "belt" -> registerBeltEndpoints(httpServer)
      "arm" -> registerArmEndpoints(httpServer)
      "ac" -> registerAssemblyControllerEndpoints(httpServer)
      else -> throw IllegalArgumentException("Unknown SERVICE_ROLE: $serviceRole")
    }

    httpServer.start()

    logger.info("Http Server Started at http://localhost:6000")
  } catch (e: Exception) {
    logger.error("Failed to start service", e)
    throw e
  }
}

fun registerMonitorEndpoints(httpServer: HttpServer) {
  httpServer.createContext("/statistics") { exchange ->
    var reqData: List<ContextVariable> = emptyList()

    exchange.use { exchange ->
      reqData = Serializer.deserialize(exchange.requestBody.readAllBytes())

      exchange.sendResponseHeaders(200, -1)
    }

    val statisticsSb = buildString {
      append("\nReceived statistics: ")

      reqData.forEach { cv -> append("\n${cv.name} = ${cv.value}") }
    }

    logger.info(statisticsSb)
  }

  // Adding cleanup code
  Runtime.getRuntime()
    .addShutdownHook(
      Thread {
        shutdownServer(httpServer)
        shutdownExecutorService()
      }
    )

  logger.info("Registered endpoints for Monitor")
}

fun registerMessageProcessorEndpoints(httpServer: HttpServer) {
  httpServer.createContext("/process/email") { exchange ->
    exchange.use { exchange ->
      val cv = Serializer.deserialize<List<ContextVariable>>(exchange.requestBody.readAllBytes())[0]

      exchange.sendResponseHeaders(200, -1)

      logger.info("\nSending email with msg: ${cv.value as String}")
    }
  }

  httpServer.createContext("/process/sms") { exchange ->
    exchange.use { exchange ->
      val cv = Serializer.deserialize<List<ContextVariable>>(exchange.requestBody.readAllBytes())[0]

      exchange.sendResponseHeaders(200, -1)

      logger.info("\nSending sms with msg: ${cv.value as String}")
    }
  }

  Runtime.getRuntime()
    .addShutdownHook(
      Thread {
        shutdownServer(httpServer)
        shutdownExecutorService()
      }
    )

  logger.info("Registered endpoints for MessageProcessor")
}

fun registerBeltEndpoints(httpServer: HttpServer) {
  // Initializing configuration parameters
  val beltMovementTimeMs: Long = 400

  val zenohSession = Zenoh.open(zenohConfig).getOrThrow()

  // Initializing event topics
  val endBeamInterruptionTopic = "eBeamInterruptedEnd"

  // Declaring publishers
  val zenohEndBeamInterruptionPublisher =
    zenohSession
      .declareAdvancedPublisher(
        KeyExpr.tryFrom("events/peripheral/$endBeamInterruptionTopic").getOrThrow(),
        cacheConfig = CacheConfig(1000L),
        sampleMissDetection = MissDetectionConfig(HeartbeatMode.PeriodicHeartbeat(500L)),
        publisherDetection = true,
      )
      .getOrThrow()

  httpServer.createContext("/movebelt") { exchange ->
    exchange.use { exchange.sendResponseHeaders(200, -1) }

    executorService.schedule(
      {
        val endBeamInterruptedEvent =
          Event(
            endBeamInterruptionTopic,
            EventChannel.PERIPHERAL,
            data = listOf(ContextVariable("success", true)),
            source = "service",
            target = "assemblyController",
            emittedTime = getEmitTime(),
          )

        emitEvent(endBeamInterruptedEvent, zenohEndBeamInterruptionPublisher)
      },
      beltMovementTimeMs,
      TimeUnit.MILLISECONDS,
    )
  }

  httpServer.createContext("/stopbelt") { exchange ->
    exchange.use { exchange.sendResponseHeaders(200, -1) }
  }

  Runtime.getRuntime()
    .addShutdownHook(
      Thread {
        shutdownServer(httpServer)
        shutdownExecutorService()
        shutdownZenohPublishers(zenohEndBeamInterruptionPublisher)
        shutdownZenohSession(zenohSession)
      }
    )

  logger.info("Registered endpoints for Belt")
}

fun registerArmEndpoints(httpServer: HttpServer) {
  // Initializing configuration parameters
  val pickupMinFailureProb: Double = 0.0
  val pickupMaxFailureProb: Double = 0.0
  val assemblyMinFailureProb: Double = 0.0
  val assemblyMaxFailureProb: Double = 0.0
  val pickupTimeMs: Long = 100L
  val assemblyTimeMs: Long = 1000L
  val armResetTimeMs: Long = 500

  val zenohSession = Zenoh.open(zenohConfig).getOrThrow()

  // Initializing event topics
  val armPickupTopic = "eUpdatePickupStatus"
  val assemblyTopic = "eCheckAssembleSuccess"
  val armResetTopic = "eResetArm"

  // Declaring publishers
  val zenohArmPickupPublisher =
    zenohSession
      .declareAdvancedPublisher(
        KeyExpr.tryFrom("events/peripheral/$armPickupTopic").getOrThrow(),
        cacheConfig = CacheConfig(1000L),
        sampleMissDetection = MissDetectionConfig(HeartbeatMode.PeriodicHeartbeat(500L)),
        publisherDetection = true,
      )
      .getOrThrow()

  val zenohAssemblyPublisher =
    zenohSession
      .declareAdvancedPublisher(
        KeyExpr.tryFrom("events/peripheral/$assemblyTopic").getOrThrow(),
        cacheConfig = CacheConfig(1000L),
        sampleMissDetection = MissDetectionConfig(HeartbeatMode.PeriodicHeartbeat(500L)),
        publisherDetection = true,
      )
      .getOrThrow()

  val zenohArmResetPublisher =
    zenohSession
      .declareAdvancedPublisher(
        KeyExpr.tryFrom("events/peripheral/$armResetTopic").getOrThrow(),
        cacheConfig = CacheConfig(1000L),
        sampleMissDetection = MissDetectionConfig(HeartbeatMode.PeriodicHeartbeat(500L)),
        publisherDetection = true,
      )
      .getOrThrow()

  val pickupOpsCount = AtomicLong(0)
  httpServer.createContext("/pickup") { exchange ->
    exchange.use { exchange.sendResponseHeaders(200, -1) }

    executorService.schedule(
      {
        val opCount =
          pickupOpsCount.updateAndGet { curr -> if (curr == Long.MAX_VALUE) curr else curr + 1 }

        val failureProb =
          getOperationWeibullFailureProb(pickupMinFailureProb, pickupMaxFailureProb, opCount)

        val rand = ThreadLocalRandom.current().nextDouble()
        val pickupSuccess = rand >= failureProb

        val pickupEvent =
          Event(
            armPickupTopic,
            EventChannel.PERIPHERAL,
            data = listOf(ContextVariable("success", pickupSuccess)),
            source = "service",
            target = "arm",
            emittedTime = getEmitTime(),
          )

        emitEvent(pickupEvent, zenohArmPickupPublisher)
      },
      pickupTimeMs,
      TimeUnit.MILLISECONDS,
    )
  }

  val assemblyOpsCount = AtomicLong(0)
  httpServer.createContext("/assemble") { exchange ->
    exchange.use { exchange.sendResponseHeaders(200, -1) }

    executorService.schedule(
      {
        val opCount =
          assemblyOpsCount.updateAndGet { curr -> if (curr == Long.MAX_VALUE) curr else curr + 1 }

        val failureProb =
          getOperationWeibullFailureProb(assemblyMinFailureProb, assemblyMaxFailureProb, opCount)

        val rand = ThreadLocalRandom.current().nextDouble()
        val assemblySuccess = rand >= failureProb

        val assemblyEvent =
          Event(
            assemblyTopic,
            EventChannel.PERIPHERAL,
            data = listOf(ContextVariable("success", assemblySuccess)),
            source = "service",
            target = "arm",
            emittedTime = getEmitTime(),
          )

        emitEvent(assemblyEvent, zenohAssemblyPublisher)
      },
      assemblyTimeMs,
      TimeUnit.MILLISECONDS,
    )
  }

  httpServer.createContext("/returntostart") { exchange ->
    exchange.use { exchange.sendResponseHeaders(200, -1) }

    executorService.schedule(
      {
        val armResetEvent =
          Event(
            armResetTopic,
            EventChannel.PERIPHERAL,
            data = listOf(ContextVariable("success", true)),
            source = "service",
            target = "arm",
            emittedTime = getEmitTime(),
          )

        emitEvent(armResetEvent, zenohArmResetPublisher)
      },
      armResetTimeMs,
      TimeUnit.MILLISECONDS,
    )
  }

  Runtime.getRuntime()
    .addShutdownHook(
      Thread {
        shutdownServer(httpServer)
        shutdownExecutorService()
        shutdownZenohPublishers(
          zenohArmPickupPublisher,
          zenohAssemblyPublisher,
          zenohArmResetPublisher,
        )
        shutdownZenohSession(zenohSession)
      }
    )

  logger.info("Registered endpoints for Arm")
}

fun registerAssemblyControllerEndpoints(httpServer: HttpServer) {
  // Initializing configuration parameters
  val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
  val ortSession: OrtSession =
    ortEnv.createSession("models/yolov8n.onnx", OrtSession.SessionOptions())

  val validObjectImageNames: Array<String> =
    arrayOf("test.png", "test2.png", "test5.png", "test6.png")
  val invalidObjectImageNames: Array<String> =
    arrayOf("test3.png", "test4.png", "test7.png", "test8.png")

  val photoCaptureTimeMs: Long = 500
  val photoScanTimeMs: Long = 700
  val validObjProb: Double = 1.0
  val beltMovementTimeMs: Long = 400

  val zenohSession = Zenoh.open(zenohConfig).getOrThrow()

  // Initializing event topics
  val photoCapturedTopic = "ePhotoCaptured"
  val photoScannedTopic = "ePhotoScanned"
  val objectDisposalTopic = "eObjectDiscarded"

  // Declaring publishers
  val zenohPhotoCapturePublisher =
    zenohSession
      .declareAdvancedPublisher(
        KeyExpr.tryFrom("events/peripheral/$photoCapturedTopic").getOrThrow(),
        cacheConfig = CacheConfig(1000L),
        sampleMissDetection = MissDetectionConfig(HeartbeatMode.PeriodicHeartbeat(500L)),
        publisherDetection = true,
      )
      .getOrThrow()

  val zenohPhotoScanPublisher =
    zenohSession
      .declareAdvancedPublisher(
        KeyExpr.tryFrom("events/peripheral/$photoScannedTopic").getOrThrow(),
        cacheConfig = CacheConfig(1000L),
        sampleMissDetection = MissDetectionConfig(HeartbeatMode.PeriodicHeartbeat(500L)),
        publisherDetection = true,
      )
      .getOrThrow()

  val zenohObjectDisposalPublisher =
    zenohSession
      .declareAdvancedPublisher(
        KeyExpr.tryFrom("events/peripheral/$objectDisposalTopic").getOrThrow(),
        cacheConfig = CacheConfig(1000L),
        sampleMissDetection = MissDetectionConfig(HeartbeatMode.PeriodicHeartbeat(500L)),
        publisherDetection = true,
      )
      .getOrThrow()

  httpServer.createContext("/takephoto") { exchange ->
    exchange.use { exchange.sendResponseHeaders(200, -1) }

    executorService.schedule(
      {
        try {
          val photoCaptureEvent =
            Event(
              photoCapturedTopic,
              EventChannel.PERIPHERAL,
              data = mutableListOf(),
              source = "service",
              target = "assemblyController",
              emittedTime = getEmitTime(),
            )

          val rand = ThreadLocalRandom.current().nextDouble()

          if (rand <= validObjProb)
            (photoCaptureEvent.data as MutableList<ContextVariable>).add(
              ContextVariable(
                "data",
                Files.readAllBytes(
                  Paths.get("imgs", "valid", validObjectImageNames[(rand * 100).toInt() % 4])
                ),
              )
            )
          else
            (photoCaptureEvent.data as MutableList<ContextVariable>).add(
              ContextVariable(
                "data",
                Files.readAllBytes(
                  Paths.get("imgs", "invalid", invalidObjectImageNames[(rand * 100).toInt() % 4])
                ),
              )
            )

          emitEvent(photoCaptureEvent, zenohPhotoCapturePublisher)
        } catch (exe: Exception) {
          logger.error("Failed to take photo", exe)
        }
      },
      photoCaptureTimeMs,
      TimeUnit.MILLISECONDS,
    )
  }

  httpServer.createContext("/scanphoto") { exchange ->
    exchange.use {
      try {
        val input =
          Serializer.deserialize<List<ContextVariable>>(exchange.requestBody.readAllBytes())

        if (input.isEmpty() || input[0].name != "imgData")
          throw IllegalArgumentException("Invalid input")

        val imgData =
          input[0].value as? ByteArray ?: throw IllegalArgumentException("Invalid input")

        executorService.schedule(
          {
            try {
              val validObj = detectPart(imgData, intArrayOf(640, 640), ortEnv, ortSession)

              val photoScanEvent =
                Event(
                  photoScannedTopic,
                  EventChannel.PERIPHERAL,
                  data = listOf(ContextVariable("validObject", validObj)),
                  source = "service",
                  target = "assemblyController",
                  emittedTime = getEmitTime(),
                )

              emitEvent(photoScanEvent, zenohPhotoScanPublisher)
            } catch (exe: Exception) {
              logger.error("Failed to scan photo", exe)
            }
          },
          photoScanTimeMs,
          TimeUnit.MILLISECONDS,
        )

        exchange.sendResponseHeaders(200, -1)
      } catch (exe: IllegalArgumentException) {
        logger.error("Failed to scan photo", exe)
        exchange.sendResponseHeaders(400, -1)
      } catch (exe: Exception) {
        logger.error("Failed to scan photo", exe)
        exchange.sendResponseHeaders(500, -1)
      }
    }
  }

  httpServer.createContext("/discardobject") { exchange ->
    exchange.use { exchange.sendResponseHeaders(200, -1) }

    executorService.schedule(
      {
        val objectDisposalEvent =
          Event(
            objectDisposalTopic,
            EventChannel.PERIPHERAL,
            data = listOf(ContextVariable("success", true)),
            source = "service",
            target = "assemblyController",
            emittedTime = getEmitTime(),
          )

        emitEvent(objectDisposalEvent, zenohObjectDisposalPublisher)
      },
      beltMovementTimeMs,
      TimeUnit.MILLISECONDS,
    )
  }

  // Adding cleanup code
  Runtime.getRuntime()
    .addShutdownHook(
      Thread {
        shutdownServer(httpServer)
        shutdownExecutorService()
        shutdownZenohPublishers(
          zenohPhotoCapturePublisher,
          zenohPhotoScanPublisher,
          zenohObjectDisposalPublisher,
        )
        shutdownZenohSession(zenohSession)
        shutdownOrt(ortSession, ortEnv)
      }
    )

  logger.info("Registered endpoints for Assembly Controller")
}

fun emitEvent(event: Event, publisher: AdvancedPublisher) {
  val eventPayload = ZBytes.from(Serializer.serialize(event))

  publisher.put(eventPayload).onFailure { exe ->
    logger.error("failed to send event '$event'", exe)
  }
}

fun detectPart(
  imgData: ByteArray,
  onnxInputDims: IntArray,
  env: OrtEnvironment,
  session: OrtSession,
  confThreshold: Float = 0.25f,
): Boolean {
  try {
    val inputWidth = onnxInputDims[0]
    val inputHeight = onnxInputDims[1]

    val img = ImageIO.read(ByteArrayInputStream(imgData))

    val resized = letterboxImage(img, inputWidth, inputHeight)

    val chwfTensor = toCHWFTensor(resized)

    val inputName = session.inputNames.iterator().next()

    OnnxTensor.createTensor(
        env,
        FloatBuffer.wrap(chwfTensor),
        longArrayOf(1, 3, inputWidth.toLong(), inputHeight.toLong()),
      )
      .use { tensor ->
        session.run(mapOf(inputName to tensor)).use { outputs ->
          val detections = outputs[0].value as Array<Array<FloatArray>>

          for (detection in detections[0]) {
            if (detection.size < 6) continue

            val conf = detection[4]

            if (conf < confThreshold) continue

            val classId = detection[5]

            if (classId == 39f) return true
          }
        }
      }
  } catch (exe: Exception) {
    logger.error("Failed to detect object", exe)
  }

  return false
}

fun toCHWFTensor(img: BufferedImage): FloatArray {
  val tensor = FloatArray(3 * img.width * img.height)

  val offsets = intArrayOf(0, img.width * img.height, 2 * img.width * img.height)

  for (y in 0 until img.height) {
    for (x in 0 until img.width) {
      val rgb = img.getRGB(x, y)

      val r = ((rgb shr 16) and 0xFF) / 255.0f

      val g = ((rgb shr 8) and 0xFF) / 255.0f

      val b = (rgb and 0xFF) / 255.0f

      tensor[offsets[0]++] = r
      tensor[offsets[1]++] = g
      tensor[offsets[2]++] = b
    }
  }

  return tensor
}

fun letterboxImage(src: BufferedImage, targetW: Int, targetH: Int): BufferedImage {

  val scale =
    min(targetW.toDouble() / src.width.toDouble(), targetH.toDouble() / src.height.toDouble())

  val newW = (src.width * scale).roundToInt()

  val newH = (src.height * scale).roundToInt()

  val resizedTmp = src.getScaledInstance(newW, newH, Image.SCALE_SMOOTH)

  val out = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)

  val g: Graphics2D = out.createGraphics()

  g.color = Color(114, 114, 114)

  g.fillRect(0, 0, targetW, targetH)

  val x = (targetW - newW) / 2

  val y = (targetH - newH) / 2

  g.drawImage(resizedTmp, x, y, null)

  g.dispose()

  return out
}

fun getOperationWeibullFailureProb(
  minFailureProb: Double,
  maxFailureProb: Double,
  operation: Long,
  shape: Double = 3.0,
  scale: Double = 100.0,
): Double {
  // Monotonically increasing Weibull-shaped probability

  return minFailureProb +
    (maxFailureProb - minFailureProb) * (1 - exp(-(operation.toDouble() / scale).pow(shape)))
}

fun getEmitTime(): Long {
  val now = Clock.System.now()

  return (now.epochSeconds * 1_000_000_000L) + now.nanosecondsOfSecond
}

fun shutdownServer(httpServer: HttpServer) {
  try {
    httpServer.stop(0)
  } catch (e: Exception) {
    logger.error("Failed to shutdown server", e)
  }
}

fun shutdownExecutorService() {
  try {
    executorService.shutdown()

    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) executorService.shutdownNow()
  } catch (exe: Exception) {
    logger.error("Failed to shutdown executor service", exe)

    executorService.shutdownNow()
  }
}

fun shutdownZenohSession(zenohSession: Session) {
  try {
    zenohSession.close()
  } catch (exe: Exception) {
    logger.error("Failed to shutdown zenoh session", exe)
  }
}

fun shutdownZenohPublishers(vararg publishers: AdvancedPublisher) {
  for (publisher in publishers) {
    try {
      publisher.close()
    } catch (exe: Exception) {
      logger.error("Failed to shutdown zenoh publisher - ${publisher.keyExpr.toString()}", exe)
    }
  }
}

fun shutdownOrt(ortSession: OrtSession, ortEnv: OrtEnvironment) {
  try {
    ortSession.close()
  } catch (exe: Exception) {
    logger.error("Failed to shutdown ort session", exe)
  }

  try {
    ortEnv.close()
  } catch (exe: Exception) {
    logger.error("Failed to shutdown ort environment", exe)
  }
}
