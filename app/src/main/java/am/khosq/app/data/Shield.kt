package am.khosq.app.data

import java.util.Locale

/** How worried the shield is about a message. */
enum class ShieldLevel { NONE, CAUTION, DANGER }

/**
 * One reason a message looks risky. [code] is a stable name the screen turns into a sentence; [arg] carries one detail
 * (the site name, or the brand a site pretends to be).
 */
data class ShieldFinding(val code: String, val level: ShieldLevel, val arg: String = "")

/** A link found in a message: the text as written, the site it leads to and a few facts about it. */
data class ShieldLink(
    val url: String,
    val host: String,
    /** The link has a "name@" in front of the site, a classic way to show one name and open another. */
    val userInfo: Boolean = false,
    val https: Boolean = true,
)

data class ShieldReport(val level: ShieldLevel, val findings: List<ShieldFinding>, val links: List<ShieldLink>) {
    val risky: Boolean get() = level != ShieldLevel.NONE

    /** The sites of the links, without repeats, in the order they appear. */
    val hosts: List<String> get() = links.map { l -> l.host }.distinct()

    companion object {
        val EMPTY = ShieldReport(ShieldLevel.NONE, emptyList(), emptyList())
    }
}

/**
 * Trust Shield: rules that look at one received text message and say whether it looks like a scam. Everything happens on
 * the phone; nothing is sent anywhere. The rules are plain word and link patterns in Armenian, Russian and English, so they
 * miss things and sometimes warn about a harmless message: the screen says so. No Android in here, so it can be checked
 * on any computer.
 */
object ShieldLogic {
    /** Longer messages are read only up to here. */
    const val MAX_CHARS = 4000

    /** The server's answer for a site that is on Cloudflare's list of dangerous sites. */
    const val BLOCKED = "blocked"

