// SyllabusParser.kt
package com.example.semestra.logic

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class ParsedExamEvent(
    val className: String,
    val examTitle: String,
    val eventDate: Long,
    val needsReview: Boolean
)

data class ParseResult(val events: List<ParsedExamEvent>)

class SyllabusParser {

fun parseText(text: String): ParseResult {
    
    android.util.Log.e("COURSE_DEBUG", "COURSE RESULT = $defaultCourse")
    android.util.Log.e("COURSE_DEBUG", "RAW HEADER = ${text.take(200)}")
    
    val cleanedText = text
    .replace('\u00A0', ' ')           // non-breaking space → normal space
    .replace(Regex("""\s+"""), " ")   // collapse whitespace
    .replace(Regex("""([A-Z]{2,4})(\d{4})"""), "$1 $2") // CSE3310 → CSE 3310

val lines = cleanedText.lines()
    android.util.Log.d("COURSE_DEBUG", "Total lines: ${lines.size}")
    lines.take(10).forEachIndexed { i, line ->
        android.util.Log.d("COURSE_DEBUG", "Line $i raw: ${line.map { it.code }.take(30)}")
        android.util.Log.d("COURSE_DEBUG", "Line $i str: [${line.trim()}]")
    }
    
    // Test the regex directly here, before extractDefaultCourse
    val testRegex = Regex("""([A-Z]{2,4})[\s]?(\d{4})""")
    val directHit = testRegex.find(text.take(2000))
    android.util.Log.d("COURSE_DEBUG", "Direct regex hit on raw text: ${directHit?.value}")
    
    val defaultCourse = extractDefaultCourse(text)
    android.util.Log.d("COURSE_DEBUG", "extractDefaultCourse returned: [$defaultCourse]")
    
    val className = defaultCourse ?: "Unknown Course"
    android.util.Log.d("COURSE_DEBUG", "className will be: [$className]")
    // ===== END TEMPORARY DEBUG =====
    val needsReview = defaultCourse == null

    val claimedLineIndices = mutableSetOf<Int>()
    val events = mutableListOf<ParsedExamEvent>()

    // --- Pass 1: exam/quiz/test/midterm/final keyword scan ---
    for ((index, line) in lines.withIndex()) {
        val examMatch = EXAM_LABEL_REGEX.find(line) ?: continue
        val searchWindow = lines.drop(index).take(4).joinToString(" ")
        val dateMatches = DATE_REGEX.findAll(searchWindow).toList()
        val dateMatch = dateMatches.firstOrNull() ?: continue
        val timeMatch = TIME_REGEX.find(searchWindow)
        val epochMs = parseToEpoch(dateMatch.value.trim(), timeMatch?.value?.trim()) ?: continue
        val multipleDates = dateMatches.size > 1

        events.add(
            ParsedExamEvent(
                className = className,
                examTitle = examMatch.value.replaceFirstChar { it.uppercase() }.trim(),
                eventDate = epochMs,
                needsReview = needsReview || multipleDates
            )
        )
        claimedLineIndices.add(index)
    }

    // --- Pass 2: date-prefixed single-line schedule rows ---
    val pass1Keys = events.map { normalizeTitle(it.examTitle) to it.eventDate }.toSet()

    for ((index, rawLine) in lines.withIndex()) {
        if (index in claimedLineIndices) continue
        val line = rawLine.trim()
        val prefix = ROW_DATE_PREFIX.find(line) ?: continue
        val dateText = prefix.value.trim()
        val eventDate = parseToEpoch(dateText, null) ?: continue
        val tail = line.removePrefix(prefix.value).trim(' ', '-', ':', '|', '\t')
        val contextTail = if (tail.isNotBlank()) tail else {
            lines.drop(index + 1).firstOrNull { it.trim().isNotBlank() }?.trim().orEmpty()
        }
        if (!looksLikeScheduleRow(contextTail)) continue
        val title = buildSessionTitle(contextTail)
        val normalizedTitle = normalizeTitle(title)
        val isDuplicate = pass1Keys.any { (existingTitle, existingDate) ->
            existingDate == eventDate && (
                existingTitle.contains(normalizedTitle) ||
                normalizedTitle.contains(existingTitle)
            )
        }
        if (isDuplicate) continue

        events.add(
            ParsedExamEvent(
                className = className,
                examTitle = title,
                eventDate = eventDate,
                needsReview = needsReview
            )
        )
        claimedLineIndices.add(index)
    }

    // --- Pass 3: bare date lines (PDF table layout) ---
    val allKnownKeys = events.map { normalizeTitle(it.examTitle) to it.eventDate }.toMutableSet()

    var i = 0
    while (i < lines.size) {
        if (i in claimedLineIndices) { i++; continue }
        val line = lines[i].trim()
        val bareDate = BARE_DATE_LINE.matchEntire(line)
        if (bareDate == null) { i++; continue }

        val dateCandidates = mutableListOf<Pair<Int, Long>>()
        var j = i
        while (j < lines.size && j !in claimedLineIndices) {
            val l = lines[j].trim()
            val m = BARE_DATE_LINE.matchEntire(l) ?: break
            val epoch = parseToEpoch(m.value.trim(), null)
            if (epoch != null) dateCandidates.add(j to epoch)
            else break
            j++
        }
        if (dateCandidates.isEmpty()) { i++; continue }

        var topicIndex = j
        while (topicIndex < lines.size && lines[topicIndex].trim().isBlank()) topicIndex++
        if (topicIndex >= lines.size) { i = j; continue }

        val topicLines = mutableListOf<String>()
        var k = topicIndex
        while (k < lines.size && k < topicIndex + 4) {
            val tl = lines[k].trim()
            if (BARE_DATE_LINE.matches(tl) || tl.matches(Regex("""(?i)class\s+date.*"""))) break
            if (tl.isNotBlank()) topicLines.add(tl)
            k++
        }

        val topicRaw = topicLines.joinToString(" ").trim()
        if (!looksLikeScheduleRow(topicRaw)) { i = j; continue }

        val title = buildSessionTitle(topicRaw)
        val normalizedTitle = normalizeTitle(title)

        for ((dateLineIdx, epoch) in dateCandidates) {
            val isDuplicate = allKnownKeys.any { (existingTitle, existingDate) ->
                existingDate == epoch && (
                    existingTitle.contains(normalizedTitle) ||
                    normalizedTitle.contains(existingTitle)
                )
            }
            if (!isDuplicate) {
                events.add(
                    ParsedExamEvent(
                        className = className,
                        examTitle = title,
                        eventDate = epoch,
                        needsReview = needsReview
                    )
                )
                allKnownKeys.add(normalizedTitle to epoch)
                claimedLineIndices.add(dateLineIdx)
            }
        }
        for (idx in topicIndex until k) claimedLineIndices.add(idx)
        i = k
    }

    val distinct = events
        .groupBy { it.eventDate to normalizeTitle(it.examTitle) }
        .map { (_, group) ->
            group.firstOrNull { !it.examTitle.startsWith("Class:") } ?: group.first()
        }
        .sortedBy { it.eventDate }

    return ParseResult(distinct)
}

