package com.luachitim.data

private fun pad2(n: Int) = if (n < 10) "0$n" else n.toString()
private fun pad4(n: Int) = n.toString().padStart(4, '0')

private fun icsEscapeText(s: String): String =
    s.replace("\\", "\\\\")
     .replace(";", "\\;")
     .replace(",", "\\,")
     .replace("\r\n", "\\n")
     .replace("\n", "\\n")

private fun icsUnescapeText(s: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                'n', 'N' -> { out.append('\n'); i += 2 }
                ';'      -> { out.append(';');  i += 2 }
                ','      -> { out.append(',');  i += 2 }
                '\\'     -> { out.append('\\'); i += 2 }
                else     -> { out.append(c);    i += 1 }
            }
        } else {
            out.append(c); i += 1
        }
    }
    return out.toString()
}

// RFC 5545 recommends folding lines at 75 octets. We fold generously early
// (73 chars) purely for compatibility with strict/older parsers - most modern
// calendar apps don't actually require this, but it costs nothing to do.
private fun foldIcsLine(line: String): String {
    if (line.length <= 73) return line
    val sb = StringBuilder()
    var start = 0
    var first = true
    while (start < line.length) {
        val end = minOf(start + 73, line.length)
        if (!first) sb.append("\r\n ")
        sb.append(line, start, end)
        start = end
        first = false
    }
    return sb.toString()
}

/**
 * Builds a standard iCalendar (.ics) document from the given diary events.
 * Every event becomes a whole-day VEVENT (diary entries have no time-of-day
 * component to begin with). Meant for one-shot export via a share/save
 * dialog - not a live sync feed.
 */
fun buildIcs(events: List<DiaryEvent>, repo: LuachRepository, todayJd: Long): String {
    val (ty, tm, td) = repo.jdToGregorian(todayJd)
    val stamp = "${pad4(ty)}${pad2(tm)}${pad2(td)}T000000Z"

    val sb = StringBuilder()
    sb.append("BEGIN:VCALENDAR\r\n")
    sb.append("VERSION:2.0\r\n")
    sb.append("PRODID:-//LuachItim//Diary Export//HE\r\n")
    sb.append("CALSCALE:GREGORIAN\r\n")
    for (ev in events) {
        val (gy, gm, gd) = repo.jdToGregorian(ev.jd)
        val startDate = "${pad4(gy)}${pad2(gm)}${pad2(gd)}"
        val (ny, nm, nd) = repo.jdToGregorian(ev.jd + 1)   // all-day event: DTEND is exclusive
        val endDate = "${pad4(ny)}${pad2(nm)}${pad2(nd)}"

        sb.append("BEGIN:VEVENT\r\n")
        sb.append(foldIcsLine("UID:luachitim-${ev.jd}-${ev.idx}@luachitim")).append("\r\n")
        sb.append(foldIcsLine("DTSTAMP:$stamp")).append("\r\n")
        sb.append(foldIcsLine("DTSTART;VALUE=DATE:$startDate")).append("\r\n")
        sb.append(foldIcsLine("DTEND;VALUE=DATE:$endDate")).append("\r\n")
        sb.append(foldIcsLine("SUMMARY:${icsEscapeText(ev.title)}")).append("\r\n")
        if (ev.note.isNotEmpty()) {
            sb.append(foldIcsLine("DESCRIPTION:${icsEscapeText(ev.note)}")).append("\r\n")
        }
        sb.append("END:VEVENT\r\n")
    }
    sb.append("END:VCALENDAR\r\n")
    return sb.toString()
}

data class ParsedIcsEvent(val year: Int, val month: Int, val day: Int, val title: String, val note: String)

/**
 * Parses VEVENT blocks out of an .ics document. Deliberately tolerant:
 * reads only SUMMARY/DESCRIPTION/DTSTART, and DTSTART is read as a plain
 * calendar date whether or not it carries a time/timezone component -
 * imported events always land as whole-day diary entries here, matching
 * how this app stores events.
 */
fun parseIcsEvents(icsText: String): List<ParsedIcsEvent> {
    // Unfold continuation lines: a line starting with a space/tab is a
    // continuation of the previous logical line (RFC 5545 line folding).
    val rawLines = icsText.replace("\r\n", "\n").split("\n")
    val lines = mutableListOf<String>()
    for (line in rawLines) {
        if ((line.startsWith(" ") || line.startsWith("\t")) && lines.isNotEmpty()) {
            lines[lines.size - 1] = lines.last() + line.substring(1)
        } else {
            lines.add(line)
        }
    }

    val results = mutableListOf<ParsedIcsEvent>()
    var inEvent = false
    var summary = ""
    var description = ""
    var dateStr: String? = null

    fun afterColon(line: String): String {
        val idx = line.indexOf(':')
        return if (idx >= 0) line.substring(idx + 1) else ""
    }
    fun flush() {
        val d = dateStr ?: return
        if (d.length < 8) return
        val y = d.substring(0, 4).toIntOrNull() ?: return
        val m = d.substring(4, 6).toIntOrNull() ?: return
        val day = d.substring(6, 8).toIntOrNull() ?: return
        results.add(ParsedIcsEvent(y, m, day, summary, description))
    }

    for (raw in lines) {
        val line = raw.trimEnd('\r')
        when {
            line.equals("BEGIN:VEVENT", ignoreCase = true) -> {
                inEvent = true; summary = ""; description = ""; dateStr = null
            }
            line.equals("END:VEVENT", ignoreCase = true) -> {
                if (inEvent) flush()
                inEvent = false
            }
            inEvent && line.startsWith("SUMMARY", ignoreCase = true) ->
                summary = icsUnescapeText(afterColon(line))
            inEvent && line.startsWith("DESCRIPTION", ignoreCase = true) ->
                description = icsUnescapeText(afterColon(line))
            inEvent && line.startsWith("DTSTART", ignoreCase = true) ->
                // e.g. "DTSTART;VALUE=DATE:20260315" or "DTSTART:20260315T090000Z"
                dateStr = afterColon(line).takeWhile { it.isDigit() }.take(8)
        }
    }
    return results
}