    private val LINK = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>]+")

    private val SHORTENERS = listOf(
        "bit.ly", "tinyurl.com", "t.co", "cutt.ly", "is.gd", "rb.gy", "ow.ly", "shorturl.at", "tiny.cc", "clck.ru", "vk.cc",
        "goo.gl", "buff.ly", "lnkd.in", "s.id", "v.gd", "bl.ink", "t.ly", "u.to",
    )
    private val BARE_SHORT = Regex("(?i)(?<![\\w./@-])(?:" + SHORTENERS.joinToString("|") { s -> Regex.escape(s) } + ")/[^\\s<>]+")

    /** Endings that are cheap or free to register and are used for a great many scam sites. */
    private val BAD_TLD = setOf("tk", "ml", "ga", "cf", "gq", "top", "click", "icu", "monster", "buzz", "zip", "mov", "cyou", "cfd", "sbs", "rest")

    private val TWO_LEVEL = setOf(
        "co.uk", "org.uk", "ac.uk", "com.au", "net.au", "co.in", "co.jp", "com.br", "com.tr", "com.am", "org.am", "net.am",
        "com.ua", "msk.ru", "spb.ru", "com.ge", "co.il", "co.za", "com.cn", "com.mx",
    )

    /** A name a scam site likes to pretend to be, and where the real thing lives. [anyEnding]: the name with any normal ending is the real one. */
    private class Brand(val token: String, val display: String, val official: List<String>, val anyEnding: Boolean)

    private val BRANDS = listOf(
        Brand("paypal", "PayPal", listOf("paypal.com", "paypal.me"), true),
        Brand("google", "Google", listOf("google.com", "goo.gl", "g.co", "googleapis.com", "googleusercontent.com", "gstatic.com", "google-analytics.com", "withgoogle.com", "googlemail.com", "googlevideo.com", "googletagmanager.com", "googleadservices.com"), true),
        Brand("facebook", "Facebook", listOf("facebook.com", "fb.com", "fb.me", "fbcdn.net", "facebook.net", "facebookmail.com"), true),
        Brand("instagram", "Instagram", listOf("instagram.com", "cdninstagram.com"), true),
        Brand("whatsapp", "WhatsApp", listOf("whatsapp.com", "whatsapp.net", "wa.me"), true),
        Brand("telegram", "Telegram", listOf("telegram.org", "telegram.me", "t.me", "telegra.ph"), true),
        Brand("icloud", "iCloud", listOf("icloud.com"), true),
        Brand("microsoft", "Microsoft", listOf("microsoft.com", "live.com", "office.com", "outlook.com", "microsoftonline.com"), true),
        Brand("netflix", "Netflix", listOf("netflix.com", "nflxvideo.net"), true),
        Brand("amazon", "Amazon", listOf("amazon.com", "amazonaws.com", "amzn.to", "a.co"), true),
        Brand("binance", "Binance", listOf("binance.com"), true),
        Brand("yandex", "Yandex", listOf("yandex.ru", "yandex.com", "ya.ru"), true),
        Brand("sberbank", "Sberbank", listOf("sberbank.ru", "sber.ru", "sberbank.com"), false),
        Brand("tinkoff", "Tinkoff", listOf("tinkoff.ru", "tbank.ru"), false),
        Brand("gosuslugi", "Gosuslugi", listOf("gosuslugi.ru"), false),
        Brand("ameriabank", "Ameriabank", listOf("ameriabank.am"), false),
        Brand("inecobank", "Inecobank", listOf("inecobank.am"), false),
        Brand("ardshinbank", "Ardshinbank", listOf("ardshinbank.am"), false),
        Brand("conversebank", "Converse Bank", listOf("conversebank.am"), false),
        Brand("evocabank", "Evocabank", listOf("evocabank.am", "evoca.am"), false),
        Brand("acba", "ACBA", listOf("acba.am"), false),
        Brand("idbank", "IDBank", listOf("idbank.am"), false),
        Brand("idram", "Idram", listOf("idram.am"), false),
        Brand("telcell", "Telcell", listOf("telcell.am"), false),
        Brand("vivacell", "Vivacell-MTS", listOf("vivacell.am", "mts.am"), false),
        Brand("haypost", "HayPost", listOf("haypost.am"), false),
    )

    private val LOGIN_WORDS = Regex("(?i)login|signin|sign-in|verify|secure|account|confirm|password|parol|update|вход|пароль")

    // ---- words, in Armenian, Russian and English. Short English words are whole-word only.

    /** Somebody is being asked to hand something over. */
    private val REQUEST = Regex(
        "(?i)(?:ուղարկ|ասա|ասեք|տուր|տվեք|գրիր|գրեք|թվիր|հաղորդ|մուտքագր|լրացր|հաստատիր|հաստատեք|ներկայացր|պատմիր|թելադր|լուսանկար|նկարիր|կիսվիր" +
            "|отправ|пришл|скаж|назов|продикт|сообщ|введи|напиш|сфотограф|сфоткай|подтверд|поделись|перешли|дай(?![\\p{L}])|дайте" +
            "|\\b(?:send|sent|tell|give|share|enter|provide|read|confirm|type|photo|picture|forward|dictate|submit|reply)\\b)",
    )
    private val INSTALL = Regex(
        "(?i)(?:տեղադր|ներբեռն|բացիր|բացեք|установ|скача|запусти|откро|включи|\\b(?:install|download|launch|open)\\b)",
    )

    private val CODE_WORD = Regex("(?i)(?:կոդ|код|\\b(?:code|otp)\\b)")
    private val SMS_WORD = Regex(
        "(?i)(?:\\bsms\\b|смс|վերիֆ|հաստատման|մեկանգամյա|подтвержд|одноразов|цифр|verification|confirmation|one-time|\\bdigits?\\b|նիշ)",
    )
    private val CARD_WORD = Regex(
        "(?i)(?:քարտի համար|քարտի տվյալ|քարտի ժամկետ|քարտի հետևի|քարտը լուսանկար|номер карт|данные карт|реквизиты карт|срок действия карт|трёхзначн|трехзначн" +
            "|card number|card details|card data|\\bcvv2?\\b|\\bcvc2?\\b|security code on the back)",
    )
    private val PASS_WORD = Regex("(?i)(?:գաղտնաբառ|парол|\\bpasswords?\\b|\\bpasscode\\b|\\bcredentials\\b|login details)")
    private val PIN_WORD = Regex("(?i)(?:փին կոդ|փին-կոդ|пин-код|пин код|\\bpin\\b|(?<![\\p{L}])пин(?![\\p{L}]))")
    private val ID_WORD = Regex(
        "(?i)(?:անձնագր|անձը հաստատող|паспорт|снилс|\\bpassport\\b|\\bssn\\b|id card|identity card|driver'?s licen[cs]e|водительск)",
    )
    private val SEED_WORD = Regex(
        "(?i)(?:seed phrase|recovery phrase|secret phrase|mnemonic|private key|сид[- ]?фраз|секретн\\p{L}* фраз|приватн\\p{L}* ключ|մնեմոնիկ|գաղտնի արտահայտ|մասնավոր բանալ)",
    )
    private val REMOTE_WORD = Regex("(?i)(?:anydesk|teamviewer|rustdesk|quicksupport|ammyy|aweray)")

    private val URGENT = Regex(
        "(?i)(?:շտապ|անհապաղ|հենց հիմա|վերջին հնարավորություն|մի քանի րոպեում|ժամվա ընթացքում|այսօր ևեթ" +
            "|срочно|немедленно|прямо сейчас|в течение часа|в течение 24|последний шанс|пока не поздно|сегодня же" +
            "|\\burgent(?:ly)?\\b|\\bimmediately\\b|\\bright now\\b|\\basap\\b|\\blast chance\\b|within 24 hours|within an hour|\\btoday only\\b|\\bact now\\b|\\bhurry\\b)",
    )
    private val MONEY = Regex(
        "(?i)(?:փող(?!ոց)|գումար|դրամ(?!ա)|փոխանց|վճար|քարտին|քարտ(?!եզ)|կրիպտո|բիթքոյն|բիտկոին|\\busdt\\b|նվեր քարտ|փոխառ" +
            "|деньг|на карт[уе]|оплат|пополн|крипт|биткоин|займ|в долг|одолж|подарочн\\p{L}* карт|перевод(?:а|ом|у)? (?:на|средств|денег)|перевед\\p{L}* (?:мне|на|деньги|средств)" +
            "|\\b(?:money|transfer|wire|pay|payment|paid|gift cards?|crypto|bitcoin|loan|borrow|western union|cash|funds)\\b)",
    )
    private val ACCOUNT = Regex(
        "(?i)(?:account|հաշիվ|аккаунт|акаунт|\\bcard\\b|քարտ(?!եզ)|карт[аыу]|\\bwallet\\b|դրամապանակ|mailbox|էլ\\. ?փոստ|почт)",
    )
    private val LOCKED = Regex(
        "(?i)(?:block|suspend|\\bclos|delet|\\block|limited|deactivat|փակ|արգելափակ|ջնջ|սահմանափակ|заблок|закро|удал|ограничен|приостанов)",
    )
    private val VERIFY = Regex("(?i)(?:verify|confirm|հաստատ|подтверд|верифиц|restore|վերականգն|восстанов)")
    private val PRIZE = Regex(
        "(?i)(?:you(?:'ve| have)? won|you are the winner|you've been selected|selected as a winner|\\blottery\\b|շահել ես|շահեցիր|շահել եք|վիճակախաղ" +
            "|вы выиграли|вы победител|выигрыш|розыгрыш|лотере|(?<![\\p{L}])приз(?![\\p{L}]))",
    )
    private val INVEST = Regex(
        "(?i)(?:guaranteed (?:profit|income|return)|double your|passive income|100% profit|risk[- ]free|crypto signals|forex signals" +
            "|гарантированн\\p{L}* (?:доход|прибыл)|удвоим|пассивн\\p{L}* доход|без риска|երաշխավորված եկամուտ|կրկնապատկ|պասիվ եկամուտ|առանց ռիսկի)",
    )

    // ---- links

    private fun trimUrl(raw: String): String {
        var url = raw
        while (url.isNotEmpty() && url.last() in ".,;:!?)]}»\"'") url = url.dropLast(1)
        return url
    }

    /** The site and the facts of one link, or null when it is not a link. */
    fun parseLink(raw: String): ShieldLink? {
        val url = trimUrl(raw.trim())
        if (url.length < 4) return null
        val scheme = Regex("(?i)^([a-z][a-z0-9+.-]*)://").find(url)
        val rest = if (scheme != null) url.substring(scheme.value.length) else url
        val authority = rest.takeWhile { c -> c != '/' && c != '?' && c != '#' }
        val at = authority.lastIndexOf('@')
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority
        val host = (if (hostPort.startsWith("[")) hostPort else hostPort.substringBefore(':')).lowercase(Locale.ROOT).trimEnd('.')
        if (host.isEmpty() || (!host.contains('.') && !host.startsWith("[") && !isIp(host))) return null
        val https = scheme == null || scheme.groupValues[1].equals("https", ignoreCase = true)
        return ShieldLink(url = url, host = host, userInfo = at >= 0, https = https)
    }

    /** Every link of the text (with a scheme, or "www.", or a well-known short link written bare). */
    fun links(text: String): List<ShieldLink> {
        val t = if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
        val out = ArrayList<ShieldLink>()
        val seen = HashSet<String>()
        for (m in LINK.findAll(t)) parseLink(m.value)?.let { l -> if (seen.add(l.url)) out.add(l) }
        for (m in BARE_SHORT.findAll(t)) parseLink(m.value)?.let { l -> if (seen.add(l.url)) out.add(l) }
        return out
    }

    /** The part of a site's name a person registers: "pay.example.com" -> "example.com", "x.com.am" -> "x.com.am". */
    fun registrable(host: String): String {
        val labels = host.split('.')
        if (labels.size <= 2) return host
        val last2 = labels.takeLast(2).joinToString(".")
        if (last2 in TWO_LEVEL) return labels.takeLast(3).joinToString(".")
        return last2
    }

    private fun ending(host: String): String = host.substringAfterLast('.')

    /** The name in front of the ending: "example" in "pay.example.com". */
    private fun nameOf(host: String): String {
        val reg = registrable(host)
        val labels = reg.split('.')
        return if (labels.size >= 3 && labels.takeLast(2).joinToString(".") in TWO_LEVEL) labels[labels.size - 3] else labels.first()
    }

    private fun isIp(host: String): Boolean {
        if (host.startsWith("[")) return true
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)) return true
        // one big number or a hex number is also an address
        return Regex("^(?:\\d{8,10}|0x[0-9a-f]+)$").matches(host)
    }

    private fun mixedScripts(host: String): Boolean {
        val wanted = setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.GREEK, Character.UnicodeScript.ARMENIAN)
        for (label in host.split('.')) {
            val scripts = HashSet<Character.UnicodeScript>()
            for (ch in label) {
                if (!ch.isLetter()) continue
                val s = Character.UnicodeScript.of(ch.code)
                if (s in wanted) scripts.add(s)
            }
            if (scripts.size > 1) return true
        }
        return false
    }

    /** Letters that a scam site swaps for look-alike digits: "paypa1", "g00gle". */
    private fun deLeet(s: String, oneIsI: Boolean): String {
        val sb = StringBuilder()
        for (c in s) {
            sb.append(
                when (c) {
                    '0' -> 'o'
                    '1' -> if (oneIsI) 'i' else 'l'
                    '3' -> 'e'
                    '4' -> 'a'
                    '5' -> 's'
                    '7' -> 't'
                    else -> c
                },
            )
        }
        return sb.toString().replace("rn", "m")
    }

    /** Is [host] the real site of [b]? */
    private fun isOfficial(host: String, b: Brand): Boolean {
        val reg = registrable(host)
        if (b.official.any { d -> reg == d }) return true
        if (b.anyEnding && nameOf(host) == b.token && ending(host) !in BAD_TLD) return true
        return false
    }

    /** The brand a site pretends to be: its name is in the site's name, but the site is not the brand's. */
    fun lookalike(host: String): String? {
        if (isIp(host)) return null
        // everything in front of the ending: the real names are looked for there
        val before = host.substringBeforeLast('.').lowercase(Locale.ROOT)
        val variants = listOf(before, deLeet(before, false), deLeet(before, true))
        for (b in BRANDS) {
            var hit = false
            for (v in variants) {
                val parts = v.split('.', '-').filter { p -> p.isNotEmpty() }
                if (parts.any { p -> p == b.token || (b.token.length >= 6 && p.contains(b.token)) }) hit = true
            }
            if (hit && !isOfficial(host, b)) return b.display
        }
        return null
    }

    private fun linkFindings(l: ShieldLink): List<ShieldFinding> {
        val out = ArrayList<ShieldFinding>()
        val host = l.host
        if (l.userInfo) out.add(ShieldFinding("link_userinfo", ShieldLevel.DANGER, host))
        if (mixedScripts(host)) out.add(ShieldFinding("link_mixed", ShieldLevel.DANGER, host))
        lookalike(host)?.let { b -> out.add(ShieldFinding("link_brand", ShieldLevel.DANGER, b)) }
        if (isIp(host)) out.add(ShieldFinding("link_ip", ShieldLevel.CAUTION, host))
        if (host in SHORTENERS || registrable(host) in SHORTENERS) out.add(ShieldFinding("link_short", ShieldLevel.CAUTION, host))
        if (host.split('.').any { p -> p.startsWith("xn--") }) out.add(ShieldFinding("link_punycode", ShieldLevel.CAUTION, host))
        if (!isIp(host) && ending(host) in BAD_TLD) out.add(ShieldFinding("link_tld", ShieldLevel.CAUTION, host))
        if (!isIp(host) && nameOf(host).count { c -> c == '-' } >= 3) out.add(ShieldFinding("link_hyphens", ShieldLevel.CAUTION, host))
        if (!l.https && LOGIN_WORDS.containsMatchIn(l.url)) out.add(ShieldFinding("link_http_login", ShieldLevel.CAUTION, host))
        return out
    }

    // ---- the whole message

    /**
     * Looks at one received text message. [stranger] is true while the sender is somebody who has not been accepted yet.
     * [verdicts] holds what the server said about the sites of the links (site -> [BLOCKED]), when the person has allowed that check.
     */
    fun scan(text: String, stranger: Boolean = false, verdicts: Map<String, String> = emptyMap()): ShieldReport {
        if (text.isBlank()) return ShieldReport.EMPTY
        val t0 = if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
        val t = t0.lowercase(Locale.ROOT)
        val links = links(t0)
        val findings = ArrayList<ShieldFinding>()

        for (l in links) {
            findings.addAll(linkFindings(l))
            if (verdicts[l.host] == BLOCKED) findings.add(ShieldFinding("link_blocked", ShieldLevel.DANGER, l.host))
        }

        val ask = REQUEST.containsMatchIn(t)
        if (ask) {
            if (CODE_WORD.containsMatchIn(t) && SMS_WORD.containsMatchIn(t)) findings.add(ShieldFinding("secret_code", ShieldLevel.DANGER))
            if (CARD_WORD.containsMatchIn(t)) findings.add(ShieldFinding("secret_card", ShieldLevel.DANGER))
            if (PASS_WORD.containsMatchIn(t)) findings.add(ShieldFinding("secret_password", ShieldLevel.DANGER))
            if (PIN_WORD.containsMatchIn(t)) findings.add(ShieldFinding("secret_pin", ShieldLevel.DANGER))
            if (SEED_WORD.containsMatchIn(t)) findings.add(ShieldFinding("secret_seed", ShieldLevel.DANGER))
            if (ID_WORD.containsMatchIn(t)) findings.add(ShieldFinding("secret_id", ShieldLevel.CAUTION))
        }
        if (REMOTE_WORD.containsMatchIn(t) && (ask || INSTALL.containsMatchIn(t))) findings.add(ShieldFinding("remote_tool", ShieldLevel.DANGER))

        val money = MONEY.containsMatchIn(t)
        if (money && URGENT.containsMatchIn(t)) findings.add(ShieldFinding("money_urgent", ShieldLevel.CAUTION))
        if (ACCOUNT.containsMatchIn(t) && LOCKED.containsMatchIn(t) && (links.isNotEmpty() || VERIFY.containsMatchIn(t))) {
            findings.add(ShieldFinding("account_threat", if (links.isNotEmpty()) ShieldLevel.DANGER else ShieldLevel.CAUTION))
        }
        if (PRIZE.containsMatchIn(t)) findings.add(ShieldFinding("prize", if (links.isNotEmpty()) ShieldLevel.DANGER else ShieldLevel.CAUTION))
        if (INVEST.containsMatchIn(t)) findings.add(ShieldFinding("invest", ShieldLevel.CAUTION))

        var level = findings.maxOfOrNull { f -> f.level } ?: ShieldLevel.NONE
        if (stranger) {
            // a first message from somebody unknown makes everything above heavier, and a bare link or a talk about money is worth a word
            if (level != ShieldLevel.NONE) {
                level = ShieldLevel.DANGER
            } else {
                if (links.isNotEmpty()) findings.add(ShieldFinding("stranger_link", ShieldLevel.CAUTION))
                if (money) findings.add(ShieldFinding("stranger_money", ShieldLevel.CAUTION))
                if (findings.isNotEmpty()) level = ShieldLevel.CAUTION
            }
        }
        // the same reason once only
        val uniq = findings.distinctBy { f -> f.code + "\u0000" + f.arg }
        return ShieldReport(level, uniq, links)
    }
}
