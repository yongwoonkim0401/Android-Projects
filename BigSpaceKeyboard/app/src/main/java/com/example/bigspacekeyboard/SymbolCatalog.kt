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
        /** 코드포인트 하나가 문자열 하나. 이모지는 BMP 밖이라 Char로는 담기지 않는다. */
        val symbols: List<String>,
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

    /**
     * 윈도우 이모지 선택기(Win + .)에 있는 것들과 같은 범위. 단일 코드포인트만 담는다 —
     * 국기나 ZWJ로 이어붙인 조합형(👨‍👩‍👧)은 기기마다 지원이 갈려서 뺐다.
     * 기기 폰트에 없는 이모지는 [pages]에서 걸러지므로 신형 이모지를 넣어 둬도 안전하다.
     */
    private val EMOJI: List<Pair<String, String>> = listOf(
        "표정" to "😀😃😄😁😆😅🤣😂🙂🙃😉😊😇🥰😍🤩😘😗😚😙😋😛😜🤪😝🤑🤗🤭🤫🤔🤐🤨😐😑😶😏😒🙄😬😮😯😲😳🥺😦😧😨😰😥😢😭😱😖😣😞😓😩😫🥱😤😡😠🤬🤯😳🥵🥶😴🤤😪😵🤐🤢🤮🤧😷🤒🤕🤠🥳🥸🤡👻💀👽🤖🎃😺😸😹😻😼😽🙀😿😾",
        "손짓·사람" to "👍👎👌🤌🤏✌🤞🤟🤘🤙👈👉👆👇☝✋🤚🖐🖖👋🤝🙏💪🦾🦿🦵🦶👂🦻👃🧠🫀🫁🦷🦴👀👁👅👄💋👶🧒👦👧🧑👨👩🧓👴👵🙍🙎🙅🙆💁🙋🧏🙇🤦🤷👮🕵💂👷🤴👸👳👲🧕🤵👰🤰🤱👼🎅🤶🦸🦹🧙🧚🧛🧜🧝🧞🧟",
        "동물·자연" to "🐶🐱🐭🐹🐰🦊🐻🐼🐨🐯🦁🐮🐷🐽🐸🐵🙈🙉🙊🐒🐔🐧🐦🐤🐣🐥🦆🦅🦉🦇🐺🐗🐴🦄🐝🪱🐛🦋🐌🐞🐜🪰🪲🦗🕷🕸🦂🐢🐍🦎🦖🦕🐙🦑🦐🦞🦀🐡🐠🐟🐬🐳🐋🦈🐊🐅🐆🦓🦍🦧🐘🦛🦏🐪🐫🦒🦘🦬🐃🐂🐄🐎🐖🐏🐑🦙🐐🦌🐕🐩🦮🐈🪶🐓🦃🦤🦚🦜🦢🦩🕊🐇🦝🦨🦡🦫🦦🦥🐁🐀🐿🦔🌵🎄🌲🌳🌴🪵🌱🌿☘🍀🎍🎋🍃🍂🍁🍄🐚🪨🌾💐🌷🌹🥀🌺🌸🌼🌻",
        "음식" to "🍏🍎🍐🍊🍋🍌🍉🍇🍓🫐🍈🍒🍑🥭🍍🥥🥝🍅🍆🥑🥦🥬🥒🌶🫑🌽🥕🫒🧄🧅🥔🍠🥐🥯🍞🥖🥨🧀🥚🍳🧈🥞🧇🥓🥩🍗🍖🦴🌭🍔🍟🍕🫓🥪🥙🧆🌮🌯🫔🥗🥘🫕🥫🍝🍜🍲🍛🍣🍱🥟🦪🍤🍙🍚🍘🍥🥠🥮🍢🍡🍧🍨🍦🥧🧁🍰🎂🍮🍭🍬🍫🍿🍩🍪🌰🥜🍯🥛🍼🫖☕🍵🧃🥤🧋🍶🍺🍻🥂🍷🥃🍸🍹🧉🍾🧊🥄🍴🍽🥣🥡🥢🧂",
        "운동·놀이" to "⚽🏀🏈⚾🥎🎾🏐🏉🥏🎱🪀🏓🏸🏒🏑🥍🏏🪃🥅⛳🪁🏹🎣🤿🥊🥋🎽🛹🛼🛷⛸🥌🎿⛷🏂🪂🏋🤼🤸⛹🤺🤾🏌🏇🧘🏄🏊🤽🚣🧗🚵🚴🏆🥇🥈🥉🏅🎖🏵🎗🎫🎟🎪🤹🎭🩰🎨🎬🎤🎧🎼🎹🥁🪘🎷🎺🪗🎸🪕🎻🎲♟🎯🎳🎮🎰🧩",
        "기기·도구" to "⌚📱💻⌨🖥🖨🖱💽💾💿📀📼📷📸📹🎥📽🎞📞☎📟📠📺📻🎙🎚🎛🧭⏱⏲⏰🕰⌛⏳📡🔋🔌💡🔦🕯🪔🧯🛢⚖🪜🧰🪛🔧🔨⚒🛠⛏🪚🔩⚙🪤🧱⛓🧲🔫💣🧨🪓🔪🗡⚔🛡⚗🔭🔬🌡🩹🩺💊💉🩸🧬🦠🧫🧪🔑🗝🔏🔐🔒🔓",
        "생활·집" to "💸💵💴💶💷🪙💰💳💎🚬⚰🪦⚱🏺🔮📿🧿💈🕳🧹🪠🧺🧻🚽🚰🚿🛁🛀🧼🪥🪒🧽🪣🧴🛎🚪🪑🛋🛏🛌🧸🪆🖼🪞🪟🛍🛒🎁🎈🎏🎀🪄🪅🎊🎉🎎🏮🎐🧧",
        "문구·사무" to "✉📩📨📧💌📥📤📦🏷🪧📪📫📬📭📮📯📜📃📄📑🧾📊📈📉🗒🗓📆📅🗑📇🗃🗳🗄📋📁📂🗂🗞📰📓📔📒📕📗📘📙📚📖🔖🧷🔗📎🖇📐📏🧮📌📍✂🖊🖋✒🖌🖍📝✏🔍🔎",
        "탈것·장소" to "🚗🚕🚙🚌🚎🏎🚓🚑🚒🚐🛻🚚🚛🚜🦯🦽🦼🛴🚲🛵🏍🛺🚨🚔🚍🚘🚖🛞🚡🚠🚟🚃🚋🚞🚝🚄🚅🚈🚂🚆🚇🚊🚉✈🛫🛬🛩💺🛰🚀🛸🚁🛶⛵🚤🛥🛳⛴🚢⚓🪝⛽🚧🚦🚥🚏🗺🗿🗽🗼🏰🏯🏟🎡🎢🎠⛲⛱🏖🏝🏜🌋⛰🏔🗻🏕⛺🛖🏠🏡🏘🏚🏗🏭🏢🏬🏣🏤🏥🏦🏨🏪🏫🏩💒🏛⛪🕌🕍🛕🕋⛩🛤🛣🗾🎑🏞🌅🌄🌠🎇🎆🌇🌆🏙🌃🌌🌉🌁",
        "하트·표시" to "❤🧡💛💚💙💜🖤🤍🤎💔❣💕💞💓💗💖💘💝💟☮✝☪🕉☸✡🔯🕎☯☦🛐⛎♈♉♊♋♌♍♎♏♐♑♒♓🆔⚛🉑☢☣📴📳🈶🈚🈸🈺🈷✴🆚💮🉐㊙㊗🈴🈵🈹🈲🅰🅱🆎🆑🅾🆘❌⭕🛑⛔📛🚫💯💢♨🚷🚯🚳🚱🔞📵🚭❗❕❓❔‼⁉🔅🔆〽⚠🚸🔱⚜🔰♻✅🈯💹❇✳❎🌐💠Ⓜ🌀💤🏧🚾♿🅿🈳🈂🛂🛃🛄🛅🚹🚺🚼🚻🚮🎦📶🈁🔣🔤🔡🔠🆖🆗🆙🆒🆕🆓🔟🔢⏏▶⏸⏯⏹⏺⏭⏮⏩⏪⏫⏬🔀🔁🔂🔄🔃🎵🎶💭🗯💬🗨🃏🀄🎴🔇🔈🔉🔊🔔🔕📣📢",
        "날씨·시간" to "☀🌤⛅🌥☁🌦🌧⛈🌩🌨❄☃⛄🌬💨🌪🌫🌈☂☔💧💦🌊🔥💥⚡🌍🌎🌏🌕🌖🌗🌘🌑🌒🌓🌔🌙🌚🌝🌞🪐⭐🌟💫✨☄🕐🕑🕒🕓🕔🕕🕖🕗🕘🕙🕚🕛",
    )

    @Volatile
    private var cache: List<Page>? = null

    fun pages(paint: Paint): List<Page> {
        cache?.let { return it }
        val built = mutableListOf<Page>()
        for ((category, source) in CATEGORIES + EMOJI) {
            val available = codePoints(source).distinct().filter { paint.hasGlyph(it) }
            if (available.isEmpty()) continue
            val chunks = available.chunked(PER_PAGE)
            chunks.forEachIndexed { index, chunk ->
                built.add(Page(category, index, chunks.size, chunk))
            }
        }
        cache = built
        return built
    }

    /** 서로게이트 페어를 쪼개지 않도록 코드포인트 단위로 자른다. */
    private fun codePoints(text: String): List<String> {
        val out = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            out.add(String(Character.toChars(codePoint)))
            index += Character.charCount(codePoint)
        }
        return out
    }
}
