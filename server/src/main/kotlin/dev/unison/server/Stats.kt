package dev.unison.server

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable data class SessionIn(val work: String, val kind: String, val started: Long, val ended: Long, val sentences: Int = 0, val pages: Int = 0)
@Serializable data class DayStat(val date: String, val listenMs: Long, val readMs: Long)
@Serializable data class BookStat(val work: String, val title: String, val author: String, val listenMs: Long, val readMs: Long, val sentences: Int)
@Serializable data class Totals(val listenMs: Long, val readMs: Long, val sessions: Int, val sentences: Int, val pages: Int)
@Serializable data class StatsReport(
    val today: Totals, val week: Totals, val month: Totals, val year: Totals, val all: Totals,
    val streak: Int, val longestStreak: Int, val daysActive: Int, val finished: Int,
    val days: List<DayStat>, val hours: List<Long>, val books: List<BookStat>,
)

@Serializable data class MonthStat(val month: Int, val listenMs: Long, val readMs: Long)
@Serializable data class FinishedBook(val work: String, val title: String, val author: String, val finishedAt: Long)
/** One calendar year of a person's listening and reading. `days` has every day of the year; `history` only the days with any time, over every year. */
@Serializable data class YearReport(
    val year: Int, val years: List<Int>,
    val listenMs: Long, val readMs: Long, val sessions: Int, val sentences: Int, val pages: Int,
    val daysActive: Int, val longestStreak: Int, val busiestDay: DayStat?, val topHour: Int, val topAuthor: String,
    val months: List<MonthStat>, val days: List<DayStat>, val books: List<BookStat>, val finished: List<FinishedBook>, val history: List<DayStat>,
)

/**
 * Listening and reading sessions, as the app reports them, and the numbers made from them. A session
 * shorter than three seconds is noise and is dropped, and so is one longer than a day or ending more than ten minutes
 * after the server's clock. Days and hours are counted in the time zone of whoever asks
 * (clients send theirs as `X-Timezone`); the server's own zone is the fallback for clients that send none.
 */
class Stats(private val db: Connection, val defaultZone: ZoneId = ZoneId.systemDefault(), private val clock: () -> Long = System::currentTimeMillis) {
    private companion object {
        const val MAX_SESSION_MS = 24L * 60 * 60 * 1000
        const val CLOCK_SLACK_MS = 10L * 60 * 1000
    }

    init {
        synchronized(db) {
            db.createStatement().use { s ->
                s.execute("CREATE TABLE IF NOT EXISTS sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, user INTEGER NOT NULL, work TEXT NOT NULL, kind TEXT NOT NULL, device TEXT NOT NULL DEFAULT '', started INTEGER NOT NULL, ended INTEGER NOT NULL, sentences INTEGER NOT NULL DEFAULT 0, pages INTEGER NOT NULL DEFAULT 0)")
                s.execute("CREATE INDEX IF NOT EXISTS sessions_user_time ON sessions (user, started)")
            }
        }
    }

    private fun <T> query(sql: String, vararg args: Any?, read: (ResultSet) -> T): List<T> = synchronized(db) {
        db.prepareStatement(sql).use { p -> args.forEachIndexed { i, a -> p.setObject(i + 1, a) }; p.executeQuery().use { r -> buildList { while (r.next()) add(read(r)) } } }
    }
    private fun exec(sql: String, vararg args: Any?) = synchronized(db) { db.prepareStatement(sql).use { p -> args.forEachIndexed { i, a -> p.setObject(i + 1, a) }; p.executeUpdate() } }

    /** Listening and reading time in total, and the last moment anything was recorded, for one person. */
    fun totals(user: Long): Triple<Long, Long, Long> {
        val rows = query("SELECT kind, SUM(ended - started), MAX(ended) FROM sessions WHERE user = ? GROUP BY kind", user) { Triple(it.getString(1), it.getLong(2), it.getLong(3)) }
        return Triple(rows.firstOrNull { it.first == "listen" }?.second ?: 0L, rows.firstOrNull { it.first == "read" }?.second ?: 0L, rows.maxOfOrNull { it.third } ?: 0L)
    }

