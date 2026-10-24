package com.shilapi.xcertplay.network.backend.lwip

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectBufferPoolArtOomInstrumentationTest {
    @Test(timeout = 30_000)
    fun realArtHeapOomAtAllocatorBoundaryMapsToResourceLimitInSeparateProcess() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val connected = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val result = AtomicReference<Bundle?>()
        val responseThread = HandlerThread("lwip-direct-buffer-oom-result").apply { start() }
        val responseMessenger = Messenger(Handler(responseThread.looper) { message ->
            if (message.what == DirectBufferPoolOomService.MSG_RESULT) {
                result.set(Bundle(message.data))
                completed.countDown()
                true
            } else false
        })
        var remote: Messenger? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote = Messenger(binder)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                failure.compareAndSet(null, AssertionError("OOM service process disconnected"))
                connected.countDown()
                completed.countDown()
            }
        }
        var bound = false
        try {
            bound = context.bindService(
                Intent(context, DirectBufferPoolOomService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            assertTrue("OOM service did not bind", bound)
            assertTrue("OOM service did not connect", connected.await(5, TimeUnit.SECONDS))
            remote!!.send(Message.obtain(null, DirectBufferPoolOomService.MSG_RUN_OOM).apply {
                replyTo = responseMessenger
            })
            assertTrue("OOM service did not return a result", completed.await(10, TimeUnit.SECONDS))
            failure.get()?.let { throw it }

            val data = requireNotNull(result.get())
            assertNotEquals("OOM must run outside the instrumentation process",
                android.os.Process.myPid(), data.getInt(DirectBufferPoolOomService.KEY_PID))
            assertTrue("ART OutOfMemoryError must map to RESOURCE_LIMIT: $data",
                data.getBoolean(DirectBufferPoolOomService.KEY_RESOURCE_LIMIT))
            assertTrue("allocator cause must remain OutOfMemoryError",
                data.getBoolean(DirectBufferPoolOomService.KEY_OUT_OF_MEMORY))
            assertEquals(0, data.getInt(DirectBufferPoolOomService.KEY_ALLOCATED_BYTES))
            assertEquals(0, data.getInt(DirectBufferPoolOomService.KEY_LEASED_BYTES))
            assertEquals(0, data.getInt(DirectBufferPoolOomService.KEY_LEASED_COUNT))
        } finally {
            if (bound) context.unbindService(connection)
            responseThread.quitSafely()
        }
    }
}
