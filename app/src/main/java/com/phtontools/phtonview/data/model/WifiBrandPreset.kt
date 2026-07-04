package com.phtontools.phtonview.data.model

import com.phtontools.phtonview.R

/**
 * 常用相机品牌 Wi-Fi（PTP-IP）配对预设。
 *
 * 用户通常只需输入相机 IP，应用会根据品牌遍历候选端口，自动发现可用连接。
 * 连接成功后会记住该端口，下次优先尝试。
 */
enum class WifiBrandPreset(
    val brand: CameraBrand,
    val labelRes: Int,
    val defaultIp: String,
    val candidatePorts: List<Int>,
    val hintRes: Int
) {
    Nikon(
        brand = CameraBrand.Nikon,
        labelRes = R.string.brand_nikon,
        defaultIp = "192.168.1.1",
        candidatePorts = listOf(15740, 15741, 15739, 15742, 15743),
        hintRes = R.string.wifi_hint_nikon
    ),
    NikonWu(
        brand = CameraBrand.Nikon,
        labelRes = R.string.brand_nikon_wu,
        defaultIp = "192.168.1.100",
        candidatePorts = listOf(15740, 15741, 15739),
        hintRes = R.string.wifi_hint_nikon_wu
    ),
    Canon(
        brand = CameraBrand.Canon,
        labelRes = R.string.brand_canon,
        defaultIp = "192.168.1.1",
        candidatePorts = listOf(15740, 15741, 15739, 55740),
        hintRes = R.string.wifi_hint_canon
    ),
    Sony(
        brand = CameraBrand.Sony,
        labelRes = R.string.brand_sony,
        defaultIp = "192.168.1.1",
        candidatePorts = listOf(15740, 15741, 15739, 55740),
        hintRes = R.string.wifi_hint_sony
    ),
    Fuji(
        brand = CameraBrand.Fuji,
        labelRes = R.string.brand_fuji,
        defaultIp = "192.168.1.1",
        candidatePorts = listOf(55740, 15740, 15741, 15739),
        hintRes = R.string.wifi_hint_fuji
    ),
    Panasonic(
        brand = CameraBrand.Panasonic,
        labelRes = R.string.brand_panasonic,
        defaultIp = "192.168.1.1",
        candidatePorts = listOf(15740, 15741, 15739, 55740),
        hintRes = R.string.wifi_hint_panasonic
    ),
    Olympus(
        brand = CameraBrand.Olympus,
        labelRes = R.string.brand_olympus,
        defaultIp = "192.168.1.1",
        candidatePorts = listOf(15740, 15741, 15739),
        hintRes = R.string.wifi_hint_olympus
    ),
    Custom(
        brand = CameraBrand.Generic,
        labelRes = R.string.wifi_custom,
        defaultIp = "",
        candidatePorts = listOf(15740, 15741, 15739, 55740),
        hintRes = R.string.wifi_hint_custom
    );

    companion object {
        fun forBrand(brand: CameraBrand): WifiBrandPreset =
            entries.firstOrNull { it.brand == brand } ?: Custom

        /**
         * 根据保存的 IP 或 "ip:port" 字符串匹配最可能的品牌预设。
         * 优先按 IP 精确匹配，否则按品牌兜底，最后返回 Custom。
         */
        fun forAddress(address: String, brand: CameraBrand = CameraBrand.Generic): WifiBrandPreset {
            val ip = address.substringBeforeLast(":", address)
            return entries
                .filter { it.defaultIp.isNotBlank() }
                .firstOrNull { it.defaultIp == ip }
                ?: forBrand(brand)
        }
    }
}
