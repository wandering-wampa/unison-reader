// Exercises ServerClient.resume (the watch's download: a dropped connection costs only what had not arrived) against
// the local server (Start-LocalServer.ps1), from the JVM. Run from the project folder after :server:installDist:
//   jshell --class-path "server\build\install\server\lib\*" server\local\check_resume.jsh
// Prints PASS or the first thing that was wrong.
import dev.homescreenreader.ServerClient;
import java.io.*;
import java.nio.file.*;
import java.util.*;

String base = "http://localhost:8099";
Map<String, String> creds = new HashMap<>();
for (String line : Files.readAllLines(Paths.get("build/local-server/credentials.txt"))) { String[] p = line.trim().split("\\s+"); if (p.length == 2) creds.put(p[0], p[1]); }
String token = new ServerClient(base, "", "resume-check").login("reader", creds.get("reader"), "check").getFirst();
ServerClient client = new ServerClient(base, token, "resume-check");
String work = null;
for (ServerClient.Work w : client.works("Tin Lighthouse", null)) if (w.getHasAudio()) work = w.getId();
if (work == null) { System.out.println("FAIL: the fixture audiobook The Tin Lighthouse is not on the server"); System.exit(1); }
String path = "/api/works/" + work + "/audio/1";
File dir = Files.createTempDirectory("unison-resume").toFile();
int[] checks = {0};
void ok(boolean cond, String what) { checks[0]++; if (!cond) { System.out.println("FAIL: " + what); System.exit(1); } }
kotlin.jvm.functions.Function0<Boolean> never = () -> false;
kotlin.jvm.functions.Function2<Long, Long, kotlin.Unit> quiet = (a, b) -> kotlin.Unit.INSTANCE;

// The whole file, the plain way: what every other fetch must equal.
File whole = new File(dir, "whole");
client.download(path, whole, quiet);
byte[] want = Files.readAllBytes(whole.toPath());
ok(want.length > 1_000_000, "the track is a file of some size");

// From nothing.
File a = new File(dir, "a");
ok(client.resume(path, a, never, quiet) && Arrays.equals(Files.readAllBytes(a.toPath()), want), "a fresh fetch is the whole file");
ok(!new File(a.getPath() + ".part").exists() && !new File(a.getPath() + ".etag").exists(), "nothing is left beside it");

// Stopped part-way: the part stays, and the next try asks only for the rest.
File b = new File(dir, "b");
int[] blocks = {0};
kotlin.jvm.functions.Function0<Boolean> afterFive = () -> blocks[0]++ >= 5;
ok(!client.resume(path, b, afterFive, quiet), "a stopped fetch says so");
File part = new File(b.getPath() + ".part");
long had = part.length();
ok(!b.exists() && had > 0 && had < want.length && new File(b.getPath() + ".etag").exists(), "the part and the file's tag stay for the next try (" + had + " bytes)");
long[] first = {-1};
kotlin.jvm.functions.Function2<Long, Long, kotlin.Unit> watch = (done, total) -> { if (first[0] < 0) first[0] = done; ok(total == want.length, "the total is the whole file's size while resuming"); return kotlin.Unit.INSTANCE; };
ok(client.resume(path, b, never, watch) && Arrays.equals(Files.readAllBytes(b.toPath()), want), "the resumed fetch is the whole file, byte for byte");
ok(first[0] > had, "the resumed fetch went on from the part (first progress " + first[0] + " after " + had + ")");

// The file changed on the server meanwhile (its tag differs): the part is of something else, and the fetch starts over.
File c = new File(dir, "c");
Files.write(new File(c.getPath() + ".part").toPath(), new byte[300_000]);
Files.writeString(new File(c.getPath() + ".etag").toPath(), "\"an-older-file\"");
ok(client.resume(path, c, never, quiet) && Arrays.equals(Files.readAllBytes(c.toPath()), want), "a part of an older file is thrown away, not glued on");

// A part with no tag beside it cannot be trusted either.
File d = new File(dir, "d");
Files.write(new File(d.getPath() + ".part").toPath(), new byte[300_000]);
ok(client.resume(path, d, never, quiet) && Arrays.equals(Files.readAllBytes(d.toPath()), want), "a part without its tag starts over");

// A part longer than the file (the file was replaced by a shorter one).
File e = new File(dir, "e");
Files.write(new File(e.getPath() + ".part").toPath(), new byte[want.length + 5000]);
Files.writeString(new File(e.getPath() + ".etag").toPath(), "\"whatever\"");
ok(client.resume(path, e, never, quiet) && Arrays.equals(Files.readAllBytes(e.toPath()), want), "a part longer than the file starts over");

for (File f : dir.listFiles()) f.delete();
dir.delete();
System.out.println("PASS (" + checks[0] + " checks)");
/exit
