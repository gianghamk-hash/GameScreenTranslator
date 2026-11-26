package com.giang.gametranslator

import java.util.Locale
import java.util.regex.Pattern

/**
 * Bộ từ điển & xử lý ngôn ngữ Game Offline (0ms):
 * 1. Kho ~220+ từ/cụm từ Menu, UI, RPG, Hành động, Visual Novel dịch chuẩn ngữ cảnh game tức thì.
 * 2. Hỗ trợ từ điển tùy chỉnh dạng "Từ gốc = Nghĩa Việt" (vd: Guild = Công hội) ngay trong ô Glossary.
 * 3. Bảo vệ tên nhân vật đứng đầu câu thoại ("Cloud: ..."), thuật ngữ game (HP, MP, Buff, Quest...)
 *    và chuẩn hóa tiếng lóng trước khi đưa vào ML Kit Translate để bản dịch Offline tự nhiên hơn hẳn.
 */
object OfflineGameDictionary {

    // Kho cụm từ Menu / UI / RPG / Action phổ biến (khớp nguyên câu/cụm -> trả về 0ms)
    private val exactGamePhrases: Map<String, String> = mapOf(
        // Menu chính & Hệ thống
        "new game" to "Chơi mới",
        "continue" to "Tiếp tục",
        "load game" to "Tải dữ liệu",
        "save game" to "Lưu tiến trình",
        "save" to "Lưu",
        "load" to "Tải",
        "quick save" to "Lưu nhanh",
        "quick load" to "Tải nhanh",
        "auto save" to "Tự động lưu",
        "autosave" to "Tự động lưu",
        "settings" to "Cài đặt",
        "options" to "Tùy chọn",
        "controls" to "Điều khiển",
        "keybindings" to "Gán phím",
        "graphics" to "Đồ họa",
        "audio" to "Âm thanh",
        "sound" to "Âm thanh",
        "music" to "Nhạc nền",
        "volume" to "Âm lượng",
        "language" to "Ngôn ngữ",
        "subtitles" to "Phụ đề",
        "brightness" to "Độ sáng",
        "difficulty" to "Độ khó",
        "easy" to "Dễ",
        "normal" to "Thường",
        "hard" to "Khó",
        "very hard" to "Rất khó",
        "nightmare" to "Ác mộng",
        "resume" to "Tiếp tục",
        "pause" to "Tạm dừng",
        "paused" to "Đang tạm dừng",
        "restart" to "Chơi lại",
        "retry" to "Thử lại",
        "quit" to "Thoát",
        "exit" to "Thoát",
        "exit game" to "Thoát game",
        "return to title" to "Về màn hình chính",
        "main menu" to "Menu chính",
        "back" to "Quay lại",
        "confirm" to "Xác nhận",
        "cancel" to "Hủy",
        "apply" to "Áp dụng",
        "reset" to "Đặt lại",
        "default" to "Mặc định",
        "yes" to "Có",
        "no" to "Không",
        "ok" to "Đồng ý",
        "close" to "Đóng",
        "skip" to "Bỏ qua",
        "next" to "Tiếp theo",
        "previous" to "Trước đó",
        "loading" to "Đang tải...",
        "loading..." to "Đang tải...",
        "press any key" to "Nhấn phím bất kỳ",
        "press any button" to "Nhấn nút bất kỳ",
        "press start" to "Nhấn Start",
        "saving..." to "Đang lưu...",
        "do not turn off the power" to "Đừng tắt nguồn máy",

        // Nhân vật, Kho đồ & Kỹ năng
        "inventory" to "Kho đồ",
        "bag" to "Túi đồ",
        "items" to "Vật phẩm",
        "equipment" to "Trang bị",
        "equip" to "Trang bị",
        "unequip" to "Tháo trang bị",
        "use" to "Sử dụng",
        "drop" to "Vứt bỏ",
        "discard" to "Hủy bỏ",
        "craft" to "Chế tạo",
        "crafting" to "Chế tạo",
        "upgrade" to "Nâng cấp",
        "enhance" to "Cường hóa",
        "sell" to "Bán",
        "buy" to "Mua",
        "shop" to "Cửa hàng",
        "merchant" to "Thương nhân",
        "skills" to "Kỹ năng",
        "skill tree" to "Bảng kỹ năng",
        "abilities" to "Năng lực",
        "magic" to "Phép thuật",
        "spells" to "Phép thuật",
        "status" to "Trạng thái",
        "stats" to "Chỉ số",
        "attributes" to "Thuộc tính",
        "party" to "Đội hình",
        "character" to "Nhân vật",
        "map" to "Bản đồ",
        "world map" to "Bản đồ thế giới",
        "fast travel" to "Dịch chuyển nhanh",
        "journal" to "Nhật ký",
        "codex" to "Bách khoa",
        "quests" to "Nhiệm vụ",
        "quest log" to "Nhật ký nhiệm vụ",
        "main quest" to "Nhiệm vụ chính",
        "side quest" to "Nhiệm vụ phụ",
        "objective" to "Mục tiêu",
        "objectives" to "Mục tiêu",

        // Chỉ số chiến đấu
        "health" to "Máu (HP)",
        "mana" to "Năng lượng (MP)",
        "stamina" to "Thể lực",
        "attack" to "Tấn công",
        "defense" to "Phòng thủ",
        "speed" to "Tốc độ",
        "agility" to "Nhanh nhẹn",
        "strength" to "Sức mạnh",
        "intelligence" to "Trí tuệ",
        "luck" to "May mắn",
        "critical" to "Chí mạng",
        "critical hit" to "Đòn chí mạng!",
        "crit rate" to "Tỷ lệ chí mạng",
        "crit damage" to "Sát thương chí mạng",
        "cooldown" to "Hồi chiêu",
        "damage" to "Sát thương",
        "armor" to "Giáp",
        "shield" to "Khiên",
        "dodge" to "Né tránh",
        "block" to "Đỡ đòn",
        "parry" to "Phản đòn",
        "heal" to "Hồi máu",
        "revive" to "Hồi sinh",
        "poison" to "Trúng độc",
        "burn" to "Thiêu đốt",
        "freeze" to "Đóng băng",
        "stun" to "Choáng",
        "level" to "Cấp",
        "level up" to "Lên cấp!",
        "level up!" to "Lên cấp!",
        "experience" to "Kinh nghiệm",
        "gold" to "Vàng",
        "coins" to "Xu",
        "reward" to "Phần thưởng",
        "rewards" to "Phần thưởng",

        // Thông báo trận đấu & Cốt truyện
        "game over" to "Thua cuộc (Game Over)",
        "you died" to "Bạn đã tử trận",
        "defeat" to "Thất bại",
        "victory" to "Chiến thắng!",
        "quest accepted" to "Đã nhận nhiệm vụ",
        "quest completed" to "Hoàn thành nhiệm vụ!",
        "quest complete" to "Hoàn thành nhiệm vụ!",
        "mission accomplished" to "Nhiệm vụ hoàn tất!",
        "mission failed" to "Nhiệm vụ thất bại",
        "new area discovered" to "Khám phá vùng đất mới",
        "locked" to "Đã khóa",
        "unlocked" to "Đã mở khóa",
        "need a key" to "Cần chìa khóa",
        "not enough mana" to "Không đủ năng lượng",
        "not enough gold" to "Không đủ vàng",
        "inventory full" to "Kho đồ đã đầy",
        "talk" to "Nói chuyện",
        "speak" to "Nói chuyện",
        "examine" to "Kiểm tra",
        "inspect" to "Quan sát",
        "open" to "Mở",
        "pick up" to "Nhặt lên",
        "take" to "Lấy",
        "interact" to "Tương tác",
        "rest" to "Nghỉ ngơi",
        "sleep" to "Ngủ",
        "wait" to "Chờ đợi",
        "follow me" to "Đi theo ta",
        "let's go" to "Đi thôi!",
        "let's go!" to "Đi thôi!",
        "hold on" to "Khoan đã",
        "wait a minute" to "Chờ một chút",
        "what's going on?" to "Chuyện gì đang xảy ra vậy?",
        "are you sure?" to "Bạn có chắc không?",
        "thank you" to "Cảm ơn",
        "thank you!" to "Cảm ơn!",
        "sorry" to "Xin lỗi",
        "i'm sorry" to "Tôi xin lỗi",
        "be careful" to "Hãy cẩn thận",
        "be careful!" to "Cẩn thận đấy!",
        "watch out!" to "Coi chừng!",
        "look out!" to "Cẩn thận!",
        "run!" to "Chạy mau!",
        "hurry up!" to "Nhanh lên!",
        "don't move!" to "Đứng yên!",
        "never mind" to "Không có gì đâu",
        "of course" to "Tất nhiên rồi",
        "i see" to "Ra vậy",
        "really?" to "Thật sao?",
        "no way!" to "Không thể nào!",
        "what?!" to "Cái gì?!",
        "who are you?" to "Ngươi là ai?",
        "where are we?" to "Đây là đâu?"
    )

