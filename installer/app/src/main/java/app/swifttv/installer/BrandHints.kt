package app.swifttv.installer

/**
 * Brand recognition and TV-side setup hints. Kept free of Android APIs so the
 * mapping can be unit-tested on the JVM.
 */
object BrandHints {

    private val brandNames = mapOf(
        "xiaomi" to "小米",
        "redmi" to "Redmi",
        "hisense" to "海信",
        "tcl" to "TCL",
        "sony" to "索尼",
        "skyworth" to "创维",
        "coocaa" to "酷开",
        "changhong" to "长虹",
        "huawei" to "华为",
        "honor" to "荣耀",
        "philips" to "飞利浦",
        "sharp" to "夏普",
        "panasonic" to "松下",
        "toshiba" to "东芝",
        "google" to "Google",
        "amazon" to "Amazon",
        "nvidia" to "NVIDIA",
        "mecool" to "MECOOL",
        "amlogic" to "Amlogic",
        "rockchip" to "Rockchip",
        "realtek" to "Realtek",
        "mediatek" to "MediaTek",
        "oppo" to "OPPO",
        "vivo" to "vivo",
        "oneplus" to "一加",
        "samsung" to "三星",
        "lg" to "LG",
        "haier" to "海尔",
        "konka" to "康佳",
        "lecoo" to "乐视",
        "letv" to "乐视",
        "fission" to "风行",
        "funshion" to "风行",
        "wasu" to "华数",
        "mango" to "芒果",
        "china" to "中国移动",
        "cmcc" to "中国移动",
        "unicom" to "中国联通",
        "telecom" to "中国电信"
    )

    fun normalize(raw: String?): String = raw.orEmpty().trim().lowercase()

    fun displayBrand(raw: String?): String {
        val value = raw.orEmpty().trim()
        if (value.isEmpty()) return ""
        val lower = value.lowercase()
        brandNames[lower]?.let { return it }
        // Manufacturer strings frequently look like "Xiaomi Communications Co Ltd".
        val match = brandNames.entries.firstOrNull { lower.contains(it.key) }
        return match?.value ?: value
    }

    fun isTvLike(characteristics: String?, model: String?, brand: String?): Boolean {
        val chars = normalize(characteristics)
        if (chars.contains("tv") || chars.contains("box") || chars.contains("stb")) return true
        val maker = normalize(brand) + " " + normalize(model)
        return listOf("tv", "television", "smarttv", "androidtv", "googletv").any { maker.contains(it) }
    }

    fun setupHint(manufacturer: String?, brand: String?, model: String?): String {
        val raw = listOfNotNull(manufacturer, brand, model).joinToString(" ").lowercase()
        return when {
            raw.contains("xiaomi") || raw.contains("redmi") ->
                "小米/Redmi 电视：设置 → 关于 → 产品型号连点 7 次，进入开发者选项，打开“USB 调试”；部分机型还需要打开“网络调试”。"

            raw.contains("hisense") ->
                "海信电视：设置 → 关于 → 版本号连点 7 次，进入开发者选项，打开“USB 调试”。聚好看机型可在“网络调试”中开启 5555。"

            raw.contains("tcl") || raw.contains("雷鸟") || raw.contains("ffalcon") ->
                "TCL/雷鸟电视：设置 → 系统 → 关于 → 版本号连点 7 次，进入开发者选项，打开“USB 调试”。"

            raw.contains("sony") ->
                "索尼电视：设置 → 系统 → 关于 → 版本号连点 7 次，进入开发者选项，打开“USB 调试”；Android 11+ 机型可在“无线调试”中配对。"

            raw.contains("skyworth") || raw.contains("coocaa") || raw.contains("酷开") ->
                "创维/酷开电视：设置 → 关于本机 → 版本号连点 7 次，进入开发者选项，打开“USB 调试”。"

            raw.contains("changhong") ->
                "长虹电视：设置 → 关于 → 版本号连点 7 次，进入开发者选项，打开“USB 调试”。"

            raw.contains("huawei") || raw.contains("honor") ->
                "华为/荣耀智慧屏：设置 → 关于 → 版本号连点 7 次，进入开发者选项，打开“USB 调试”；首次连接需在电视上允许调试。"

            raw.contains("philips") ->
                "飞利浦电视：设置 → 关于 → 版本号连点 7 次，进入开发者选项，打开“USB 调试”。"

            raw.contains("google") || raw.contains("chromecast") ->
                "Google TV：设置 → 系统 → 关于 → 版本号连点 7 次，进入开发者选项；Android 11+ 使用“无线调试”配对。"

            else ->
                "通用方法：设置 → 关于 → 版本号连点 7 次，进入开发者选项，打开“USB 调试”。首次连接时在电视上点“允许”。"
        }
    }
}
