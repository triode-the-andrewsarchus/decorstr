package com.decorstr.engine

enum class ColorSpace(val id: Int, val displayName: String) {
    RGB_ZCA(0, "Decorstr RGB"),
    LAB(1, "Decorstr LAB"),
    YUV(2, "Decorstr YUV"),
    DIRECT_PCA(3, "Direct PCA"),
    RAW_NORMAL(4, "Normal RAW");

    companion object {
        fun fromId(id: Int): ColorSpace = entries.firstOrNull { it.id == id } ?: LAB
    }
}