    // Thuật ngữ game giữ nguyên nếu xuất hiện dạng từ riêng lẻ
    private val protectedGameTokens = listOf(
        "HP", "MP", "SP", "EXP", "XP", "ATK", "DEF", "SPD", "CRIT", "DPS", "AOE", "NPC", "HUD", "UI", "FPS"
    )

    // Chuẩn hóa tiếng lóng / viết tắt trước khi đưa vào ML Kit
    private val slangExpansions: List<Pair<Regex, String>> = listOf(
        Regex("\\bgonna\\b", RegexOption.IGNORE_CASE) to "going to",
        Regex("\\bwanna\\b", RegexOption.IGNORE_CASE) to "want to",
        Regex("\\bgotta\\b", RegexOption.IGNORE_CASE) to "have to",
        Regex("\\bain't\\b", RegexOption.IGNORE_CASE) to "is not",
        Regex("\\blemme\\b", RegexOption.IGNORE_CASE) to "let me",
        Regex("\\bgimme\\b", RegexOption.IGNORE_CASE) to "give me",
        Regex("\\bkinda\\b", RegexOption.IGNORE_CASE) to "kind of",
        Regex("\\bsorta\\b", RegexOption.IGNORE_CASE) to "sort of",
        Regex("\\bdunno\\b", RegexOption.IGNORE_CASE) to "do not know",
        Regex("\\bc'mon\\b", RegexOption.IGNORE_CASE) to "come on",
        Regex("\\bya\\b", RegexOption.IGNORE_CASE) to "you",
        Regex("\\b'cause\\b", RegexOption.IGNORE_CASE) to "because"
    )

