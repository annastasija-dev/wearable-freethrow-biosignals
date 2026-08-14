package lt.vilniustech.basketball.cloud

import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RawUploadManager(
    private val apiFactory: () -> CloudApiClient,
    private val batchSizeByStream: Map<String, Int> = mapOf(
        "accelerometer" to 50,
        "ppg" to 50,
        "heart_rate" to 5,
        "eda" to 5,
        "skin_temperature" to 3,
    ),
    private val flushIntervalMs: Long = 2000,
) {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val buffers = mutableMapOf<String, MutableList<JSONObject>>()
    private val pendingUploads = AtomicInteger(0)
    private var sessionId: String = ""

    @Volatile
    var lastUploadError: String? = null
        private set

    @Volatile
    var uploadedBatches: Int = 0
        private set

    init {
        executor.scheduleAtFixedRate(
            { flush() },
            flushIntervalMs,
            flushIntervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    fun bindSession(sessionId: String) {
        this.sessionId = sessionId
        synchronized(buffers) {
            buffers.clear()
        }
        lastUploadError = null
        uploadedBatches = 0
    }

    fun enqueue(sessionId: String, sample: RawSample) {
        synchronized(buffers) {
            if (this.sessionId.isEmpty()) this.sessionId = sessionId
            val streamBuffer = buffers.getOrPut(sample.stream) { mutableListOf() }
            streamBuffer.add(JSONObject(sample.payload.toString()))
            val batchSize = batchSizeByStream[sample.stream] ?: 20
            if (streamBuffer.size >= batchSize) {
                flushStreamLocked(sample.stream, batchSize)
            }
        }
    }

    fun uploadCapabilities(sessionId: String, report: CapabilitiesReport) {
        submitUpload(
            onFailure = { /* capabilities are not buffered */ },
        ) {
            retry(times = 3) {
                apiFactory().uploadCapabilities(sessionId, report)
            }
        }
    }

    fun flush() {
        synchronized(buffers) {
            buffers.keys.toList().forEach { stream ->
                flushStreamLocked(stream, Int.MAX_VALUE)
            }
        }
    }

    fun awaitPendingUploads(timeoutMs: Long = 20_000) {
        flush()
        waitUntilIdle(timeoutMs)
        flush()
        waitUntilIdle(timeoutMs)
    }

    fun bufferedSampleCount(): Int = synchronized(buffers) {
        buffers.values.sumOf { it.size }
    }

    private fun waitUntilIdle(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (pendingUploads.get() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
    }

    private fun flushStreamLocked(stream: String, maxItems: Int) {
        val streamBuffer = buffers[stream] ?: return
        if (streamBuffer.isEmpty() || sessionId.isEmpty()) return

        val takeCount = minOf(streamBuffer.size, maxItems)
        val batch = streamBuffer.take(takeCount).toList()
        repeat(takeCount) { streamBuffer.removeAt(0) }

        submitUpload(
            onFailure = {
                synchronized(buffers) {
                    buffers.getOrPut(stream) { mutableListOf() }.addAll(0, batch)
                }
            },
        ) {
            retry(times = 3) {
                apiFactory().uploadRawBatch(sessionId, stream, batch)
            }
            uploadedBatches++
        }
    }

    private fun submitUpload(
        onFailure: () -> Unit = {},
        block: () -> Unit,
    ) {
        pendingUploads.incrementAndGet()
        executor.execute {
            try {
                block()
                lastUploadError = null
            } catch (error: Exception) {
                lastUploadError = error.message ?: error.javaClass.simpleName
                onFailure()
            } finally {
                pendingUploads.decrementAndGet()
            }
        }
    }

    private fun retry(times: Int, block: () -> Unit) {
        var last: Exception? = null
        repeat(times) { attempt ->
            try {
                block()
                return
            } catch (error: Exception) {
                last = error
                if (attempt < times - 1) {
                    Thread.sleep((attempt + 1) * 500L)
                }
            }
        }
        throw last ?: IllegalStateException("upload failed")
    }

    fun shutdown() {
        awaitPendingUploads()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}

/** Backward-compatible alias. */
typealias ImuUploadManager = RawUploadManager
