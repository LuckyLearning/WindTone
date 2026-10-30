package com.luckylearning.windtone.data

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Bus 节流与投递：挂载/卸载发重扫；卸载另发 unmount；3s 内合并。
 * 先 launch collect + yield，再 emit，避免 SharedFlow 无 replay 时丢事件。
 */
class StorageHotplugBusTest {

    @Before
    fun reset() {
        StorageHotplugBus.resetForTest()
    }

    @Test
    fun mountedEmitsRescanOnceWithinThrottle() = runBlocking {
        val rescans = mutableListOf<String>()
        val job = launch {
            StorageHotplugBus.rescanRequests.collect { rescans.add(it) }
        }
        yield()
        StorageHotplugBus.onMounted("/storage/A", nowMs = 10_000L)
        StorageHotplugBus.onMounted("/storage/B", nowMs = 10_500L) // 节流内丢弃
        yield()
        assertEquals(listOf("/storage/A"), rescans)
        assertEquals(10_000L, StorageHotplugBus.lastRescanRequestAtMs)
        job.cancel()
    }

    @Test
    fun unmountedEmitsUnmountAndRescan() = runBlocking {
        val unmounts = mutableListOf<String>()
        val rescans = mutableListOf<String>()
        val j1 = launch { StorageHotplugBus.unmountEvents.collect { unmounts.add(it) } }
        val j2 = launch { StorageHotplugBus.rescanRequests.collect { rescans.add(it) } }
        yield()
        StorageHotplugBus.onUnmounted("/storage/USB", nowMs = 20_000L)
        yield()
        assertEquals(listOf("/storage/USB"), unmounts)
        assertEquals(listOf("/storage/USB"), rescans)
        j1.cancel()
        j2.cancel()
    }

    @Test
    fun unmountStillNotifiesWhenRescanThrottled() = runBlocking {
        val unmounts = mutableListOf<String>()
        val rescans = mutableListOf<String>()
        val j1 = launch { StorageHotplugBus.unmountEvents.collect { unmounts.add(it) } }
        val j2 = launch { StorageHotplugBus.rescanRequests.collect { rescans.add(it) } }
        yield()
        StorageHotplugBus.onMounted("/storage/A", nowMs = 30_000L)
        yield()
        StorageHotplugBus.onUnmounted("/storage/A", nowMs = 30_100L)
        yield()
        assertEquals(listOf("/storage/A"), unmounts)
        assertEquals(listOf("/storage/A"), rescans) // 第二次重扫被节流
        assertEquals(30_000L, StorageHotplugBus.lastRescanRequestAtMs)
        j1.cancel()
        j2.cancel()
    }

    @Test
    fun emptyPathAllowedOnUnmount() = runBlocking {
        val unmounts = mutableListOf<String>()
        val job = launch { StorageHotplugBus.unmountEvents.collect { unmounts.add(it) } }
        yield()
        StorageHotplugBus.onUnmounted(null, nowMs = 40_000L)
        yield()
        assertEquals(listOf(""), unmounts)
        assertTrue(StorageHotplugBus.lastRescanRequestAtMs == 40_000L)
        job.cancel()
    }
}
