package com.decorstr.engine

enum class ColorSpace(val id: Int, val displayName: String, val chipText: String) {
    LAB(1, "Decorstr LAB", "LAB"),
    CRGB(0, "CRGB (Reds)", "CRGB"),
    YRD(5, "YRD (Red Diff)", "YRD"),
    YRE(6, "YRE (Red Boost)", "YRE"),
    YYE(7, "YYE (Yellows)", "YYE"),
    YBK(8, "YBK (Blacks)", "YBK"),
    YWE(9, "YWE (Whites)", "YWE"),
    YDT(2, "YDT (General)", "YDT"),
    RGB0(10, "RGB0 (Zero Mean)", "RGB0"),
    YBL(11, "YBL (Blues)", "YBL"),
    YGN(12, "YGN (Greens)", "YGN"),
    DIRECT_PCA(3, "Direct PCA", "PCA"),
    RAW_NORMAL(4, "Normal RAW", "RAW");

    companion object {
        @JvmField
        val RGB_ZCA = CRGB

        fun fromId(id: Int): ColorSpace = entries.firstOrNull { it.id == id } ?: LAB
    }
}
