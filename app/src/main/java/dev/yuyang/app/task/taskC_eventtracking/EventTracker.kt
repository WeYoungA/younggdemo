package dev.yuyang.app.task.taskC_eventtracking

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.random.Random

/**
 * ============================================================================
 * Task C：事件埋点 SDK（Adjust 风格，AppLovin 收购了 Adjust，完美对口）
 * ============================================================================
 *
 * 【埋点 SDK 的核心诉求：一条事件都不能丢，但又不能每条都立刻发网络（费流量费电）】
 * 解决办法：先落盘 → 批量上传 → 失败重试。
 *
 * 【面试官想看的高级信号】
 *   - 先持久化、再入队：App 被杀也不丢事件（重启后从库里捞出来补传）
 *   - 自定义 Flow 操作符 chunkedTimeout：攒够一批 或 到时间窗口 就触发上传
 *     （标准库没有这个操作符，能手写体现对 Flow/Channel 的理解）
 *   - 指数退避 + 抖动（jitter）的重试：失败后越等越久，加随机抖动防“惊群”
 *   - SDK 级 SupervisorJob 作用域；shutdown() 干净取消
 * ============================================================================
 */
private val gson = Gson()

/** 一条事件。properties 用 Map<String,String>，存库时序列化成 JSON。 */
data class Event(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val properties: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String,
)

/** 上传接口。 */
interface EventApi {
    suspend fun upload(events: List<Event>)
}

/** 模拟上传。 */
class FakeEventApi : EventApi {
    override suspend fun upload(events: List<Event>) {
        delay(200) // 模拟网络
    }
}

/** 配置：批大小、时间窗口、单次最多取多少条。 */
data class TrackerConfig(
    val batchSize: Int = 20,                // 攒够 20 条就发
    val batchFlushIntervalMs: Long = 5_000L, // 或者每 5 秒发一次
    val maxBatch: Int = 1_000,
)

/** 会话管理：这里简单用一个随机 id 代表本次启动的会话。 */
object SessionManager {
    private val id = UUID.randomUUID().toString()
    fun currentId(): String = id
}

// ---- Room：事件持久化 ----
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val id: String,
    val name: String,
    val propertiesJson: String, // properties 序列化后的 JSON 字符串
    val timestamp: Long,
    val sessionId: String,
)

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: EventEntity)

    // 按时间升序取“待上传”的事件（FIFO）。
    @Query("SELECT * FROM events ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getAllPending(limit: Int): List<EventEntity>

    // 上传成功后按 id 批量删除。
    @Query("DELETE FROM events WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Database(entities = [EventEntity::class], version = 1, exportSchema = false)
abstract class EventDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile
        private var instance: EventDatabase? = null

        fun get(context: Context): EventDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                EventDatabase::class.java,
                "events.db",
            ).build().also { instance = it }
        }
    }
}

// 领域模型 <-> 数据库实体 的互转（properties 用 Gson 序列化/反序列化）。
private fun Event.toEntity() = EventEntity(id, name, gson.toJson(properties), timestamp, sessionId)

private fun EventEntity.toDomain(): Event {
    val type = object : TypeToken<Map<String, String>>() {}.type
    val props: Map<String, String> = gson.fromJson(propertiesJson, type) ?: emptyMap()
    return Event(id, name, props, timestamp, sessionId)
}

/**
 * 埋点器主体（单例）。
 * 构造时启动两条后台协程：①捞出上次没传完的；②主上传管道（批处理）。
 */
