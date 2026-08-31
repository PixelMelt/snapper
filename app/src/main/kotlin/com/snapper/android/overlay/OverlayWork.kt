package com.snapper.android.overlay

import android.os.Handler
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class OverlayWork(private val main: Handler, private val onIdle: () -> Unit) {
    private val worker = idleExecutor("snapper-worker")
    private val captureWorker = idleExecutor("snapper-capture")
    private val pendingDelayedActions = mutableListOf<Runnable>()

    @Volatile
    var stopping = false
        private set

    @Volatile
    var generation = 1
        private set

    var inFlight = 0
        private set

    fun beginStopping() {
        stopping = true
        generation++
        cancelDelayedActions()
    }

    fun cancelDelayedActions() {
        for (action in pendingDelayedActions) {
            main.removeCallbacks(action)
        }
        inFlight -= pendingDelayedActions.size
        pendingDelayedActions.clear()
    }

    fun shutdown() {
        beginStopping()
        captureWorker.shutdownNow()
        worker.shutdownNow()
    }

    fun isCurrent(generation: Int): Boolean = !stopping && this.generation == generation

    fun execute(task: () -> Unit): Boolean = executeOn(worker, task)

    fun executeCapture(task: () -> Unit): Boolean = executeOn(captureWorker, task)

    fun postToMain(generation: Int, current: () -> Unit, stale: (() -> Unit)? = null) {
        main.post {
            if (isCurrent(generation)) current() else stale?.invoke()
        }
    }

    fun postDelayed(delayMs: Long, action: () -> Unit) {
        val wrapped = object : Runnable {
            override fun run() {
                pendingDelayedActions.remove(this)
                inFlight--
                try {
                    if (!stopping) action()
                } finally {
                    onIdle()
                }
            }
        }
        pendingDelayedActions.add(wrapped)
        inFlight++
        main.postDelayed(wrapped, delayMs)
    }

    private fun executeOn(executor: ExecutorService, task: () -> Unit): Boolean {
        if (stopping) return false
        inFlight++
        executor.execute {
            try {
                task()
            } finally {
                main.post {
                    inFlight--
                    onIdle()
                }
            }
        }
        return true
    }
}

private fun idleExecutor(threadName: String): ExecutorService {
    val executor = ThreadPoolExecutor(
        1, 1, 15L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(),
    ) { runnable -> Thread(runnable, threadName).apply { isDaemon = true } }
    executor.allowCoreThreadTimeOut(true)
    return executor
}
