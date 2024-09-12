package com.itmo.microservices.demo.common

import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RateLimiter(
    private val rate: Int,
    private val timeUnit: TimeUnit = TimeUnit.MINUTES,
    private val slowStartOn: Boolean = true,
) {
    companion object {
        private val logger: Logger = LoggerFactory.getLogger(RateLimiter::class.java)
        private val counter = AtomicInteger(0)
        private val rateLimiterScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    }

    @Volatile
    private var effectiveRate = if (slowStartOn) 1 else rate

    @Volatile
    private var semaphore = Semaphore(effectiveRate)
    private val semaphoreNumber = counter.getAndIncrement()

    private val releaseJob = rateLimiterScope.launch {
        while (true) {
            val start = System.currentTimeMillis()
            val permitsToRelease = effectiveRate - semaphore.availablePermits
            repeat(permitsToRelease) {
                runCatching {
                    semaphore.release()
                }.onFailure { th -> logger.error("Failed while releasing permits", th) }
            }
            logger.warn("Semaphore ${semaphoreNumber}. Released $permitsToRelease permits")

            if (slowStartOn && effectiveRate < rate) {
                effectiveRate = minOf(rate, effectiveRate + 100)
                semaphore = Semaphore(effectiveRate)
            }

            delay(timeUnit.toMillis(1) - (System.currentTimeMillis() - start))
        }
    }.invokeOnCompletion { th -> if (th != null) logger.error("Rate limiter release job completed", th) }

    fun tick() = semaphore.tryAcquire()

    suspend fun tickBlocking() = semaphore.acquire()
}

class CountingRateLimiter(
    private val rate: Int,
    private val timeUnit: TimeUnit = TimeUnit.SECONDS
) {
    companion object {
        private val logger: Logger = LoggerFactory.getLogger(CountingRateLimiter::class.java)
    }

    var internal = RlInternal()

    @Synchronized
    fun tick(): Boolean {
        val now = System.currentTimeMillis()
        if (now - internal.segmentStart > timeUnit.toMillis(1)) {
            internal = RlInternal(now, rate - 1)
            return true
        } else {
            if (internal.permits > 0) {
                internal.permits--
                return true
            } else {
                return false
            }
        }
    }

//    fun tickBlocking() = semaphore.acquire()

    class RlInternal(
        var segmentStart: Long = System.currentTimeMillis(),
        var permits: Int = 0,
    )
}

fun makeRateLimiter(accountName: String, rate: Int, timeUnit: TimeUnit = TimeUnit.SECONDS): io.github.resilience4j.ratelimiter.RateLimiter {
    val config = RateLimiterConfig.custom()
        .limitRefreshPeriod(if (timeUnit == TimeUnit.SECONDS) Duration.ofSeconds(1) else Duration.ofMinutes(1))
        .limitForPeriod(rate)
        .timeoutDuration(Duration.ofMillis(5))
        .build()

    val rateLimiterRegistry = RateLimiterRegistry.of(config)

    return rateLimiterRegistry.rateLimiter("rateLimiter:${accountName}")
}