class EventTracker private constructor(
    context: Context,
    private val config: TrackerConfig,
    private val api: EventApi,
) {
    // SDK 级作用域：SupervisorJob 保证一个任务失败不拖垮整个 scope。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 内存队列：track 进来的事件先 emit 到这里，由下游批处理消费。
    private val queue = MutableSharedFlow<Event>(extraBufferCapacity = 1_000)
    private val dao = EventDatabase.get(context).eventDao()

    init {
        // ① 启动时把上次残留在库里的事件补传（防丢的关键）。
        scope.launch { uploadPendingFromDb() }
        // ② 主管道：队列 → 按“批大小或时间窗口”切批 → 逐批上传。
        scope.launch {
            queue
                .chunkedTimeout(config.batchSize, config.batchFlushIntervalMs)
                .collect { batch -> uploadBatch(batch) }
        }
    }

    /** 上报一条事件。注意顺序：先写库（防丢），再入队（触发批量上传）。 */
    fun track(name: String, properties: Map<String, String> = emptyMap()) {
        val event = Event(name = name, properties = properties, sessionId = SessionManager.currentId())
        scope.launch {
            dao.insert(event.toEntity()) // 先持久化
            queue.emit(event)            // 再入队
        }
    }

    /** 手动立即上传所有待传事件（比如 App 退后台时调用）。 */
    fun flush() {
        scope.launch {
            val pending = dao.getAllPending(config.maxBatch).map { it.toDomain() }
            if (pending.isNotEmpty()) uploadBatch(pending)
        }
    }

    /** 关闭：取消所有后台协程。 */
    fun shutdown() = scope.cancel()

    private suspend fun uploadPendingFromDb() {
        val pending = dao.getAllPending(config.maxBatch).map { it.toDomain() }
        if (pending.isNotEmpty()) uploadBatch(pending)
    }

    /** 上传一批：带重试；成功后才把这批从库里删（保证“至少一次”送达）。 */
    private suspend fun uploadBatch(batch: List<Event>) {
        if (batch.isEmpty()) return
        retry(maxAttempts = 3, initialDelayMs = 1_000L, factor = 2.0) {
            api.upload(batch)
            dao.deleteByIds(batch.map { it.id })
        }
    }

    companion object {
        @Volatile
        private var instance: EventTracker? = null

        fun init(
            context: Context,
            config: TrackerConfig = TrackerConfig(),
            api: EventApi = FakeEventApi(),
        ): EventTracker = instance ?: synchronized(this) {
            instance ?: EventTracker(context.applicationContext, config, api).also { instance = it }
        }
    }
}

/**
 * 自定义 Flow 操作符：按“数量 size 或 时间 timeoutMs，谁先到”切批输出。
 *
 * 实现要点：
 *   - channelFlow：允许在内部并发地往下游 send（这里有“定时器协程”和“收集协程”两路）
 *   - buffer 暂存元素，Mutex 保护它（两路协程都会动 buffer）
 *   - send 是挂起的：下游慢时它会等（背压），保证一批都不丢（不能用 trySend，会丢）
 */
fun <T> Flow<T>.chunkedTimeout(size: Int, timeoutMs: Long): Flow<List<T>> = channelFlow {
    val buffer = mutableListOf<T>()
    val mutex = Mutex()

    // 定时器协程：每隔 timeoutMs 把当前 buffer 发出去（哪怕没攒够 size）。
    val timer = launch {
        while (isActive) {
            delay(timeoutMs)
            mutex.withLock {
                if (buffer.isNotEmpty()) {
                    send(buffer.toList()) // 挂起式 send，绝不丢批
                    buffer.clear()
                }
            }
        }
    }

    // 收集上游：每来一个就进 buffer，攒够 size 立刻发一批。
    this@chunkedTimeout.collect { value ->
        mutex.withLock {
            buffer.add(value)
            if (buffer.size >= size) {
                send(buffer.toList())
                buffer.clear()
            }
        }
    }

    // 上游结束：停掉定时器，把残留的最后一批发出去。
    timer.cancelAndJoin()
    if (buffer.isNotEmpty()) send(buffer.toList())
}

/**
 * 指数退避 + 抖动的重试。
 * 第 1 次失败等 ~1s，第 2 次等 ~2s……每次乘 factor；再加一点随机 jitter，
 * 防止大量客户端同时失败、同时重试，把服务器再次打挂（惊群效应 thundering herd）。
 * 最后一次直接执行，让异常抛出去。
 */
suspend fun <T> retry(
    maxAttempts: Int,
    initialDelayMs: Long,
    factor: Double,
    block: suspend () -> T,
): T {
    var currentDelay = initialDelayMs
    repeat(maxAttempts - 1) {
        try {
            return block()
        } catch (e: Exception) {
            val jitter = Random.nextLong(0, (currentDelay / 4).coerceAtLeast(1))
            delay(currentDelay + jitter)
            currentDelay = (currentDelay * factor).toLong()
        }
    }
    return block()
}