    data class PreparedOfflineText(
        val textForMlKit: String,
        val placeholders: Map<String, String>,
        val speakerPrefix: String?
    )

    fun lookupExactPhrase(rawText: String, settings: TranslatorSettings): String? {
        val clean = rawText.trim().replace(Regex("\\s+"), " ")
        val lower = clean.lowercase(Locale.ROOT)

        // 1. Ưu tiên khớp từ điển tùy chỉnh "A = B" của người dùng
        for ((src, target) in settings.customReplacements()) {
            if (src.equals(clean, ignoreCase = true)) {
                return target
            }
        }

        // 2. Khớp kho cụm từ Game tích hợp sẵn (0ms)
        exactGamePhrases[lower]?.let { return it }
        val stripped = lower.trimEnd('.', '!', '?', ':', ' ')
        exactGamePhrases[stripped]?.let { return it }
        return null
    }

    fun prepareForOffline(rawText: String, settings: TranslatorSettings): PreparedOfflineText {
        var text = rawText.trim().replace(Regex("\\s+"), " ")
        var speakerPrefix: String? = null

        // Tách tên nhân vật ở đầu câu thoại (vd "Cloud: Let's go") để không bị dịch tên người
        val speakerMatch = Regex("^([A-Za-z0-9_.'\\- ]{2,22}):\\s+(.+)$").find(text)
        if (speakerMatch != null) {
            val candidateSpeaker = speakerMatch.groupValues[1].trim()
            val body = speakerMatch.groupValues[2].trim()
            val lowerSpeaker = candidateSpeaker.lowercase(Locale.ROOT)
            if (lowerSpeaker !in setOf("tip", "note", "hint", "warning", "quest", "objective", "system")) {
                speakerPrefix = candidateSpeaker
                text = body
            }
        }

        // Mở rộng tiếng lóng để ML Kit dịch đúng nghĩa
        for ((regex, replacement) in slangExpansions) {
            text = regex.replace(text, replacement)
        }

        val placeholders = mutableMapOf<String, String>()
        var tokenIndex = 0

        fun protectPattern(sourceTerm: String, finalOutput: String) {
            val token = "ZXQ${tokenIndex++}K"
            val pattern = Pattern.compile("\\b${Pattern.quote(sourceTerm)}\\b", Pattern.CASE_INSENSITIVE)
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                placeholders[token] = finalOutput
                text = matcher.replaceAll(token)
            }
        }

        // 1. Bảo vệ & thay thế các cặp "Từ gốc = Nghĩa Việt" của người dùng
        for ((src, target) in settings.customReplacements().sortedByDescending { it.first.length }) {
            protectPattern(src, target)
        }

        // 2. Bảo vệ tên riêng / địa danh cần giữ nguyên
        for (term in settings.glossaryTerms().sortedByDescending { it.length }) {
            protectPattern(term, term)
        }

        // 3. Bảo vệ thuật ngữ game viết tắt (HP, MP, EXP...)
        for (abbr in protectedGameTokens) {
            val token = "ZXQ${tokenIndex++}K"
            val pattern = Pattern.compile("\\b${Pattern.quote(abbr)}\\b")
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                placeholders[token] = abbr
                text = matcher.replaceAll(token)
            }
        }

        return PreparedOfflineText(text, placeholders, speakerPrefix)
    }

    fun postProcessOffline(
        rawTranslated: String,
        prepared: PreparedOfflineText,
        style: TranslationStyle
    ): String {
        var out = rawTranslated.trim()

        for ((token, finalVal) in prepared.placeholders) {
            out = out.replace(token, finalVal, ignoreCase = true)
        }

        // Sửa khoảng trắng thừa quanh dấu câu
        out = out
            .replace(Regex("\\s+([,.!?;:])"), "$1")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        // Tinh chỉnh nhẹ văn phong RPG nếu có đại từ khô cứng
        if (style == TranslationStyle.RPG_FANTASY) {
            out = out
                .replace(Regex("\\bXin chào bạn\\b", RegexOption.IGNORE_CASE), "Chào ngươi")
                .replace(Regex("\\bCảm ơn bạn\\b", RegexOption.IGNORE_CASE), "Đa tạ")
        }

        if (prepared.speakerPrefix != null) {
            out = "${prepared.speakerPrefix}: $out"
        }
        return out
    }
}
