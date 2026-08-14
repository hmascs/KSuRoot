package com.ting.root

object NativeProbe {
    init {
        System.loadLibrary("ksu_native")
    }

    external fun run(): String

    external fun isKernelSuActive(): Boolean
}