    fun clear(user: Long): Int = exec("DELETE FROM sessions WHERE user = ?", user)

    /** Stores a batch; returns how many were kept. */
    fun record(user: Long, device: String, sessions: List<SessionIn>): Int {
        var kept = 0
        for (s in sessions) {
            if (s.kind != "listen" && s.kind != "read") continue
            if (s.ended - s.started < 3000) continue
            // Nobody listens for more than a day at a stretch, and a session cannot end in the future (a little slack for clocks).
            if (s.ended - s.started > MAX_SESSION_MS || s.ended > clock() + CLOCK_SLACK_MS) continue
            // The same session sent twice (a retry) is stored once.
            if (query("SELECT 1 FROM sessions WHERE user = ? AND work = ? AND kind = ? AND started = ?", user, s.work, s.kind, s.started) { 1 }.isNotEmpty()) continue
            exec("INSERT INTO sessions (user, work, kind, device, started, ended, sentences, pages) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", user, s.work, s.kind, device.take(60), s.started, s.ended, s.sentences, s.pages)
            kept++
        }
        return kept
    }

    private class Row(val work: String, val kind: String, val started: Long, val ended: Long, val sentences: Int, val pages: Int) {
        val ms get() = ended - started
        fun date(zone: ZoneId): LocalDate = Instant.ofEpochMilli(started).atZone(zone).toLocalDate()
    }

    /** The year in review: `year` null means this year; `finishedAt` is every finished work with the moment it was finished. */
    fun year(user: Long, year: Int?, finishedAt: List<Pair<String, Long>>, zone: ZoneId = defaultZone, titles: (Set<String>) -> Map<String, Pair<String, String>>): YearReport {
        val all = query("SELECT work, kind, started, ended, sentences, pages FROM sessions WHERE user = ? ORDER BY started", user) { Row(it.getString(1), it.getString(2), it.getLong(3), it.getLong(4), it.getInt(5), it.getInt(6)) }
        val y = year ?: LocalDate.now(zone).year
        fun day(d: LocalDate, sel: List<Row>) = DayStat(d.toString(), sel.filter { it.kind == "listen" }.sumOf { it.ms }, sel.filter { it.kind == "read" }.sumOf { it.ms })
        val byDay = all.groupBy { it.date(zone) }
        val rows = all.filter { it.date(zone).year == y }
        val inYear = byDay.filterKeys { it.year == y }
        val first = LocalDate.of(y, 1, 1)
        val days = (0 until first.lengthOfYear()).map { first.plusDays(it.toLong()) }.map { day(it, inYear[it] ?: emptyList()) }
        var longest = 0; var run = 0; var prev: LocalDate? = null
        for (d in inYear.keys.sorted()) { run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1; if (run > longest) longest = run; prev = d }
        val hours = LongArray(24); for (r in rows) hours[Instant.ofEpochMilli(r.started).atZone(zone).hour] += r.ms
        val finishedInYear = finishedAt.filter { it.second > 0 && Instant.ofEpochMilli(it.second).atZone(zone).year == y }.sortedBy { it.second }
        val named = titles(rows.map { it.work }.toSet() + finishedInYear.map { it.first })
        val books = rows.groupBy { it.work }.map { (work, sel) ->
            val (title, author) = named[work] ?: ("A book no longer on the server" to "")
            BookStat(work, title, author, sel.filter { it.kind == "listen" }.sumOf { it.ms }, sel.filter { it.kind == "read" }.sumOf { it.ms }, sel.sumOf { it.sentences })
        }.sortedByDescending { it.listenMs + it.readMs }
        val finished = finishedInYear.mapNotNull { (work, at) -> named[work]?.let { FinishedBook(work, it.first, it.second, at) } }
        val years = (all.map { it.date(zone).year } + finishedAt.filter { it.second > 0 }.map { Instant.ofEpochMilli(it.second).atZone(zone).year } + LocalDate.now(zone).year + y).distinct().sortedDescending()
        return YearReport(
            year = y, years = years,
            listenMs = rows.filter { it.kind == "listen" }.sumOf { it.ms }, readMs = rows.filter { it.kind == "read" }.sumOf { it.ms },
            sessions = rows.size, sentences = rows.sumOf { it.sentences }, pages = rows.sumOf { it.pages },
            daysActive = inYear.size, longestStreak = longest,
            busiestDay = days.maxByOrNull { it.listenMs + it.readMs }?.takeIf { it.listenMs + it.readMs > 0 },
            topHour = if (rows.isEmpty()) -1 else hours.indices.maxByOrNull { hours[it] } ?: -1,
            topAuthor = books.filter { it.author.isNotBlank() }.groupBy { it.author }.maxByOrNull { (_, b) -> b.sumOf { it.listenMs + it.readMs } }?.key ?: "",
            months = (1..12).map { m -> val sel = rows.filter { it.date(zone).monthValue == m }; MonthStat(m, sel.filter { it.kind == "listen" }.sumOf { it.ms }, sel.filter { it.kind == "read" }.sumOf { it.ms }) },
            days = days, books = books.take(12), finished = finished,
            history = byDay.keys.sorted().map { day(it, byDay.getValue(it)) },
        )
    }

