package com.phtontools.phtonview.collab

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 协作消息序列化单测（迭代 #18）。
 *
 * 覆盖：
 * - 每种消息类型 round-trip（encode → decode 字段一致）
 * - 未知 type 抛 IllegalArgumentException
 * - 缺 type 抛 IllegalArgumentException
 * - FrameBroadcast 的 Base64 编解码保真
 * - Room/Member 嵌套结构 round-trip
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CollabMessageSerializerTest {

    @Test
    fun `JoinRequest round trip preserves fields`() {
        val msg = CollabMessage.JoinRequest(
            senderPeerId = "P-A",
            displayName = "Alice",
            requestedRole = MemberRole.Operator
        )
        val out = CollabMessageSerializer.decode(CollabMessageSerializer.encode(msg))
        assertThat(out).isInstanceOf(CollabMessage.JoinRequest::class.java)
        val r = out as CollabMessage.JoinRequest
        assertThat(r.senderPeerId).isEqualTo("P-A")
        assertThat(r.displayName).isEqualTo("Alice")
        assertThat(r.requestedRole).isEqualTo(MemberRole.Operator)
    }

    @Test
    fun `JoinResponse accepted=true round trip`() {
        val originalRoom = CollabRoom(
            code = "PHTN42",
            hostPeerId = "P-H",
            members = listOf(
                CollabMember("P-H", "Host", MemberRole.Host),
                CollabMember("P-A", "Alice", MemberRole.Operator)
            )
        )
        val msg = CollabMessage.JoinResponse(
            senderPeerId = "P-H",
            accepted = true,
            room = originalRoom,
            reason = null
        )
        val r = CollabMessageSerializer.decode(CollabMessageSerializer.encode(msg)) as CollabMessage.JoinResponse
        assertThat(r.accepted).isTrue()
        val decodedRoom = r.room!!
        assertThat(decodedRoom.code).isEqualTo("PHTN42")
        assertThat(decodedRoom.members).hasSize(2)
        assertThat(decodedRoom.members[0].role).isEqualTo(MemberRole.Host)
        assertThat(decodedRoom.members[1].role).isEqualTo(MemberRole.Operator)
    }

    @Test
    fun `JoinResponse rejected with reason`() {
        val msg = CollabMessage.JoinResponse(
            senderPeerId = "P-H",
            accepted = false,
            reason = "Room full"
        )
        val r = CollabMessageSerializer.decode(CollabMessageSerializer.encode(msg))
                as CollabMessage.JoinResponse
        assertThat(r.accepted).isFalse()
        assertThat(r.reason).isEqualTo("Room full")
        assertThat(r.room).isNull()
    }

    @Test
    fun `MemberUpdate round trip preserves room`() {
        val room = CollabRoom(
            code = "ABC123",
            hostPeerId = "P-H",
            members = listOf(CollabMember("P-H", "Host", MemberRole.Host))
        )
        val msg = CollabMessage.MemberUpdate(senderPeerId = "P-H", room = room)
        val out = CollabMessageSerializer.decode(CollabMessageSerializer.encode(msg))
                as CollabMessage.MemberUpdate
        assertThat(out.room.code).isEqualTo("ABC123")
        assertThat(out.room.members).hasSize(1)
    }

    @Test
    fun `CaptureRequest round trip`() {
        val msg = CollabMessage.CaptureRequest(
            senderPeerId = "P-OP",
            delayMs = 5000,
            burstCount = 3
        )
        val r = CollabMessageSerializer.decode(CollabMessageSerializer.encode(msg))
                as CollabMessage.CaptureRequest
        assertThat(r.delayMs).isEqualTo(5000)
        assertThat(r.burstCount).isEqualTo(3)
    }

    @Test
    fun `CaptureAck success preserves exposure`() {
        val msg = CollabMessage.CaptureAck(
            senderPeerId = "P-H",
            requestMsgId = "REQ-1",
            success = true,
            aperture = "f/2.8",
            shutter = "1/250",
            iso = 400
        )
        val r = CollabMessageSerializer.decode(CollabMessageSerializer.encode(msg))
                as CollabMessage.CaptureAck
        assertThat(r.requestMsgId).isEqualTo("REQ-1")
        assertThat(r.success).isTrue()
        assertThat(r.aperture).isEqualTo("f/2.8")
        assertThat(r.shutter).isEqualTo("1/250")
        assertThat(r.iso).isEqualTo(400)
    }

    @Test
    fun `SettingsSnapshot round trip`() {
        val msg = CollabMessage.SettingsSnapshot(
            senderPeerId = "P-H",
            aperture = "f/5.6",
            shutter = "1/125",
            iso = 800,
            ev = -0.7f,
            whiteBalance = "Daylight",
            flashMode = "Off",
            shootingMode = "A"
        )
        val r = CollabMessageSerializer.decode(CollabMessageSerializer.encode(msg))
                as CollabMessage.SettingsSnapshot
        assertThat(r.aperture).isEqualTo("f/5.6")
        assertThat(r.shutter).isEqualTo("1/125")
        assertThat(r.iso).isEqualTo(800)
        assertThat(r.ev).isEqualTo(-0.7f)
        assertThat(r.whiteBalance).isEqualTo("Daylight")
    }

    @Test
    fun `FrameBroadcast preserves binary payload`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01)
        val msg = CollabMessage.FrameBroadcast(
            senderPeerId = "P-H",
            width = 1280,
            height = 720,
            jpegBytes = jpeg,
            isKeyFrame = false
        )
        val r = CollabMessageSerializer.decode(CollabMessageSerializer.encode(msg))
                as CollabMessage.FrameBroadcast
        assertThat(r.width).isEqualTo(1280)
        assertThat(r.height).isEqualTo(720)
        assertThat(r.isKeyFrame).isFalse()
        assertThat(r.jpegBytes).isEqualTo(jpeg)
    }

    @Test
    fun `ChatMessage round trip`() {
        val msg = CollabMessage.ChatMessage(
            senderPeerId = "P-A",
            senderName = "Alice",
            text = "3-2-1!"
        )
        val r = CollabMessageSerializer.decode(CollabMessageSerializer.encode(msg))
                as CollabMessage.ChatMessage
        assertThat(r.senderName).isEqualTo("Alice")
        assertThat(r.text).isEqualTo("3-2-1!")
    }

    @Test
    fun `Heartbeat round trip`() {
        val msg = CollabMessage.Heartbeat(
            senderPeerId = "P-A",
            sequence = 42,
            uptimeMs = 123_456L
        )
        val r = CollabMessageSerializer.decode(CollabMessageSerializer.encode(msg))
                as CollabMessage.Heartbeat
        assertThat(r.sequence).isEqualTo(42)
        assertThat(r.uptimeMs).isEqualTo(123_456L)
    }

    @Test
    fun `LeaveNotice with reason`() {
        val msg = CollabMessage.LeaveNotice(senderPeerId = "P-A", reason = "user_quit")
        val r = CollabMessageSerializer.decode(CollabMessageSerializer.encode(msg))
                as CollabMessage.LeaveNotice
        assertThat(r.reason).isEqualTo("user_quit")
    }

    @Test
    fun `decode throws on missing type`() {
        val bad = "{\"msgId\":\"x\",\"senderPeerId\":\"y\"}"
        runCatching { CollabMessageSerializer.decode(bad) }
            .onSuccess { error("expected exception") }
            .onFailure { e ->
                assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
            }
    }

    @Test
    fun `decode throws on unknown type`() {
        val bad = "{\"type\":\"unknown_type\",\"msgId\":\"x\"}"
        runCatching { CollabMessageSerializer.decode(bad) }
            .onSuccess { error("expected exception") }
            .onFailure { e ->
                assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
            }
    }

    @Test
    fun `Room code validation`() {
        // 4-8 位字母数字
        runCatching { CollabRoom(code = "AB", hostPeerId = "x") }
            .onSuccess { error("expected exception") }
            .onFailure { /* ok */ }
        runCatching { CollabRoom(code = "PHTN-42", hostPeerId = "x") }
            .onSuccess { error("expected exception") }
            .onFailure { /* ok */ }
        val ok = CollabRoom(code = "PHTN42", hostPeerId = "x")
        assertThat(ok.code).isEqualTo("PHTN42")
    }
}
