package com.phtontools.phtonview.collab

import kotlinx.coroutines.flow.Flow

/**
 * 房间传输抽象（迭代 #18）。
 *
 * 物理层独立：本接口**不**关心底层是 UDP/TCP/WebSocket/远程转发，
 * 单元测试可在 JVM 上用 [FakeCollabTransport] 替代真实网络栈。
 *
 * 生产实现（独立 PR 提交，本任务不实现）：
 * - [LanUdpTransport]  —— LAN 内部基于 UDP multicast（239.255.42.42:47800）
 *   + 主动 sendto 单播
 * - [RemoteWsTransport]  —— 通过 PhtonView 中继服务器走 WebSocket
 */
interface CollabTransport {

    /**
     * 以 Host 身份打开房间（绑定端口、加入 multicast 组等）。
     */
    suspend fun openRoom(code: String)

    /**
     * 以成员身份加入房间。
     */
    suspend fun joinRoom(code: String)

    /**
     * 离开房间（关闭端口、退 multicast 组）。
     */
    suspend fun leaveRoom(code: String)

    /**
     * 发送一条 JSON 编码消息给指定房间的所有成员。
     */
    suspend fun send(roomCode: String, jsonMessage: String)

    /**
     * 接收消息流（每个字符串 = 一条 JSON 编码消息）。
     */
    fun received(): Flow<String>
}