    fun report(user: Long, finished: Int, zone: ZoneId = defaultZone, titles: (Set<String>) -> Map<String, Pair<String, String>>): StatsReport {
        val rows = query("SELECT work, kind, started, ended, sentences, pages FROM sessions WHERE user = ? ORDER BY started", user) { Row(it.getString(1), it.getString(2), it.getLong(3), it.getLong(4), it.getInt(5), it.getInt(6)) }
        val today = LocalDate.now(zone)
        fun totals(from: LocalDate?): Totals {
            val sel = if (from == null) rows else rows.filter { !it.date(zone).isBefore(from) }
            return Totals(sel.filter { it.kind == "listen" }.sumOf { it.ms }, sel.filter { it.kind == "read" }.sumOf { it.ms }, sel.size, sel.sumOf { it.sentences }, sel.sumOf { it.pages })
        }
        val byDay = rows.groupBy { it.date(zone) }
        val activeDays = byDay.keys.sorted()
        // Streak: consecutive days with any activity, counted back from today (or from yesterday if today is still empty).
        var streak = 0
        var cursor = if (today in byDay) today else today.minusDays(1)
        while (cursor in byDay) { streak++; cursor = cursor.minusDays(1) }
        var longest = 0; var run = 0; var prev: LocalDate? = null
        for (d in activeDays) { run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1; if (run > longest) longest = run; prev = d }
        val days = (0 until 365).map { today.minusDays(it.toLong()) }.reversed().map { d ->
            val sel = byDay[d] ?: emptyList()
            DayStat(d.toString(), sel.filter { it.kind == "listen" }.sumOf { it.ms }, sel.filter { it.kind == "read" }.sumOf { it.ms })
        }
        val hours = LongArray(24)
        for (r in rows) hours[Instant.ofEpochMilli(r.started).atZone(zone).hour] += r.ms
        // Only the twenty shown need a title; the order depends on time alone.
        val busiest = rows.groupBy { it.work }.entries.sortedByDescending { (_, sel) -> sel.sumOf { it.ms } }.take(20)
        val named = titles(busiest.map { it.key }.toSet())
        val books = busiest.map { (work, sel) ->
            val (title, author) = named[work] ?: ("A book no longer on the server" to "")
            BookStat(work, title, author, sel.filter { it.kind == "listen" }.sumOf { it.ms }, sel.filter { it.kind == "read" }.sumOf { it.ms }, sel.sumOf { it.sentences })
        }
        return StatsReport(
            today = totals(today), week = totals(today.minusDays(6)), month = totals(today.withDayOfMonth(1)), year = totals(today.withDayOfYear(1)), all = totals(null),
            streak = streak, longestStreak = longest, daysActive = activeDays.size, finished = finished,
            days = days, hours = hours.toList(), books = books,
        )
    }
}