    private fun extractClassNameFromLines(lines: List<String>, defaultCourse: String?): String {
        for (line in lines) {
            val match = COURSE_CODE_REGEX.find(line) ?: continue
            return normalizeCourseCode(match.value)
        }
        return defaultCourse ?: "Unknown Course"
    }

    private fun normalizeCourseCode(raw: String): String {
    	val letters = Regex("""[A-Za-z]+""").find(raw)?.value?.uppercase() ?: return raw.uppercase()
    	val digits = Regex("""\d{4}""").find(raw)?.value ?: return raw.uppercase()
    	return "$letters $digits"
	}

    private fun normalizeTitle(title: String): String {
        return title
            .removePrefix("Class:")
            .lowercase(Locale.US)
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun parseToEpoch(dateStr: String, timeStr: String?): Long? {
        val cleanDate = dateStr.replace(Regex("""(\d+)(?:st|nd|rd|th)"""), "$1").trim()
        val fullStr = if (timeStr != null) "$cleanDate $timeStr" else cleanDate

        val formatsToTry = if (timeStr != null) {
            DATE_FORMATS.flatMap { df ->
                listOf(
                    SimpleDateFormat("${df.toPattern()} h:mm a", Locale.US),
                    SimpleDateFormat("${df.toPattern()} ha", Locale.US),
                    SimpleDateFormat("${df.toPattern()} HH:mm", Locale.US),
                    df
                )
            }
        } else {
            DATE_FORMATS
        }

        for (fmt in formatsToTry) {
            try {
                fmt.isLenient = false
                val date = fmt.parse(fullStr) ?: fmt.parse(cleanDate) ?: continue
                val cal = Calendar.getInstance().apply { time = date }
                if (cal.get(Calendar.YEAR) < 2000) {
                    cal.set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR))
                }
                return cal.timeInMillis
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun extractDefaultCourse(text: String): String? {
    // Normalize PDF garbage first
    val cleaned = text
        .replace('\u00A0', ' ')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")

    // Try multiple patterns explicitly (don’t rely on one regex)
    val patterns = listOf(
        Regex("""\b([A-Z]{2,4})\s*(\d{4})\b"""),
        Regex("""\b([A-Z]{2,4})(\d{4})\b"""),
        Regex("""([A-Z]{2,4})\s*:\s*""") // catches "CSE3310:"
    )

    for (regex in patterns) {
        val match = regex.find(cleaned) ?: continue
        val raw = match.value

        // If it's the ":" format, extract prefix manually
        if (raw.contains(":")) {
            val prefix = raw.substringBefore(":").trim()
            val codeMatch = Regex("""([A-Z]{2,4})\s*(\d{4})""").find(prefix)
            if (codeMatch != null) return normalizeCourseCode(codeMatch.value)
        }

        return normalizeCourseCode(raw)
    }

    return null
}

    private fun looksLikeScheduleRow(content: String): Boolean {
        if (content.isBlank()) return false
        val lower = content.lowercase(Locale.US)
        val blacklist = listOf(
            "institutional information", "additional information",
            "drop policy", "emergency", "http://", "https://",
            "catalog", "grade grievances"
        )
        if (blacklist.any { lower.contains(it) }) return false
        val scheduleKeywords = listOf(
            "topic", "chapter", "assignment", "project", "midterm", "final",
            "quiz", "discussion", "training", "presentation", "class", "spring break",
            "lab", "test", "review", "requirements", "design", "modeling",
            "introduction", "software", "engineering", "processes", "uml",
            "security", "agile", "exam", "increment", "binder"
        )
        return scheduleKeywords.any { lower.contains(it) } || content.length > 18
    }

    private fun buildSessionTitle(content: String): String {
        val condensed = content.replace(Regex("""\s+"""), " ").trim()
        if (condensed.isBlank()) return "Class session"
        val clean = condensed.take(100)
        return if (clean.contains("no class", ignoreCase = true) ||
                   clean.contains("spring break", ignoreCase = true)) {
            clean.replaceFirstChar { it.uppercase() }
        } else {
            "Class: $clean"
        }
    }

    companion object {
        val COURSE_CODE_REGEX = Regex(
    		"""(?<![A-Z])([A-Z]{2,4})\s*[-: ]?\s*(\d{4})(?!\d)"""
	)

        private val EXAM_LABEL_REGEX = Regex(
            """((?:midterm|final|exam|quiz|test)\s*(?:\d+|i{1,3}|iv)?)\b""",
            RegexOption.IGNORE_CASE
        )
        private val DATE_REGEX = Regex(
            """(?:(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|""" +
                """Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\s+\d{1,2}(?:st|nd|rd|th)?,?\s+\d{4}|""" +
                """\d{1,2}[/\-]\d{1,2}[/\-]\d{2,4}|""" +
                """\d{4}-\d{2}-\d{2})""",
            RegexOption.IGNORE_CASE
        )
        private val TIME_REGEX = Regex(
            """\b(\d{1,2}:\d{2}\s*(?:AM|PM)|(\d{1,2}(?:AM|PM))|\d{2}:\d{2})\b""",
            RegexOption.IGNORE_CASE
        )
        private val ROW_DATE_PREFIX = Regex(
            """^\s*(\d{1,2}/\d{1,2}/\d{2,4}|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{1,2},?\s+\d{2,4})""",
            RegexOption.IGNORE_CASE
        )
        // Matches a line that is ONLY a date (with optional surrounding whitespace)
        // Handles: "1/13/2026", "Jan 13, 2026", "1/13/2026 &", "1/27/2026 &"
        private val BARE_DATE_LINE = Regex(
            """^\s*(\d{1,2}[/\-]\d{1,2}[/\-]\d{2,4}|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\.?\s+\d{1,2},?\s*\d{0,4})\s*(?:&|and)?\s*${'$'}""",
            RegexOption.IGNORE_CASE
        )
        private val DATE_FORMATS = listOf(
            SimpleDateFormat("MMMM d, yyyy", Locale.US),
            SimpleDateFormat("MMMM d yyyy", Locale.US),
            SimpleDateFormat("MMM d, yyyy", Locale.US),
            SimpleDateFormat("MMM d yyyy", Locale.US),
            SimpleDateFormat("MM/dd/yyyy", Locale.US),
            SimpleDateFormat("MM-dd-yyyy", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US),
            SimpleDateFormat("MM/dd/yy", Locale.US),
            SimpleDateFormat("MMMM d", Locale.US),
            SimpleDateFormat("MMM d", Locale.US),
        )
    }
}