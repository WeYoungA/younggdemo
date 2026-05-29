package dev.yuyang.app.task.taskB_offlinefeed

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ============================================================================
 * Task B：离线优先的响应式 Feed（现代数据层标准模式）
 * ============================================================================
 *
 * 【核心模式：单一真相源 Single Source of Truth (SST)】
 *   - 数据库（Room）是唯一真相源；界面永远只看数据库
 *   - 网络只负责“异步把新数据写进数据库”，写完数据库的 Flow 自动通知界面刷新
 *   - 好处：天然离线可用、数据一致、界面代码极简（只观察一条 Flow）
 *
 * 【面试官想看的高级信号】
 *   - SST 模式本身
 *   - distinctUntilChanged()：内容没变就不重复刷界面
 *   - flowOn(Dispatchers.Default)：把上游的 map 转换放后台线程
 *   - stateIn(WhileSubscribed(5_000))：订阅消失后保留 5 秒，避免旋转屏幕时
 *     “退订→立刻重订”的抖动和重新加载
 *   - 刷新时对账：服务端删掉的数据，本地也要删
 * ============================================================================
 */

// ---- 领域模型 + 网络 DTO ----
/** 给界面用的领域模型。 */
data class Item(val id: String, val title: String, val updatedAt: Long)

/** 网络返回的 DTO（和数据库实体、领域模型分开，是分层好习惯）。 */
data class NetworkItem(val id: String, val title: String, val updatedAt: Long)

/** 网络接口。 */
interface FeedApi {
    suspend fun getFeed(): List<NetworkItem>
}

/** 模拟网络，让仓库开箱即跑。 */
class FakeFeedApi : FeedApi {
    override suspend fun getFeed(): List<NetworkItem> {
        kotlinx.coroutines.delay(500)
        return (1..20).map { NetworkItem("id_$it", "Server item #$it", it.toLong()) }
    }
}

// ---- Room 数据库部分 ----
/** 数据库表实体。cachedAt 记录这条数据是什么时候缓存的，用于判断“过期”。 */
@Entity(tableName = "feed_items")
data class ItemEntity(
    @PrimaryKey val id: String,
    val title: String,
    val updatedAt: Long,
    val cachedAt: Long,
)

/** 数据访问对象（DAO）。 */
@Dao
interface ItemDao {
    // 返回 Flow：表数据一变，所有订阅者自动收到新列表。这是 SST 能成立的关键。
    @Query("SELECT * FROM feed_items ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ItemEntity>>

    // REPLACE：主键冲突就覆盖，实现“有则更新、无则插入”（upsert）。
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ItemEntity>)

    @Query("DELETE FROM feed_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT MAX(cachedAt) FROM feed_items")
    suspend fun lastCacheTime(): Long?
}

/** Room 数据库定义。KSP 会在编译期生成它的实现类。 */
@Database(entities = [ItemEntity::class], version = 1, exportSchema = false)
abstract class OfflineFeedDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao

    companion object {
        @Volatile
        private var instance: OfflineFeedDatabase? = null

        // 双检锁单例，数据库实例全局唯一（创建数据库开销大，不能反复建）。
        fun get(context: Context): OfflineFeedDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                OfflineFeedDatabase::class.java,
                "offline_feed.db",
            ).build().also { instance = it }
        }
    }
}

// ---- 仓库：实现 SST ----
class OfflineFeedRepository(
    private val api: FeedApi,
    private val dao: ItemDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * 对外暴露的“观察 Feed”。链路：
     *   数据库 Flow → map 成领域模型 → 去重 → （首次订阅时）若数据过期就触发刷新 → 放后台线程
     * 界面只要 collect 这一条 Flow 就行，刷新/离线全自动。
     */
    fun observeFeed(): Flow<List<Item>> =
        dao.observeAll()
            .map { entities -> entities.map { Item(it.id, it.title, it.updatedAt) } }
            .distinctUntilChanged()             // 内容没变不重复发
            .onStart { triggerRefreshIfStale() } // 有人开始观察时，按需触发一次网络刷新
            .flowOn(Dispatchers.Default)         // 上面的 map 等转换在后台线程跑

    /** 网络刷新：拉新数据写库 + 对账删除。返回 Result 让调用方知道成败。 */
    suspend fun refresh(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val fresh = api.getFeed()
            val cachedAt = now()
            dao.upsertAll(fresh.map { ItemEntity(it.id, it.title, it.updatedAt, cachedAt) })

            // 【对账】服务端不再返回的数据，本地也删掉，保持和服务端一致。
            val freshIds = fresh.map { it.id }.toSet()
            val localIds = dao.observeAll().first().map { it.id }.toSet()
            val toDelete = (localIds - freshIds).toList()
            if (toDelete.isNotEmpty()) dao.deleteByIds(toDelete)
        }
    }

    /** 只有数据“过期”（超过阈值）才刷新，避免每次进页面都打网络。 */
    private suspend fun triggerRefreshIfStale() {
        val last = dao.lastCacheTime() ?: 0L
        if (now() - last > STALE_THRESHOLD_MS) refresh()
    }

    companion object {
        const val STALE_THRESHOLD_MS = 5 * 60 * 1000L // 5 分钟
    }
}

// ---- ViewModel ----
data class FeedUiState(
    val items: List<Item> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class OfflineFeedViewModel(
    private val repo: OfflineFeedRepository,
) : ViewModel() {

    /**
     * 把仓库的 Flow 转成界面状态，再用 stateIn 变成一个“热”的 StateFlow：
     *   - WhileSubscribed(5_000)：最后一个订阅者消失后再保留 5 秒上游；
     *     旋转屏幕这种瞬间退订/重订就不会触发重新加载，体验顺、省资源
     *   - 初始值是 loading=true
     */
    val state = repo.observeFeed()
        .map { FeedUiState(items = it, isLoading = false) }
        .catch { e -> emit(FeedUiState(error = e.message)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedUiState(isLoading = true))

    /** 手动下拉刷新：触发网络，结果自然通过数据库 Flow 回流到 state。 */
    fun refresh() {
        viewModelScope.launch { repo.refresh() }
    }
}
