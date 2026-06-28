package com.phtontools.phtonview.ndk

object NativeBridge {
    init {
        System.loadLibrary("phtonview")
    }

    external fun stringFromJNI(): String
}
