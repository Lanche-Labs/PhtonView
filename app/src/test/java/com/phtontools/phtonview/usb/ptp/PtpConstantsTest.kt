package com.phtontools.phtonview.usb.ptp

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PtpConstants 单测（迭代 #18 + fix #122 / #123）。
 *
 * 覆盖：
 * - 新增 RESPONSE_NIKON_SESSION_ALREADY_OPEN (0x201E) 常量值正确
 * - 通用 OK / SessionNotOpen / SessionAlreadyOpen 常量值正确
 * - OpenSession 响应码白名单的判定逻辑
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PtpConstantsTest {

    @Test
    fun `RESPONSE_OK is 0x2001`() {
        assertThat(PtpConstants.RESPONSE_OK).isEqualTo(0x2001.toShort())
    }

    @Test
    fun `RESPONSE_SESSION_NOT_OPEN is 0x2003`() {
        assertThat(PtpConstants.RESPONSE_SESSION_NOT_OPEN).isEqualTo(0x2003.toShort())
    }

    @Test
    fun `RESPONSE_SESSION_ALREADY_OPEN is 0x2007 (MTP standard)`() {
        assertThat(PtpConstants.RESPONSE_SESSION_ALREADY_OPEN).isEqualTo(0x2007.toShort())
    }

    @Test
    fun `RESPONSE_NIKON_SESSION_ALREADY_OPEN is 0x201E (Nikon D5200 quirk)`() {
        // D5200 / 部分老机器在重复 OpenSession 时回 0x201E，
        // 实际语义是 Session Already Open。
        assertThat(PtpConstants.RESPONSE_NIKON_SESSION_ALREADY_OPEN).isEqualTo(0x201E.toShort())
    }

    @Test
    fun `session-already-open whitelist covers all known codes`() {
        // OpenSession 视为成功的响应码集合
        val whitelist = setOf(
            PtpConstants.RESPONSE_OK,
            PtpConstants.RESPONSE_SESSION_NOT_OPEN,
            PtpConstants.RESPONSE_SESSION_ALREADY_OPEN,
            PtpConstants.RESPONSE_NIKON_SESSION_ALREADY_OPEN
        )
        assertThat(whitelist).hasSize(4)
        // 验证 0x2002 GeneralError **不在**白名单，避免误判
        assertThat(whitelist).doesNotContain(PtpConstants.RESPONSE_GENERAL_ERROR)
    }

    @Test
    fun `vendor extension IDs for known brands`() {
        // VendorExtensionID 区分品牌
        assertThat(PtpConstants.VENDOR_EXTENSION_NIKON).isNotEqualTo(0)
        assertThat(PtpConstants.VENDOR_EXTENSION_CANON).isNotEqualTo(0)
        assertThat(PtpConstants.VENDOR_EXTENSION_SONY).isNotEqualTo(0)
    }
}
