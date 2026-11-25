package dev.unison.server

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

@Serializable class Health(val version: String, val works: Int, val ready: Int, val preparing: Int, val missing: Int, val syncing: List<SyncService.Live>)
@Serializable class WorkDetail(val work: Work, val shelf: String, val tracks: List<TrackInfo>, val chapters: List<ChapterInfo>, val units: List<UnitInfo>, val sentencesAligned: Int, val position: Position?, val speed: Speed? = null)
@Serializable class WorkRow(val work: Work, val shelf: String)
@Serializable class WorkMarks(val work: String, val title: String, val author: String, val passages: Int, val marks: List<Mark>)
@Serializable class AnchorInfo(val passage: Int, val start: Long, val end: Long, val confidence: Double)
@Serializable class Message(val message: String)
@Serializable class PairRequest(val ebookWork: String)
@Serializable class LoginRequest(val name: String, val password: String, val device: String = "", val web: Boolean = false)
@Serializable class LoginAnswer(val token: String, val user: User)
@Serializable class Me(val user: User, val devices: List<DeviceToken>)
@Serializable class NewUser(val name: String, val password: String, val role: String = "member")
@Serializable class PasswordChange(val password: String, val current: String = "")
@Serializable class ShelfChange(val state: String, val finishedAt: Long? = null)
@Serializable class RoleChange(val role: String)
@Serializable class PairingOverview(val audioOnly: List<Work>, val unpairedAudio: List<Work>, val ebookOnly: List<Work>, val suggestions: Map<String, List<String>>)
@Serializable class BackupInfo(val name: String, val size: Long, val written: Long)
@Serializable class UserOverview(val user: User, val devices: Int, val lastSeen: Long, val reading: Int, val queued: Int, val finished: Int, val listenMs: Long, val readMs: Long, val lastActive: Long)
@Serializable class NameChange(val name: String)
/** A book found outside the house, with what the house already has of it: the work when the library holds it, in which forms, and whether it is on the wanted list. */
@Serializable class BookHit(val book: MetaCandidate, val work: String?, val hasEbook: Boolean, val hasAudio: Boolean, val wanted: Boolean)
@Serializable class BookLookup(val found: List<BookHit>, val errors: List<String>)
@Serializable class RequestIn(val title: String, val author: String = "", val note: String = "", val kind: String = "both")
@Serializable class StatusChange(val status: String, val work: String? = null)
@Serializable class RequestList(val requests: List<BookRequest>)
/** A correction to a work's metadata; a field left out is untouched, and one listed in `reset` goes back to what the libraries say. */
@Serializable class MetaChange(
    val title: String? = null, val subtitle: String? = null, val authors: List<String>? = null, val narrators: List<String>? = null, val series: List<SeriesRef>? = null,
    val description: String? = null, val publishedYear: String? = null, val publisher: String? = null, val language: String? = null, val genres: List<String>? = null,
    val cover: String? = null, val reset: List<String> = emptyList(),
)
@Serializable class MetaView(val current: Work, val source: Work)
@Serializable class LookupAnswer(val candidates: List<MetaCandidate>, val errors: List<String>)
private val UserKey = AttributeKey<User>("user")
private val ApplicationCall.user: User get() = attributes[UserKey]
private val TokenKey = AttributeKey<String>("token")

/**
 * The HTTP API the app and the web client talk to. Every /api route but health and login needs the bearer
 * token; the web client's cookie (HttpOnly, SameSite=Strict, set at sign-in) stands in for it only where a
 * browser cannot send a header: covers, audio, the ebook, and the position and session beacons. Tokens are
 * never accepted in the query string, so they never reach a log.
 */
