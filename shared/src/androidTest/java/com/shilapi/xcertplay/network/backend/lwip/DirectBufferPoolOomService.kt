package com.shilapi.xcertplay.network.backend.lwip

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.nio.ByteBuffer
/** Runs the heap-exhaustion attempt outside the instrumentation runner process. */
class DirectBufferPoolOomService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("lwip-direct-buffer-oom").apply { start() }
        messenger = Messenger(Handler(worker.looper) { message ->
            if (message.what != MSG_RUN_OOM) return@Handler false
            val result = exerciseHeapOom()
            message.replyTo?.send(Message.obtain(null, MSG_RESULT).apply { data = result })
            stopSelf()
            true
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    private fun exerciseHeapOom(): Bundle {
        val pool = DirectBufferPool(16) {
            val impossibleArray = ByteArray(Int.MAX_VALUE)
            check(impossibleArray.size == Int.MAX_VALUE)
            ByteBuffer.allocateDirect(16)
        }
        return try {
            val failure = runCatching { pool.acquire(9) }.exceptionOrNull()
            val snapshot = pool.snapshot()
            Bundle().apply {
                putInt(KEY_PID, Process.myPid())
                putBoolean(KEY_RESOURCE_LIMIT, failure is com.shilapi.xcertplay.network.backend.BackendFailure &&
                    failure.kind == com.shilapi.xcertplay.network.backend.BackendFailure.Kind.RESOURCE_LIMIT)
                putBoolean(KEY_OUT_OF_MEMORY, failure?.cause is OutOfMemoryError)
                putString(KEY_FAILURE_CLASS, failure?.javaClass?.name)
                putString(KEY_FAILURE_KIND, (failure as? com.shilapi.xcertplay.network.backend.BackendFailure)?.kind?.name)
                putString(KEY_CAUSE_CLASS, failure?.cause?.javaClass?.name)
                putInt(KEY_ALLOCATED_BYTES, snapshot.allocatedBytes)
                putInt(KEY_LEASED_BYTES, snapshot.leasedBytes)
                putInt(KEY_LEASED_COUNT, snapshot.leasedCount)
            }
        } finally {
            pool.close()
        }
    }

    companion object {
        const val MSG_RUN_OOM = 1
        const val MSG_RESULT = 2
        const val KEY_PID = "pid"
        const val KEY_RESOURCE_LIMIT = "resource-limit"
        const val KEY_OUT_OF_MEMORY = "out-of-memory"
        const val KEY_FAILURE_CLASS = "failure-class"
        const val KEY_FAILURE_KIND = "failure-kind"
        const val KEY_CAUSE_CLASS = "cause-class"
        const val KEY_ALLOCATED_BYTES = "allocated-bytes"
        const val KEY_LEASED_BYTES = "leased-bytes"
        const val KEY_LEASED_COUNT = "leased-count"
    }
}
