package com.phtontools.phtonview.data.model

import com.phtontools.phtonview.R

/**
 * 常用相机品牌 Wi-Fi（PTP-IP）配对预设。
 *
 * 大多数相机开启 Wi-Fi 后会自建热点或加入现有网络，手机连入同一网络后，
 * 使用品牌默认的 IP 与端口（PTP-IP 默认 15740）即可尝试连接。
 */
enum class WifiBrandPreset(
    val brand: CameraBrand,
    val labelRes: Int,
    val defaultAddress: String,
    val hintRes: Int
) {
    Nikon(
        brand = CameraBrand.Nikon,
        labelRes = R.string.brand_nikon,
        defaultAddress = "192.168.1.1:15740",
        hintRes = R.string.wifi_hint_nikon
    ),
    NikonWu(
        brand = CameraBrand.Nikon,
        labelRes = R.string.brand_nikon_wu,
        defaultAddress = "192.168.1.100:15740",
        hintRes = R.string.wifi_hint_nikon_wu
    ),
    Canon(
        brand = CameraBrand.Canon,
        labelRes = R.string.brand_canon,
        defaultAddress = "192.168.1.1:15740",
        hintRes = R.string.wifi_hint_canon
    ),
    Sony(
        brand = CameraBrand.Sony,
        labelRes = R.string.brand_sony,
        defaultAddress = "192.168.1.1:15740",
        hintRes = R.string.wifi_hint_sony
    ),
    Fuji(
        brand = CameraBrand.Fuji,
        labelRes = R.string.brand_fuji,
        defaultAddress = "192.168.1.1:15740",
        hintRes = R.string.wifi_hint_fuji
    ),
    Panasonic(
        brand = CameraBrand.Panasonic,
        labelRes = R.string.brand_panasonic,
        defaultAddress = "192.168.1.1:15740",
        hintRes = R.string.wifi_hint_panasonic
    ),
    Olympus(
        brand = CameraBrand.Olympus,
        labelRes = R.string.brand_olympus,
        defaultAddress = "192.168.1.1:15740",
        hintRes = R.string.wifi_hint_olympus
    ),
    Custom(
        brand = CameraBrand.Generic,
        labelRes = R.string.wifi_custom,
        defaultAddress = "",
        hintRes = R.string.wifi_hint_custom
    );

    companion object {
        fun forBrand(brand: CameraBrand): WifiBrandPreset =
            entries.firstOrNull { it.brand == brand } ?: Custom
    }
}