fun api(config: Config, catalog: Catalog, scanner: Scanner, sync: SyncService, users: Users, stats: Stats, lists: Lists, rescan: () -> Unit) {
    val lookup = MetaLookup()
    val links = DeviceLinks(users)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        install(ContentNegotiation) { json(Json { encodeDefaults = true; ignoreUnknownKeys = true }) }
        // Headers for every response: the page may not be framed, types are not sniffed, and scripts come from here only.
        intercept(ApplicationCallPipeline.Plugins) {
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header("X-Frame-Options", "DENY")
            call.response.header("Referrer-Policy", "no-referrer")
            call.response.header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
            call.response.header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' data: blob: https://fonts.gstatic.com; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; frame-src 'self' blob:; worker-src 'self' blob:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'")
        }
        install(PartialContent)
        install(AutoHeadResponse)
        install(CallLogging) { level = Level.INFO; filter { it.request.path().startsWith("/api") && !it.request.path().contains("/audio/") } }
        install(StatusPages) {
            exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, Message(e.message ?: "Bad request")) }
            exception<Throwable> { call, e -> call.application.environment.log.error("Request failed", e); call.respond(HttpStatusCode.InternalServerError, Message(e.message ?: e.javaClass.simpleName)) }
        }
        routing {
            // The web client: a static single-page app at the root; unknown paths fall back to it.
            // The client is a handful of files that change with every deploy; browsers must ask each time.
            staticResources("/", "web") { default("index.html"); cacheControl { listOf(CacheControl.NoCache(null)) } }
            get("/api/health") {
                // Anyone may ask whether the server is up; what it is syncing is for signed-in people.
                val works = catalog.works()
                val signedIn = bearer(call)?.let { users.authenticate(it) } != null
                call.respond(Health(Version.NAME, works.size, works.count { it.shelf == "ready" }, works.count { it.shelf == "preparing" }, works.count { it.shelf == "missing" }, if (signedIn) sync.live() else emptyList()))
            }
            route("/api") {
                intercept(ApplicationCallPipeline.Plugins) {
                    val path = call.request.path()
                    if (!path.startsWith("/api") || path == "/api/health" || path == "/api/login" || path == "/api/link/start" || path == "/api/link/claim") return@intercept
                    val presented = bearer(call) ?: if (cookieMayAuthorize(path, call.request.httpMethod)) call.request.cookies[COOKIE] else null
                    val user = presented?.let { users.authenticate(it) }
                    if (user == null) { call.respond(HttpStatusCode.Unauthorized, Message("Sign in first")); finish(); return@intercept }
                    call.attributes.put(UserKey, user)
                    call.attributes.put(TokenKey, presented)
                }
                post("/login") {
                    val req = call.receive<LoginRequest>()
                    val address = clientAddress(call); val name = req.name.trim(); val keys = listOf("ip:$address", "name:${name.lowercase()}")
                    val wait = keys.firstNotNullOfOrNull { users.throttle.blocked(it) }
                    if (wait != null) {
                        users.audit(name, "sign-in throttled", address, "$wait s left")
                        call.response.header(HttpHeaders.RetryAfter, wait.toString())
                        return@post call.respond(HttpStatusCode.TooManyRequests, Message("Too many attempts. Try again in ${if (wait >= 120) "${(wait + 59) / 60} minutes" else "$wait seconds"}."))
                    }
                    val deviceName = req.device.ifBlank { device(call) }
                    val (user, token) = users.login(name, req.password, deviceName) ?: run {
                        keys.forEach { users.throttle.fail(it) }
                        users.audit(name, "sign-in failed", address, deviceName)
                        return@post call.respond(HttpStatusCode.Unauthorized, Message("Wrong name or password"))
                    }
                    keys.forEach { users.throttle.clear(it) }
                    users.audit(user.name, "signed in", address, deviceName)
                    if (req.web) call.response.cookies.append(sessionCookie(call, token, (Users.WEB_IDLE_MS / 1000).toInt()))
                    call.respond(LoginAnswer(token, user))
                }
                // Signing in a watch by a code (DeviceLinks): the device asks for a code without a sign-in, shows it, and
                // then asks with its secret until a signed-in person has approved the code (/me/link below).
                post("/link/start") {
                    val address = clientAddress(call)
                    val started = links.start(call.receive<LinkStart>().device, address)
                        ?: return@post call.respond(HttpStatusCode.TooManyRequests, Message("Too many sign-in codes are waiting. Try again in a few minutes."))
                    call.respond(started)
                }
                post("/link/claim") {
                    val status = links.claim(call.receive<LinkClaim>().secret) ?: return@post call.respond(HttpStatusCode.Gone, Message("This code has expired. Ask for a new one."))
                    status.user?.let { users.audit(it.name, "signed in", clientAddress(call), "by code") }
                    call.respond(status)
                }
                route("/me") {
                    get { call.respond(Me(call.user, users.tokens(call.user.id))) }
                    // The code a watch is showing: who is asking (GET), and yes, sign it in as me (POST). Wrong codes are
                    // slowed like wrong passwords, under their own key.
                    route("/link/{code}") {
                        suspend fun blocked(call: ApplicationCall): Boolean {
                            val wait = users.throttle.blocked("link:${call.user.id}") ?: return false
                            call.response.header(HttpHeaders.RetryAfter, wait.toString())
                            call.respond(HttpStatusCode.TooManyRequests, Message("Too many attempts. Try again in ${if (wait >= 120) "${(wait + 59) / 60} minutes" else "$wait seconds"}."))
                            return true
                        }
                        suspend fun unknown(call: ApplicationCall) {
                            users.throttle.fail("link:${call.user.id}")
                            call.respond(HttpStatusCode.NotFound, Message("No device is waiting with that code. Check the code on the watch; a code lasts ten minutes."))
                        }
                        get {
                            if (blocked(call)) return@get
                            val info = links.info(call.parameters["code"] ?: "") ?: return@get unknown(call)
                            call.respond(info)
                        }
                        post {
                            if (blocked(call)) return@post
                            val info = links.approve(call.parameters["code"] ?: "", call.user.id) ?: return@post unknown(call)
                            users.throttle.clear("link:${call.user.id}")
                            users.audit(call.user.name, "sign-in code approved", clientAddress(call), "${info.device} at ${info.address}")
                            call.respond(info)
                        }
                    }
                    delete("/tokens/{prefix}") {
                        val prefix = (call.parameters["prefix"] ?: "").removeSuffix("…")
                        users.revoke(call.user.id, prefix)
                        users.audit(call.user.name, "device signed out", clientAddress(call), prefix)
                        // Signing this browser out also drops its cookie.
                        if (call.request.cookies[COOKIE]?.startsWith(prefix) == true) call.response.cookies.append(sessionCookie(call, "", 0))
                        call.respond(Message("Signed that device out"))
                    }
                    // Changing your own password takes the current one: a signed-in device left unlocked, or a stolen
                    // sign-in, is not enough to take the account. Wrong tries are slowed like sign-in tries, under
                    // their own key so they cannot lock the person out of signing in. (403, not 401: 401 tells a
                    // client its own sign-in has ended.)
                    post("/password") {
                        val req = call.receive<PasswordChange>(); val me = call.user; val key = "password:${me.id}"
                        users.throttle.blocked(key)?.let { wait ->
                            call.response.header(HttpHeaders.RetryAfter, wait.toString())
                            return@post call.respond(HttpStatusCode.TooManyRequests, Message("Too many attempts. Try again in ${if (wait >= 120) "${(wait + 59) / 60} minutes" else "$wait seconds"}."))
                        }
                        if (users.hasPassword(me.id)) {
                            if (req.current.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, Message("Your current password is needed too. If this app does not ask for it, update the app."))
                            if (!users.checkPassword(me.id, req.current)) {
                                users.throttle.fail(key); users.audit(me.name, "password change refused", clientAddress(call), "wrong current password")
                                return@post call.respond(HttpStatusCode.Forbidden, Message("The current password is wrong"))
                            }
                        }
                        users.setPassword(me.id, req.password); users.throttle.clear(key)
                        users.audit(me.name, "password changed", clientAddress(call)); call.respond(Message("Password set"))
                    }
                    // A browser signed in before cookies existed asks for one with the token it already holds.
                    post("/cookie") { val t = bearer(call) ?: throw IllegalArgumentException("Bearer token required"); call.response.cookies.append(sessionCookie(call, t, (Users.WEB_IDLE_MS / 1000).toInt())); call.respond(Message("Cookie set")) }
                    get("/shelf") {
                        call.respond(users.shelf(call.user.id).map { e -> e.copy(position = catalog.latestPosition(call.user.id, e.work)) })
                    }
                    put("/shelf/{work}") {
                        val w = catalog.work(call.parameters["work"] ?: "") ?: throw IllegalArgumentException("Unknown work")
                        val c = call.receive<ShelfChange>()
                        users.shelve(call.user.id, w.id, c.state, c.finishedAt)
                        // A book on the shelf has been picked up: it leaves Want to read.
                        lists.pickedUp(call.user.id, w.id)
                        call.respond(Message("Shelved"))
                    }
                    // Collections made by hand, and Want to read (always there, first). {id} is a collection's number or "want".
                    route("/collections") {
                        val known: (String) -> Boolean = { catalog.work(it) != null }
                        fun all(call: ApplicationCall): List<Collection> { val ids = catalog.allIds().toSet(); return lists.collections(call.user.id) { it in ids } }
                        get { call.respond(all(call)) }
                        post { val c = lists.createCollection(call.user.id, call.receive<NameChange>().name); call.respond(c) }
                        put("/{id}") { val c = lists.renameCollection(call.user.id, call.parameters["id"] ?: "", call.receive<NameChange>().name); call.respond(all(call).first { it.id == c.id }) }
                        delete("/{id}") { lists.deleteCollection(call.user.id, call.parameters["id"] ?: ""); call.respond(Message("Removed")) }
                        put("/{id}/works/{work}") {
                            val w = call.parameters["work"] ?: ""; require(known(w)) { "Unknown work" }
                            lists.addWork(call.user.id, call.parameters["id"] ?: "", w); call.respond(Message("Added"))
                        }
                        delete("/{id}/works/{work}") { lists.removeWork(call.user.id, call.parameters["id"] ?: "", call.parameters["work"] ?: ""); call.respond(Message("Removed")) }
                    }
                    // Series the person has finished or started a book in: how far along, and the book after the last finished one, as current (begun) or next (not yet).
                    get("/series") {
                        val finished = users.shelf(call.user.id).filter { it.state == "finished" }.associate { it.work to (it.finishedAt ?: it.added) }
                        call.respond(catalog.seriesProgress(finished, catalog.startedWorks(call.user.id) - finished.keys))
                    }
                    delete("/shelf/{work}") { users.unshelve(call.user.id, call.parameters["work"] ?: ""); call.respond(Message("Removed")) }
                    // Everything one person marked, a book at a time, most recently marked book first.
                    get("/marks") {
                        call.respond(users.allMarks(call.user.id).mapNotNull { (id, marks) -> catalog.work(id)?.let { WorkMarks(id, it.title, it.author, it.passages, marks) } }.sortedByDescending { w -> w.marks.maxOf { it.created } })
                    }
                    get("/marks/{work}") { call.respond(users.marks(call.user.id, call.parameters["work"] ?: "")) }
                    put("/marks/{work}") { users.setMark(call.user.id, call.parameters["work"] ?: "", call.receive<Mark>()); call.respond(Message("Marked")) }
                    delete("/marks/{work}/{kind}/{passage}") { users.removeMark(call.user.id, call.parameters["work"] ?: "", call.parameters["passage"]?.toIntOrNull() ?: throw IllegalArgumentException("Passage required"), call.parameters["kind"] ?: ""); call.respond(Message("Removed")) }
                    // Sessions arrive in batches from the app; stats are made from them on request.
                    post("/sessions") { val kept = stats.record(call.user.id, device(call), call.receive<List<SessionIn>>()); call.respond(Message("Recorded $kept")) }
                    get("/stats") {
                        val finished = users.shelf(call.user.id).count { it.state == "finished" }
                        call.respond(stats.report(call.user.id, finished, zone(call, stats)) { id -> catalog.work(id)?.let { it.title to it.author } })
                    }
                    // The year in review: this year, or the one asked for.
                    get("/year/{year?}") {
                        val year = call.parameters["year"]?.let { it.toIntOrNull()?.takeIf { y -> y in 2000..2200 } ?: throw IllegalArgumentException("A year, like 2026") }
                        val finishedAt = users.shelf(call.user.id).filter { it.state == "finished" }.map { it.work to (it.finishedAt ?: 0L) }
                        call.respond(stats.year(call.user.id, year, finishedAt, zone(call, stats)) { id -> catalog.work(id)?.let { it.title to it.author } })
                    }
                }
                route("/users") {
                    get { requireAdmin(call); call.respond(users.all()) }
                    get("/audit") { requireAdmin(call); call.respond(users.auditLog()) }
                    // Everyone, with what the admin page shows about them.
                    get("/backups") { requireAdmin(call); call.respond(catalog.backups(File(config.data, "backups")).map { BackupInfo(it.name, it.length(), it.lastModified()) }) }
                    post("/backup") { requireAdmin(call); val f = catalog.backup(File(config.data, "backups")); users.audit(call.user.name, "backup written", clientAddress(call), f.name); call.respond(BackupInfo(f.name, f.length(), f.lastModified())) }
                    get("/overview") {
                        requireAdmin(call)
                        call.respond(users.all().map { u ->
                            val tokens = users.tokens(u.id); val shelf = users.shelf(u.id); val (listen, read, last) = stats.totals(u.id)
                            UserOverview(u, tokens.size, tokens.maxOfOrNull { it.lastSeen } ?: 0L, shelf.count { it.state == "reading" }, shelf.count { it.state == "queued" }, shelf.count { it.state == "finished" }, listen, read, maxOf(last, tokens.maxOfOrNull { it.lastSeen } ?: 0L))
                        })
                    }
                    put("/{id}/role") { requireAdmin(call); val id = call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("User id required"); require(id != call.user.id) { "Change your own role from another admin's account" }; val r = call.receive<RoleChange>(); users.setRole(id, r.role); users.audit(call.user.name, "role changed", clientAddress(call), "${users.byId(id)?.name ?: "?"} → ${r.role}"); call.respond(Message("Role set")) }
                    // Start a person over: shelf, marks, positions and sessions gone; the account and its devices stay.
                    delete("/{id}/data") { requireAdmin(call); val id = call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("User id required"); val n = users.resetPersonal(id) + lists.resetPersonal(id) + catalog.clearPositions(id) + stats.clear(id); users.audit(call.user.name, "progress reset", clientAddress(call), "${users.byId(id)?.name ?: "?"} ($n rows)"); call.respond(Message("Reset: $n rows removed")) }
                    delete("/{id}/tokens") { requireAdmin(call); val id = call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("User id required"); val n = users.revokeAll(id); users.audit(call.user.name, "signed out everywhere", clientAddress(call), "${users.byId(id)?.name ?: "?"} ($n devices)"); call.respond(Message("Signed out of $n devices")) }
                    post { requireAdmin(call); val n = call.receive<NewUser>(); val u = users.create(n.name, n.password, n.role); users.audit(call.user.name, "member added", clientAddress(call), "${u.name} (${u.role})"); call.respond(u) }
                    delete("/{id}") { requireAdmin(call); val id = call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("User id required"); val name = users.byId(id)?.name ?: "?"; users.delete(id); users.audit(call.user.name, "member removed", clientAddress(call), name); call.respond(Message("Removed")) }
                    post("/{id}/password") { requireAdmin(call); val id = call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("User id required"); users.setPassword(id, call.receive<PasswordChange>().password); users.audit(call.user.name, "password set", clientAddress(call), users.byId(id)?.name ?: "?"); call.respond(Message("Password set")) }
                }
                // The pairing overview: audiobooks without an ebook (with the ebooks by the same author as
                // suggestions), ebooks without audio, and what the admin has already marked audio-only.
                get("/pairing") {
                    requireAdmin(call)
                    val all = catalog.works()
                    val ebookOnly = all.filter { it.hasEbook && !it.hasAudio }
                    val unpaired = all.filter { it.hasAudio && !it.hasEbook && !it.audioOnly }
                    val byAuthor = ebookOnly.groupBy { Scanner.authorKey(it.author) }
                    val suggestions = unpaired.associate { a -> a.id to (byAuthor[Scanner.authorKey(a.author)] ?: emptyList()).map { it.id } }
                    call.respond(PairingOverview(all.filter { it.audioOnly }, unpaired, ebookOnly, suggestions))
                }
                post("/scan") { requireAdmin(call); users.audit(call.user.name, "library rescan", clientAddress(call)); Thread { rescan() }.start(); call.respond(Message("Scanning")) }
                // The wanted list: books the library does not have yet. Anyone in the household asks and sees the list;
                // the person who asked or an admin takes a request back, and an admin settles one by hand.
                // Looking a book up before asking for it: what Audible and Open Library say about the words typed, and for
                // each answer whether the library already has it (and in which forms) or it is already wanted.
                get("/lookup") {
                    val words = call.request.queryParameters["q"]?.trim().orEmpty()
                    require(words.length in 2..200) { "A title to look up" }
                    val errors = ArrayList<String>()
                    val found = lookup.find(words, errors)
                    val works = catalog.works()
                    val wanted = lists.requests(call.user.id).filter { it.status == "wanted" }
                    call.respond(BookLookup(found.map { c ->
                        val here = works.filter { BookIdentity.matches(it, c.title, c.authors) }
                        val best = here.firstOrNull { it.hasEbook && it.hasAudio } ?: here.firstOrNull { it.hasEbook } ?: here.firstOrNull()
                        BookHit(c, best?.id, here.any { it.hasEbook }, here.any { it.hasAudio }, wanted.any { r -> BookIdentity.sameBook(BookIdentity.Book(r.title, listOf(r.author)), BookIdentity.Book(c.title, c.authors)) })
                    }, errors))
                }
                // A cover the lookup offered, by way of the server (only the two catalogues' image hosts are fetched from).
                get("/lookup/image") {
                    val (bytes, png) = lookup.image(call.request.queryParameters["url"] ?: throw IllegalArgumentException("An address"))
                    call.respondBytes(bytes, if (png) ContentType.Image.PNG else ContentType.Image.JPEG)
                }
                route("/requests") {
                    get { call.respond(RequestList(lists.requests(call.user.id))) }
                    post {
                        val r = call.receive<RequestIn>()
                        val made = lists.addRequest(call.user.id, call.user.name, r.title, r.author, r.note, r.kind, if (device(call).startsWith("web")) "web" else "app") ?: throw IllegalArgumentException("Already asked for")
                        // The library may already have it: then it has arrived before anyone went looking.
                        lists.settle(catalog.works())
                        call.respond(lists.request(made.id, call.user.id)!!)
                    }
                    put("/{id}") {
                        requireAdmin(call)
                        val id = call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Request id required")
                        val c = call.receive<StatusChange>()
                        if (c.work != null) require(catalog.work(c.work) != null) { "Unknown work" }
                        lists.setRequestStatus(id, c.status, c.work)
                        val now = lists.request(id, call.user.id)!!
                        users.audit(call.user.name, "request ${c.status}", clientAddress(call), now.title)
                        call.respond(now)
                    }
                    delete("/{id}") {
                        val id = call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Request id required")
                        val r = lists.request(id, call.user.id) ?: throw IllegalArgumentException("Unknown request")
                        require(r.mine || call.user.role == "admin") { "Only the person who asked or an admin can remove it" }
                        lists.deleteRequest(id)
                        call.respond(Message("Removed"))
                    }
                }
                get("/works") {
                    // ?q= searches title, subtitle, authors, narrators and series; ?shelf= filters ready|preparing|missing.
                    val q = call.request.queryParameters["q"].orEmpty()
                    val shelf = call.request.queryParameters["shelf"]
                    call.respond(catalog.search(q).filter { shelf == null || it.shelf == shelf }.map { WorkRow(it, it.shelf) })
                }
                get("/series") { call.respond(catalog.series()) }
                get("/series/{name}") { call.respond(catalog.seriesWorks(call.parameters["name"] ?: "").map { WorkRow(it, it.shelf) }) }
                get("/authors") { call.respond(catalog.people("author")) }
                get("/authors/{name}") { call.respond(catalog.personWorks("author", call.parameters["name"] ?: "").map { WorkRow(it, it.shelf) }) }
                get("/narrators") { call.respond(catalog.people("narrator")) }
                get("/narrators/{name}") { call.respond(catalog.personWorks("narrator", call.parameters["name"] ?: "").map { WorkRow(it, it.shelf) }) }
                route("/works/{id}") {
                    get {
                        val w = work(call, catalog)
                        call.respond(WorkDetail(w, w.shelf, catalog.tracks(w.id), catalog.chapters(w.id), catalog.units(w.id), catalog.anchorCount(w.id), catalog.latestPosition(call.user.id, w.id), catalog.speed(call.user.id, w.id)))
                    }
                    // The metadata overlay: an admin's corrections kept in Unison's catalogue, over what Calibre and the audiobook folders say.
                    get("/meta") { requireAdmin(call); val w = work(call, catalog); call.respond(MetaView(w, catalog.sourceWork(w.id)!!)) }
                    put("/meta") {
                        requireAdmin(call)
                        val w = work(call, catalog); val c = call.receive<MetaChange>()
                        fun clean(s: String, max: Int) = s.trim().also { require(it.length <= max && it.none { ch -> ch == '\u001f' || ch == '\u001e' }) { "That is too long" } }
                        fun names(l: List<String>) = l.map { clean(it, 120) }.filter { it.isNotBlank() }.distinct().also { require(it.size <= 30) { "That is too many" } }.joinToString(Catalog.SEP)
                        val changes = LinkedHashMap<String, String?>()
                        c.title?.let { changes["title"] = clean(it, 300) }; c.subtitle?.let { changes["subtitle"] = clean(it, 300) }
                        c.description?.let { changes["description"] = clean(it, 20000) }; c.publishedYear?.let { y -> changes["publishedYear"] = clean(y, 4).also { require(it.isEmpty() || (it.length == 4 && it.all(Char::isDigit))) { "A year, like 1999" } } }
                        c.publisher?.let { changes["publisher"] = clean(it, 200) }; c.language?.let { changes["language"] = clean(it, 60) }
                        c.authors?.let { changes["authors"] = names(it) }; c.narrators?.let { changes["narrators"] = names(it) }; c.genres?.let { changes["genres"] = names(it) }
                        c.series?.let { s -> changes["series"] = Catalog.encodeSeries(s.map { SeriesRef(clean(it.name, 200), it.sequence?.also { n -> require(n in 0.0..9999.0) { "A series number between 0 and 9999" } }) }.filter { it.name.isNotBlank() }.distinctBy { it.name.lowercase() }) }
                        // A cover offered by a lookup is fetched into the server's own data folder; the libraries are not touched.
                        c.cover?.takeIf { it.isNotBlank() }?.let { url -> changes["cover"] = lookup.fetchCover(url, File(config.data, "works/${w.id}/cover-override.jpg")).path }
                        for (f in c.reset) { require(f in Catalog.FIELDS) { "Unknown field $f" }; changes[f] = null }
                        val now = catalog.setOverrides(w.id, changes)
                        users.audit(call.user.name, "metadata edited", clientAddress(call), "${now.title}: ${changes.keys.joinToString(", ")}")
                        call.respond(MetaView(now, catalog.sourceWork(w.id)!!))
                    }
                    // What Audible and Open Library say about this book (or about ?title= and ?author=), for the admin to pick from.
                    get("/meta/lookup") {
                        requireAdmin(call)
                        val w = work(call, catalog); val errors = ArrayList<String>()
                        val found = lookup.search(call.request.queryParameters["title"]?.takeIf { it.isNotBlank() } ?: w.title, call.request.queryParameters["author"] ?: w.author, errors)
                        call.respond(LookupAnswer(found, errors))
                    }
                    get("/meta/image") {
                        requireAdmin(call)
                        val (bytes, png) = lookup.image(call.request.queryParameters["url"] ?: throw IllegalArgumentException("An address"))
                        call.respondBytes(bytes, if (png) ContentType.Image.PNG else ContentType.Image.JPEG)
                    }
                    get("/passages") {
                        val w = work(call, catalog)
                        val stored = scanner.storedBook(w.id) ?: return@get call.respond(HttpStatusCode.NotFound, Message("No parsed ebook for this work"))
                        call.respond(stored)
                    }
                    get("/alignment") { call.respond(catalog.anchors(work(call, catalog).id).map { AnchorInfo(it.passage, it.start, it.end, it.confidence) }) }
                    get("/ebook") {
                        val w = work(call, catalog)
                        val file = w.ebookPath?.let(::File)?.takeIf { it.isFile } ?: return@get call.respond(HttpStatusCode.NotFound, Message("No ebook"))
                        serve(call, file, if (w.ebookFormat == "epub") ContentType.parse("application/epub+zip") else ContentType.Application.OctetStream)
                    }
                    get("/cover") {
                        val file = catalog.coverPath(work(call, catalog).id)?.let(::File)?.takeIf { it.isFile } ?: return@get call.respond(HttpStatusCode.NotFound, Message("No cover"))
                        serve(call, file, if (file.extension.lowercase() == "png") ContentType.Image.PNG else ContentType.Image.JPEG)
                    }
                    get("/audio/{n}") {
                        val w = work(call, catalog)
                        val n = call.parameters["n"]?.toIntOrNull() ?: throw IllegalArgumentException("Track number required")
                        val track = catalog.tracks(w.id).getOrNull(n) ?: return@get call.respond(HttpStatusCode.NotFound, Message("No such track"))
                        val file = File(track.path).takeIf { it.isFile } ?: return@get call.respond(HttpStatusCode.NotFound, Message("Track file missing"))
                        serve(call, file, audioType(file))
                    }
                    // The newest position from any of the person's devices, with that device's name; each device keeps its own row.
                    get("/position") { val w = work(call, catalog); call.respond(catalog.latestPosition(call.user.id, w.id) ?: Position(0, 0)) }
                    // PUT from the clients; POST too, because a browser's page-hide beacon can only POST.
                    val savePosition: suspend (ApplicationCall) -> Unit = { c ->
                        val w = work(c, catalog)
                        val p = c.receive<Position>()
                        require(p.passage >= 0 && p.audio >= 0) { "Position must not be negative" }
                        // `base`: the server time of the place the client last had. Another device moved the book since: refused,
                        // with that newer place, so the client can ask the person rather than overwrite it.
                        val base = c.request.queryParameters["base"]?.toLongOrNull()
                        val newer = base?.let { b -> catalog.latestElsewhere(c.user.id, w.id, device(c))?.takeIf { it.updated > b } }
                        if (newer != null) c.respond(HttpStatusCode.Conflict, newer)
                        else {
                            catalog.setPosition(c.user.id, w.id, device(c), p, users.deviceName(c.attributes[TokenKey]).orEmpty())
                            c.respond(catalog.position(c.user.id, w.id, device(c))!!)
                        }
                    }
                    get("/speed") { val w = work(call, catalog); call.respond(catalog.speed(call.user.id, w.id) ?: Speed(1f, 0)) }
                    put("/speed") { val w = work(call, catalog); call.respond(catalog.setSpeed(call.user.id, w.id, call.receive<Speed>().speed)) }
                    put("/position") { savePosition(call) }
                    post("/position") { savePosition(call) }
                    post("/sync") {
                        val w = work(call, catalog)
                        if (call.request.queryParameters["restart"] == "true") sync.restart(w.id)
                        else if (!sync.request(w.id)) return@post call.respond(HttpStatusCode.Conflict, Message("This work needs both an ebook and an audiobook before it can sync"))
                        call.respond(Message("Syncing"))
                    }
                    delete("/sync") { sync.cancel(work(call, catalog).id); call.respond(Message("Pausing")) }
                    // Audio-only: this audiobook has no ebook to wait for; it is ready to listen to as it is.
                    put("/audio-only") {
                        requireAdmin(call)
                        val w = work(call, catalog); val keys = catalog.keysOf(w.id) ?: throw IllegalArgumentException("Unknown work")
                        catalog.setAudioOnly(keys.first, keys.second, true); users.audit(call.user.name, "marked audio-only", clientAddress(call), w.title)
                        call.respond(Message("Audio only"))
                    }
                    delete("/audio-only") {
                        requireAdmin(call)
                        val w = work(call, catalog); val keys = catalog.keysOf(w.id) ?: throw IllegalArgumentException("Unknown work")
                        catalog.setAudioOnly(keys.first, keys.second, false); users.audit(call.user.name, "audio-only cleared", clientAddress(call), w.title)
                        call.respond(Message("Waiting for an ebook again"))
                    }
                    delete("/pair") {
                        requireAdmin(call)
                        val w = work(call, catalog); val keys = catalog.keysOf(w.id) ?: throw IllegalArgumentException("Unknown work")
                        catalog.clearPairing(keys.first, keys.second); users.audit(call.user.name, "pairing cleared", clientAddress(call), w.title)
                        Thread { rescan() }.start()
                        call.respond(Message("Pairing removed; rescanning"))
                    }
                    put("/pair") {
                        requireAdmin(call)
                        // Make this work's audiobook belong to another work's ebook: an override by title and author keys.
                        val w = work(call, catalog)
                        val target = catalog.work(call.receive<PairRequest>().ebookWork) ?: throw IllegalArgumentException("Unknown ebook work")
                        val path = target.ebookPath ?: throw IllegalArgumentException("That work has no ebook")
                        catalog.setPairing(Scanner.titleKey(w.title), Scanner.authorKey(w.author), path)
                        users.audit(call.user.name, "paired", clientAddress(call), "${w.title} → ${target.title}")
                        Thread { rescan() }.start()
                        call.respond(Message("Paired; rescanning"))
                    }
                }
            }
        }
    }.start(wait = true)
}

