package com.example.bigspacekeyboard

import android.graphics.Paint

/**
 * 기호 팔레트의 원본 목록. 한 페이지는 10열 x 4행 = 40개.
 *
 * 목록에는 기기에 따라 글리프가 없는 문자도 섞여 있으므로, 페이지를 만들 때
 * [Paint.hasGlyph]로 걸러낸다. 없는 글자는 두부(□)로 보이는 대신 아예 빠진다.
 */
object SymbolCatalog {

    const val COLUMNS = 10

    /** Four rows, same as every other layer, so the keyboard never changes height. */
    const val ROWS = 4
    private const val PER_PAGE = COLUMNS * ROWS

    class Page(
        val category: String,
        val indexInCategory: Int,
        val pagesInCategory: Int,
        val symbols: List<Char>,
    ) {
        val label: String
            get() = if (pagesInCategory > 1) {
                "$category ${indexInCategory + 1}/$pagesInCategory"
            } else {
                category
            }
    }

    private val CATEGORIES: List<Pair<String, String>> = listOf(
        "문장부호" to ".,?!;:'\"`~^_-–—―…‥·※§¶†‡¡¿„‚“”‘’«»‹›、。´¨ˇ˘˙˚˝˜¸˛¯‾",
        "괄호" to "()[]{}〈〉《》「」『』【】〔〕〖〗⟨⟩（）［］｛｝",
        "수학" to "+-×÷=≠≡≒≈∽≪≫<>≤≥±∓∞∫∬∮∑∏√∛∜∂∇∈∉∋∌⊂⊃⊆⊇∪∩∧∨¬∀∃∴∵∝∠⊥∥%‰°′″℃℉",
        "통화" to "₩\$€£¥¢₽₹₺₴₦₱₫฿₭₮₲₡₪₸₼₾¤￦￥￡",
        "단위" to "㎜㎝㎞㎟㎠㎡㎢㎣㎤㎥㎦㎎㎏㎍㎖㎗ℓ㎘㏄㎈㎉㎾㎿㎐㎑㎒㎓㎔㎩㎪㎫㎬㏈㎰㎱㎲㎳㏊㎸㎹㎺㎻㎼㎽",
        "화살표" to "←→↑↓↔↕↖↗↘↙⇐⇒⇑⇓⇔⇕⇖⇗⇘⇙↰↱↲↳↴↵↩↪⇦⇧⇨⇩➔➜➤⟵⟶⟷⤴⤵",
        "도형" to "■□▣▤▥▦▧▨▩▪▫●○◎◉◇◆◈◐◑◒◓△▲▽▼◁◀▷▶◢◣◤◥☰☱☲☳☴☵☶☷",
        "기호" to "★☆♠♣♥♦♤♧♡♢☜☞☝☟✓✔✕✖✗✘⊙⊗⊕⌘⌥⏎⌫␣☎☏♨☂☀☁☃❄♪♬♩♭♯☺☻☹♂♀☯☮〒〓@#&*\\/|",
        "원문자" to "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⓪❶❷❸❹❺⑴⑵⑶⑷⑸⒜⒝⒞ⓐⓑⓒⓓⒶⒷⒸ㉠㉡㉢㉣㉤㉥㉦㉧㉨㉩㈀㈁㈂㈃",
        "그리스" to "αβγδεζηθικλμνξοπρστυφχψωΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩ",
        "숫자" to "ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅰⅱⅲⅳⅴⅵⅶⅷⅸⅹ½⅓⅔¼¾⅛⅜⅝⅞¹²³⁴⁵ⁿ₀₁₂₃₄№℡㈜㉿",
        "라틴" to "ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÑÒÓÔÕÖØÙÚÛÜÝÞßàáâãäåæçèéêëìíîïñòóôõöøùúûüýþÿŒœŠšŸŽž",
    )

    @Volatile
    private var cache: List<Page>? = null

    fun pages(paint: Paint): List<Page> {
        cache?.let { return it }
        val built = mutableListOf<Page>()
        for ((category, source) in CATEGORIES) {
            val available = source.toCharArray().distinct().filter { paint.hasGlyph(it.toString()) }
            if (available.isEmpty()) continue
            val chunks = available.chunked(PER_PAGE)
            chunks.forEachIndexed { index, chunk ->
                built.add(Page(category, index, chunks.size, chunk))
            }
        }
        cache = built
        return built
    }
}
