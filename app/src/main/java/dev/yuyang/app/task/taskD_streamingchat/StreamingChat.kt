package dev.yuyang.app.task.taskD_streamingchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * ============================================================================
 * Task D：AI 流式聊天客户端（基于 SSE / Server-Sent Events）
 * ============================================================================
 *
 * 【场景】像 ChatGPT 那样，回复一个字一个字“流式”蹦出来。服务器用 SSE 协议，
 * 一行行推 “data: {...}”，最后推 “data: [DONE]” 表示结束。
 *
 * 【面试官想看的高级信号】
 *   - callbackFlow + awaitClose：把 OkHttp 这种“阻塞读流 + 回调”正确桥接成 Flow
 *   - response.body.use { }：用完自动关闭流，绝不泄漏连接
 *   - CancellationException 必须重新抛出，不能被 catch 吞掉（否则协程取消失效）
 *   - 发新消息前取消上一条还在流的 Job（streamJob?.cancel()）
 *   - 增量更新最后一条消息（拼字符串），而不是每来一个字就重建整个列表
 *
 * 注：用 Android 自带的 org.json 解析，契合本项目栈（没引 kotlinx.serialization）。
 * ============================================================================
 */

/** 消息角色。 */
enum class Role { USER, ASSISTANT, SYSTEM }

/** 一条聊天消息。isStreaming=true 表示这条还在“打字中”。 */
data class ChatMessage(
    val id: String,
    val role: Role,
    val content: String,
    val isStreaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)

/** 流式返回的一个数据块：增量文本 / 结束 / 错误。 */
sealed interface StreamChunk {
    data class Delta(val content: String) : StreamChunk  // 新蹦出来的一小段文本
    data object Done : StreamChunk                        // 流结束
    data class Error(val message: String) : StreamChunk  // 出错
}

class ChatRepository(
    private val client: OkHttpClient = OkHttpClient(),
    private val apiKey: String = "",
    private val endpoint: String = "https://api.example.com/v1/chat/completions",
) {
    /**
     * 把一次流式补全请求封成 Flow<StreamChunk>。
     * callbackFlow：内部可以从“非协程的回调/阻塞读”里往下游 send 数据，
     * 最后必须用 awaitClose 等待下游取消并做清理（这里取消 OkHttp 请求）。
     */
    fun streamCompletion(messages: List<ChatMessage>): Flow<StreamChunk> = callbackFlow {
        // 用 org.json 拼请求体：{ model, stream:true, messages:[{role,content}...] }
        val bodyJson = JSONObject().apply {
            put("model", "gpt-4o")
            put("stream", true)
            put(
                "messages",
                JSONArray().apply {
                    messages.forEach {
                        put(
                            JSONObject().apply {
                                put("role", it.role.name.lowercase())
                                put("content", it.content)
                            },
                        )
                    }
                },
            )
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")  // 声明要 SSE 流
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)

        // OkHttp 的读是阻塞的，放到 IO 线程的子协程里跑。
        launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->   // use{}：结束自动关闭 response
                    if (!response.isSuccessful) {
                        trySend(StreamChunk.Error("HTTP ${response.code}"))
                        return@use
                    }
                    val source = response.body?.source() ?: run {
                        trySend(StreamChunk.Error("empty body"))
                        return@use
                    }
                    // 一行行读 SSE，直到流结束或协程被取消（isActive 变 false）。
                    while (!source.exhausted() && isActive) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data: ")) continue   // SSE 数据行以 "data: " 开头
                        val data = line.removePrefix("data: ")
                        if (data == "[DONE]") {                    // 结束标记
                            trySend(StreamChunk.Done)
                            break
                        }
                        // 解析这一块 JSON，取出增量文本 choices[0].delta.content
                        runCatching {
                            val delta = JSONObject(data)
                                .optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("delta")
                                ?.optString("content")
                            if (!delta.isNullOrEmpty()) trySend(StreamChunk.Delta(delta))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e                                  // 取消异常必须透传，不能吞
            } catch (e: Exception) {
                trySend(StreamChunk.Error(e.message ?: "stream error"))
            } finally {
                close()                                  // 关闭这个 Flow
            }
        }

        // 下游取消时（比如用户切走/发了新消息），取消底层 HTTP 请求，释放连接。
        awaitClose { call.cancel() }
    }
}

class ChatViewModel(
    private val repo: ChatRepository = ChatRepository(),
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // 当前正在流的协程，发新消息时先取消它。
    private var streamJob: Job? = null

    /** 发一条消息：先把“用户消息 + 一条空的 AI 占位消息”加进列表，然后开始流式填充。 */
    fun sendMessage(content: String) {
        val userMsg = ChatMessage(UUID.randomUUID().toString(), Role.USER, content)
        // AI 占位：isStreaming=true，内容空，等会儿一点点往里拼。
        val assistantMsg = ChatMessage(UUID.randomUUID().toString(), Role.ASSISTANT, "", isStreaming = true)
        _messages.update { it + userMsg + assistantMsg }

        streamJob?.cancel()  // 取消上一条还没流完的
        streamJob = viewModelScope.launch {
            // 把历史（去掉刚加的空占位）发给后端，开始收流。
            repo.streamCompletion(_messages.value.dropLast(1)).collect { chunk ->
                when (chunk) {
                    // 每来一段增量，就拼到最后那条 AI 消息上。
                    is StreamChunk.Delta -> updateLastAssistant { it.copy(content = it.content + chunk.content) }
                    // 结束：把“打字中”标记关掉。
                    StreamChunk.Done -> updateLastAssistant { it.copy(isStreaming = false) }
                    // 出错：把错误显示在那条消息里。
                    is StreamChunk.Error -> updateLastAssistant {
                        it.copy(content = "[Error: ${chunk.message}]", isStreaming = false)
                    }
                }
            }
        }
    }

    /** 用户主动停止生成。 */
    fun cancelStream() {
        streamJob?.cancel()
    }

    /**
     * 只更新列表最后一条（AI 消息）。
     * 用 toMutableList + 替换最后一个元素，避免“每个字都重建整个列表”的浪费。
     */
    private inline fun updateLastAssistant(crossinline transform: (ChatMessage) -> ChatMessage) {
        _messages.update { list ->
            if (list.isEmpty()) list
            else list.toMutableList().also { it[it.lastIndex] = transform(it.last()) }
        }
    }
}
