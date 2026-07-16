package com.phtontools.phtonview.collab

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 内存版传输实现，用于单元测试与本地开发。
 *
 * 不依赖任何网络栈；多个 FakeCollabTransport 共享同一个 [Bus]
 * 即可模拟"同房间"消息互通，跨进程则用真 transport。
 */
class FakeCollabTransport(private val bus: Bus = Bus()) : CollabTransport {

    private val incoming = MutableSharedFlow<String>(extraBufferCapacity = 64)

    init {
        bus.subscribe { json -> incoming.tryEmit(json) }
    }

    override suspend fun openRoom(code: String) { /* no-op */ }

    override suspend fun joinRoom(code: String) { /* no-op */ }

    override suspend fun leaveRoom(code: String) { /* no-op */ }

    override suspend fun send(roomCode: String, jsonMessage: String) {
        bus.publish(jsonMessage)
    }

    override fun received(): Flow<String> = incoming.asSharedFlow()

    /**
     * 全局消息总线（单进程内多 transport 共享）。
     */
    class Bus {
        private val subscribers = mutableListOf<(String) -> Unit>()

        @Synchronized
        fun subscribe(handler: (String) -> Unit) {
            subscribers.add(handler)
        }

        @Synchronized
        fun publish(jsonMessage: String) {
            subscribers.forEach { it(jsonMessage) }
        }
    }
}
