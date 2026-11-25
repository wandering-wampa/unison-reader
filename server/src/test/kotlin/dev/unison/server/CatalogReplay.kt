package dev.unison.server

import java.io.File

/** Offline scan of a disposable catalog copy, with library mounts read-only. Never starts HTTP, sync or notifications. */
fun main(args: Array<String>) {
    require(args.size == 3) { "Usage: CatalogReplayKt <disposable-data-dir> <ebooks> <audiobooks>" }
    val data = File(args[0])
    require(File(data, ".disposable-replay").isFile) { "A disposable replay marker is required" }
    val catalog = Catalog(File(data, "catalog.db"))
    try {
        val scanner = Scanner(Config(File(args[2]), File(args[1]), data, File(data, "unused-model"), "", 0, 0), catalog)
        val before = catalog.works()
        val changed = scanner.scan()
        val after = catalog.works()
        val settled = Lists(catalog.connection).settle(after)
        println("REPLAY works=${before.size}->${after.size} changed=$changed paired=${after.count { it.hasAudio && it.hasEbook }} arrived=${settled.map { it.id }}")
        check(before.map { it.id }.toSet() == after.map { it.id }.toSet()) { "Work IDs changed; inspect before deploying" }
        check(before.filter { it.hasAudio && it.hasEbook }.all { old -> after.any { it.id == old.id && it.ebookPath == old.ebookPath && it.audioDir == old.audioDir } }) { "An existing pair changed" }
    } finally { catalog.connection.close() }
}
