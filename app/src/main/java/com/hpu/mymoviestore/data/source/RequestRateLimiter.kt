package com.hpu.mymoviestore.data.source

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.util.concurrent.atomic.AtomicLong

/**
 * 单源限流队列 / 调度器
 *
 * 每个播放源持有一个 [RequestRateLimiter] 实例，用于统一管理该源下所有类型的网络请求
 * （搜索、详情、播放页等）。
 *
 * 行为约定：
 * - 两次实际网络请求之间至少间隔 [minIntervalMs]（默认 3 秒）。
 * - 队列最大持有 [maxQueueSize] 个尚未执行完成的任务（包括正在执行和等待中的）。
 *   当超过容量时，按"未开始 + 优先级最低 + 最早入队"的顺序丢弃旧任务。
 * - 优先级数值越大越高：SEARCH=3 > DETAIL=2 > PLAY=1。
 * - 新任务入队时，会取消"队列中所有 **优先级低于或等于自身** 的旧任务"（包括已开始执行的）。
 *   后来者覆盖：同优先级的新任务也会取消同优先级的旧任务。
 *
 * 取消行为：
 * - 任务还在等待中（未开始）：直接从队列移除，调用方收到 [CancellationException]。
 * - 任务已经开始执行（HTTP 请求已经发出）：调用方注册的 [okhttp3.Call] 会被 [okhttp3.Call.cancel]
 *   主动断开，但本次执行仍计入"已经占用了一个 3 秒间隔"，下一个请求依然需要等到距离它开始
 *   时刻 3 秒之后才能发起。
 */
