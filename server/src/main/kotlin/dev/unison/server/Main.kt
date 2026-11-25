package dev.unison.server

import java.io.File
import java.security.SecureRandom
import org.slf4j.LoggerFactory

/** Where the libraries are, where the server keeps its own data, and how it is reached. All from the environment. */
class Config(
    val audiobooks: File,
    val ebooks: File,
    val data: File,
    val model: File,
    val token: String,
    val workers: Int,
    val port: Int,
) {
    companion object {
        fun fromEnvironment(): Config {
            fun env(name: String, default: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
            val data = File(env("UNISON_DATA", "/data")).apply { mkdirs() }
            val cores = Runtime.getRuntime().availableProcessors()
            return Config(
                audiobooks = File(env("UNISON_AUDIOBOOKS", "/audiobooks")),
                ebooks = File(env("UNISON_EBOOKS", "/ebooks")),
                data = data,
                model = File(env("UNISON_MODEL", "/app/model.onnx")),
                token = System.getenv("UNISON_TOKEN")?.takeIf { it.isNotBlank() } ?: tokenFile(data),
                workers = env("UNISON_WORKERS", (cores / 3).coerceIn(1, 4).toString()).toInt(),
                port = env("PORT", "8080").toInt(),
            )
        }

        /** A token generated once and kept beside the data, for installs that do not set UNISON_TOKEN. */
        private fun tokenFile(data: File): String {
            val file = File(data, "token")
            if (file.isFile) return file.readText().trim()
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            val token = bytes.joinToString("") { "%02x".format(it) }
            file.writeText(token)
            return token
        }
    }
}

fun main() {
    val log = LoggerFactory.getLogger("UnisonServer")
    val config = Config.fromEnvironment()
    // The phone keeps the parser's 100 MB cap; the server has the room for illustrated doorstoppers.
    dev.homescreenreader.Ebooks.maxBytes = (System.getenv("UNISON_MAX_EBOOK_MB")?.toLongOrNull() ?: 400L) shl 20
    require(config.audiobooks.isDirectory) { "Audiobook library not found at ${config.audiobooks}" }
    require(config.ebooks.isDirectory) { "Ebook library not found at ${config.ebooks}" }
    require(config.model.isFile) { "Speech model not found at ${config.model}" }
    log.info("UnisonServer ${Version.NAME}: audiobooks=${config.audiobooks} ebooks=${config.ebooks} data=${config.data} workers=${config.workers} port=${config.port}")
    val catalog = Catalog(File(config.data, "catalog.db"))
    val users = Users(catalog.connection, config.token)
    // A server set up after the first key was ended has no use for the file that start-up has just made again.
    Setup.dropKeyFile(config, users)
    val stats = Stats(catalog.connection)
    val scanner = Scanner(config, catalog)
    val sync = SyncService(config, catalog, scanner)
    val lists = Lists(catalog.connection)
    // The still-wanted requests as a file beside the database, rewritten whenever the list changes, so another
    // program on the same host can act on them without a key to this server. Written whole and moved into place: a
    // reader never sees half a file.
    val wantedFile = File(config.data, "wanted.json")
    // New requests are also said in a Discord channel when a webhook is set (see WantedPost).
    val wantedPost = runCatching { WantedPost.fromEnvironment(lists) }.onFailure { log.warn("Wanted list posting is off: ${it.message}") }.getOrNull()
    log.info("Wanted list posting: ${if (wantedPost != null) "on" else "off"}")
    lists.onRequestsChanged = {
        wantedPost?.flushSoon()
        runCatching {
            synchronized(wantedFile) {
                val tmp = File(config.data, "wanted.json.tmp")
                tmp.writeText(kotlinx.serialization.json.Json.encodeToString(WantedFile.serializer(), lists.wantedFile()))
                java.nio.file.Files.move(tmp.toPath(), wantedFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
        }.onFailure { log.warn("The wanted file was not written: ${it.message}") }
    }
    lists.onRequestsChanged?.invoke()
    // One pass over the libraries, then what follows from it: syncing what is newly paired, and settling
    // the wanted list (a requested book that turned up has arrived).
    val rescan: () -> Unit = {
        val changed = scanner.scan()
        if (changed > 0) log.info("Library scan: $changed works changed")
        sync.queueStale()
        val arrived = lists.settle(catalog.works())
        if (arrived.isNotEmpty()) log.info("Wanted list: ${arrived.size} arrived")
        // A post that failed earlier is tried again with every scan.
        wantedPost?.flushSoon()
    }
    // A scan on start and every two minutes (a book that has just landed in the libraries should not wait long to show); anything paired and unsynced is queued as it is found.
    Thread({
        while (true) {
            try {
                rescan()
            } catch (e: Exception) {
                log.error("Library scan failed", e)
            }
            Thread.sleep(2 * 60 * 1000L)
        }
    }, "scanner").apply { isDaemon = true }.start()
    // A consistent copy of the database every night at 02:30, early enough for a host's own nightly
    // backup to carry it off the machine; one at start as well.
    Thread({
        runCatching { catalog.backup(File(config.data, "backups")) }.onFailure { log.warn("Backup failed: ${it.message}") }
        while (true) {
            val now = java.time.LocalDateTime.now()
            var next = now.toLocalDate().atTime(2, 30)
            if (!next.isAfter(now)) next = next.plusDays(1)
            Thread.sleep(java.time.Duration.between(now, next).toMillis().coerceAtLeast(1000))
            runCatching { catalog.backup(File(config.data, "backups")) }.onSuccess { log.info("Backup written: ${it.name}") }.onFailure { log.warn("Backup failed: ${it.message}") }
        }
    }, "backup").apply { isDaemon = true }.start()
    api(config, catalog, scanner, sync, users, stats, lists, rescan)
}

object Version {
    // Kept in step with the app's version at each release (it had stayed at 0.1.0 through nine phases).
    const val NAME = "0.12.0"
}