private fun requireAdmin(call: ApplicationCall) { if (call.user.role != "admin") throw IllegalArgumentException("Only an admin can do that") }

private const val COOKIE = "unison"
private fun bearer(call: ApplicationCall) = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }
/** The cookie signs for what a browser fetches without headers: media and the ebook by GET, and the two page-hide beacons. */
private fun cookieMayAuthorize(path: String, method: HttpMethod) =
    (method == HttpMethod.Get && Regex("^/api/works/[^/]+/(cover|ebook|audio/\\d+)$").matches(path)) ||
        (method == HttpMethod.Post && (Regex("^/api/works/[^/]+/position$").matches(path) || path == "/api/me/sessions"))
private fun sessionCookie(call: ApplicationCall, token: String, maxAge: Int): Cookie {
    val https = call.request.header("X-Forwarded-Proto")?.equals("https", true) == true || call.request.origin.scheme == "https"
    return Cookie(COOKIE, token, maxAge = maxAge, path = "/api", secure = https, httpOnly = true, extensions = mapOf("SameSite" to "Strict"))
}
/** The caller's address: the proxy's forwarded header when the request comes from a private network (the proxy), else the socket's. */
private fun clientAddress(call: ApplicationCall): String {
    val remote = call.request.origin.remoteAddress
    val private = remote == "127.0.0.1" || remote == "::1" || remote.startsWith("10.") || remote.startsWith("192.168.") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(remote)
    val forwarded = call.request.header("X-Forwarded-For")?.split(',')?.firstOrNull()?.trim()
    return if (private && !forwarded.isNullOrBlank()) forwarded else remote
}