class RequestRateLimiter(
    private val sourceTag: String,
    private val minIntervalMs: Long = 3_000L,
    private val maxQueueSize: Int = 3
) {

    /** 请求优先级：数值越大越高 */
    enum class Priority(val value: Int) {
        PLAY(1),
        DETAIL(2),
        SEARCH(3);
    }

    /** 内部任务对象，对外部代码不可见，但对同模块内的 Handle 可见 */
    internal class Task(
        val id: Long,
        val priority: Priority,
        val tag: String,
        @Volatile var ongoingCall: Call? = null,
        @Volatile var started: Boolean = false,
        val signal: CompletableDeferred<Unit> = CompletableDeferred()
    )

    /**
     * 任务执行期间获得的句柄，用于注册 OkHttp Call，以便外部取消时能够 cancel。
     * 通过持有 [Task] 的直接引用实现 O(1) 注册，且 [Task.ongoingCall] 为 @Volatile，
     * 保证写入对取消线程立即可见。
     */
    inner class Handle internal constructor(internal val task: Task) {
        fun registerCall(call: Call) {
            task.ongoingCall = call
        }
    }

    private val mutex = Mutex()
    private val queue = ArrayDeque<Task>()

    /** 上一次实际开始执行网络请求的时间戳（毫秒），用于计算 3 秒间隔 */
    private val lastDispatchAt = AtomicLong(0L)
    private val taskIdGen = AtomicLong(0L)

    /**
     * 在限流器调度下执行 [block]。
     *
     * @param priority 任务优先级
     * @param tag 调试日志用标签，例如请求 URL
     * @param block 真正的执行体；如果块内进行 OkHttp 调用，应通过 [registerCall] 注册以支持取消
     */
    suspend fun <T> submit(
        priority: Priority,
        tag: String,
        block: suspend (handle: Handle) -> T
    ): T = withContext(Dispatchers.IO) {
        val task = Task(
            id = taskIdGen.incrementAndGet(),
            priority = priority,
            tag = tag
        )

        // 1) 入队 + 维护容量与优先级抢占
        enqueue(task)

        try {
            // 2) 等待轮到自己 + 满足 3 秒间隔
            waitUntilDispatch(task)

            // 3) 真正执行（若已被取消，会在 waitUntilDispatch 抛出 CancellationException）
            val handle = Handle(task)
            block(handle)
        } catch (ce: CancellationException) {
            Log.w(TAG, "[$sourceTag] 任务被取消: id=${task.id}, priority=${task.priority}, tag=${task.tag}, started=${task.started}, reason=${ce.message}")
            throw ce
        } finally {
            // 4) 完成后从队列移除
            mutex.withLock {
                queue.remove(task)
            }
        }
    }

    private suspend fun enqueue(task: Task) {
        val cancelledOldTasks = mutableListOf<Task>()
        mutex.withLock {
            // 抢占：取消"队列中所有优先级 <= 当前任务优先级"的旧任务（包括已开始执行的）。
            // - 未开始：从队列直接移除；
            // - 已开始：保留在队列中（其执行体的 finally 会自行清理），但通过 call.cancel() 终止网络层；
            //   仍计入"已经占用了一个 3 秒 dispatch 槽"，下一任务依旧需等待 3 秒。
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val old = iterator.next()
                if (old.priority.value <= task.priority.value) {
                    if (!old.started) {
                        iterator.remove()
                    }
                    cancelledOldTasks.add(old)
                }
            }

            // 容量控制：超出 maxQueueSize 时，从队列中按"未开始且优先级最低"丢弃
            // 因为正在执行的任务（started == true）必须保留（其 3 秒槽已被占用）
            while (queue.size >= maxQueueSize) {
                val victim = queue
                    .filter { !it.started }
                    .minByOrNull { it.priority.value * 1_000_000_000L + (Long.MAX_VALUE - it.id) }
                if (victim == null) {
                    // 队列里全是 started=true 的执行中任务，无法再丢弃，直接打破容量限制让新任务等。
                    Log.w(TAG, "[$sourceTag] 队列满且全为执行中任务，新任务允许排队等待: tag=${task.tag}")
                    break
                }
                queue.remove(victim)
                cancelledOldTasks.add(victim)
            }

            queue.addLast(task)
            Log.d(
                TAG,
                "[$sourceTag] 入队: id=${task.id}, priority=${task.priority}, tag=${task.tag}, " +
                    "queueSize=${queue.size}, cancelled=${cancelledOldTasks.size}"
            )
        }

        // 锁外执行实际取消，避免死锁
        cancelledOldTasks.forEach { old ->
            cancelTask(old, reason = "被新任务抢占（new priority=${task.priority}）")
        }
    }

    private fun cancelTask(target: Task, reason: String) {
        Log.w(
            TAG,
            "[$sourceTag] 取消任务: id=${target.id}, priority=${target.priority}, tag=${target.tag}, started=${target.started}, reason=$reason"
        )
        target.ongoingCall?.let { call ->
            try {
                if (!call.isCanceled()) call.cancel()
            } catch (t: Throwable) {
                Log.w(TAG, "[$sourceTag] 取消 OkHttp Call 失败: ${t.message}")
            }
        }
        target.signal.completeExceptionally(CancellationException(reason))
    }

    private suspend fun waitUntilDispatch(task: Task) {
        while (true) {
            // a) 检查是否被取消
            if (task.signal.isCompleted) {
                // 已经被取消（completeExceptionally）
                task.signal.await() // 抛 CancellationException
            }

            // b) 看是否轮到队首
            val now = System.currentTimeMillis()
            val (isFront, waitMs) = mutex.withLock {
                val head = queue.firstOrNull()
                if (head?.id == task.id) {
                    val nextAllowedAt = lastDispatchAt.get() + minIntervalMs
                    val wait = (nextAllowedAt - now).coerceAtLeast(0L)
                    if (wait == 0L) {
                        task.started = true
                        lastDispatchAt.set(System.currentTimeMillis())
                        Log.d(
                            TAG,
                            "[$sourceTag] 开始执行: id=${task.id}, priority=${task.priority}, tag=${task.tag}"
                        )
                        Pair(true, 0L)
                    } else {
                        Pair(false, wait)
                    }
                } else {
                    // 不在队首：等队首结束后再唤醒
                    Pair(false, 50L)
                }
            }

            if (isFront) return
            // 用 delay 配合取消信号：尽量短的轮询；取消时立即生效。
            try {
                kotlinx.coroutines.withTimeoutOrNull(waitMs.coerceAtMost(200L)) {
                    // 如果中途被取消，await 会抛出 CancellationException
                    task.signal.await()
                }
            } catch (ce: CancellationException) {
                throw ce
            }
        }
    }

    companion object {
        private const val TAG = "RequestRateLimiter"
    }
}