private fun work(call: ApplicationCall, catalog: Catalog): Work =
    catalog.work(call.parameters["id"] ?: throw IllegalArgumentException("Work id required")) ?: throw IllegalArgumentException("Unknown work")

/** The asker's time zone (`X-Timezone`, an IANA name such as Europe/Warsaw), for days and hours in the statistics; the server's when absent or unknown. */
private fun zone(call: ApplicationCall, stats: Stats): java.time.ZoneId = call.request.header("X-Timezone")?.takeIf { it.isNotBlank() && it.length <= 64 }?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: stats.defaultZone
private fun device(call: ApplicationCall) = call.request.header("X-Device")?.takeIf { it.isNotBlank() } ?: call.request.queryParameters["device"] ?: "default"

private suspend fun serve(call: ApplicationCall, file: File, type: ContentType) {
    call.response.header(HttpHeaders.ETag, "\"${file.lastModified()}-${file.length()}\"")
    call.response.header(HttpHeaders.CacheControl, "private, max-age=0, must-revalidate")
    call.respond(LocalFileContent(file, type))
}

private fun audioType(file: File) = when (file.extension.lowercase()) {
    "m4b", "m4a", "aac" -> ContentType.parse("audio/mp4")
    "mp3" -> ContentType.parse("audio/mpeg")
    "flac" -> ContentType.parse("audio/flac")
    "ogg", "opus" -> ContentType.parse("audio/ogg")
    "wav" -> ContentType.parse("audio/wav")
    else -> ContentType.Application.OctetStream
}
