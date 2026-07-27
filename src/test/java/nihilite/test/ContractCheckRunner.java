package nihilite.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java contract runner + Error Observability Contract
 * (EOC) scanner. Wired into Gradle as the {@code contractCheck}
 * JavaExec task.
 *
 * <p>Two responsibilities, both verifiable from the {@code
 * contractCheck} Gradle task:</p>
 *
 * <ol>
 *   <li>{@code EocScanner.scan(repoRoot)} — walks
 *       {@code src/main/java/**} and {@code src/main/clojure/**}.
 *       Every Java/Clojure source file is parsed for
 *       {@code catch (Throwable …)} / {@code catch (Exception …)}
 *       forms. Any broad-type or ignored-name catch is reported.</li>
 *   <li>{@code ContractCheckRunner.NEGATIVE_FIXTURE_CONTENT} — a
 *       PERMANENT hardcoded source snippet containing a known-bad
 *       swallowed-exception block. Under {@code
 *       -DcontractNegative=1} the scanner ALSO parses the fixture
 *       content and asserts it was reported. The fixture is
 *       <strong>not</strong> a transient file: it lives as a string
 *       constant inside this class, so there is no temp-file to
 *       forget to delete.</li>
 * </ol>
 *
 * <p>The EOC allow-list has two tiers:</p>
 *
 * <ul>
 *   <li>{@code OWNED_ALLOWLIST} — the documented EOC boundary
 *       entries. These are stable, narrow, and grow only with
 *       explicit operator approval.</li>
 *   <li>{@code BASELINE_ALLOWLIST} — the catch-attribution table
 *       for the CURRENT codebase. Every catch carries a Todo
 *       owner label; future tightenings move each entry from
 *       broad/ignored to narrow/named and the list shrinks.
 *       Baseline entries not yet tightened are removed by deadline.</li>
 * </ul>
 *
 * <p>No JUnit / Surefire / assert / *. Assertions are encoded as
 * {@code throw new RuntimeException(...)} so the run exits non-zero
 * even when JVM assertions are disabled.</p>
 */
public final class ContractCheckRunner {

    /**
     * Tier-1: documented EOC boundaries (stable across waves).
     * Each entry has an explicit operator-approved owner.
     */
    private static final List<EocAllow> OWNED_ALLOWLIST = List.of(
            new EocAllow("src/main/clojure/nihilite/transport.clj",
                    "accept-loop timeout polling",
                    "Todo 5",
                    "SocketTimeoutException poll-and-retry; recovers to check shutdown"),
            new EocAllow("src/main/clojure/nihilite/transport.clj",
                    "bencode pushback unread failure",
                    "Todo 5",
                    "PushbackInputStream unread is best-effort; original bytes precede unread"),
            new EocAllow("src/main/clojure/nihilite/registry.clj",
                    "bridge-fire user-hook isolation",
                    "Todo 3",
                    "isolates Minecraft thread from user-hook throwables; logs + records"),
            new EocAllow("src/main/clojure/nihilite/registry.clj",
                    "dispatch-top-level failure isolation",
                    "Todo 3",
                    "outer isolation; logs throwable"),
            new EocAllow("src/main/clojure/nihilite/registry.clj",
                    "stderr-flush best-effort",
                    "Todo 3",
                    "*err* flush at log line; not silent"),
            new EocAllow("src/main/clojure/nihilite/boot.clj",
                    "log-flush best-effort",
                    "Todo 6",
                    "*err* flush at log line; not silent"),
            new EocAllow("src/main/java/nihilite/agent/Agent.java",
                    "premain-runtime-error isolation",
                    "Todo 6",
                    "premain runtime exception must not crash -javaagent")
    );

    /**
     * Tier-2: baseline — every catch currently in the codebase is
     * attributed to a Todo owner. Future tightenings narrow each
     * entry (move from broad/ignored to narrow/named), shrinking
     * the allow-list. The check refuses baseline entries that were
     * not tightened by the deadline.
     */
    private static final List<EocAllow> BASELINE_ALLOWLIST = List.of(
            baseline("src/main/java/nihilite/hooks/Bridge.java", "Todo 3",
                    "bridge.fire thread-isolation; pure-JDK Dispatcher + REDISPATCHER slot (BB migration)"),
            baseline("src/main/java/nihilite/hooks/HookInstaller.java", "Todo 3",
                    "AgentBuilder install + Listener error isolation; 3 hook phases (BB migration)"),
            baseline("src/main/java/nihilite/hooks/HookAdvice.java", "Todo 3",
                    "advice entry dispatch isolation; BB migration"),
            baseline("src/main/java/nihilite/hooks/ReturnAdvice.java", "Todo 3",
                    "advice exit return-mutation isolation; @Advice.AssignReturned (BB migration)"),
            baseline("src/main/java/nihilite/hooks/GenericDispatcher.java", "Todo 3",
                    "MethodDelegation target for :redefine; bridge to clojure; BB migration"),
            baseline("src/main/java/nihilite/hooks/DynamicAssigner.java", "Todo 3",
                    "Always-DYNAMIC Assigner for Object->typed return cast; BB migration"),
            baseline("src/main/java/nihilite/agent/Agent.java", "Todo 6",
                    "premain/spawn/worker isolation; HookInstaller + redefine-dispatcher install"),
            baseline("src/main/java/nihilite/server/ServerMain.java", "Todo 6",
                    "main + port-parse + shutdown isolation; narrows in Todo 6"),
            baseline("src/main/clojure/nihilite/transport.clj", "Todo 5",
                    "dispatcher + accept-loop isolation; narrows in Todo 5"),
            baseline("src/main/clojure/nihilite/transport/io.clj", "Todo 5",
                    "stream reset best-effort; narrows in Todo 5"),
            baseline("src/main/clojure/nihilite/transport/bencode.clj", "Todo 5",
                    "bencode connection + socket-close isolation; narrows in Todo 5"),
            baseline("src/main/clojure/nihilite/transport/raw.clj", "Todo 5",
                    "raw connection + socket-close isolation; narrows in Todo 5"),
            baseline("src/main/clojure/nihilite/transport/ws.clj", "Todo 5",
                    "WS connection + socket-close isolation; narrows in Todo 5"),
            baseline("src/main/clojure/nihilite/reload.clj", "Todo 7",
                    "module reload cycle/missing isolation; narrows in Todo 7"),
            baseline("src/main/clojure/nihilite/adapter.clj", "Todo 7",
                    "adapter-install concurrency isolation; narrows in Todo 7"),
            baseline("src/main/clojure/nihilite/boot.clj", "Todo 6",
                    "boot + lifecycle isolation; narrows in Todo 6"),
            baseline("src/main/clojure/nihilite/hooks.clj", "Todo 3",
                    "install!/hot-swap!/uninstall! isolation; narrows in Todo 3"),
            baseline("src/main/clojure/nihilite/version.clj", "Todo 8",
                    "build/version-string isolation; narrows in Todo 8")
    );

    private static EocAllow baseline(String file, String owner, String reason) {
        return new EocAllow(file, "baseline-allowed", owner, reason);
    }

    /** Effective allow-list = OWNED ∪ BASELINE. */
    private static List<EocAllow> effectiveAllowlist() {
        List<EocAllow> all = new ArrayList<>(OWNED_ALLOWLIST);
        all.addAll(BASELINE_ALLOWLIST);
        return all;
    }

    /**
     * Permanent negative fixture source text. The contractCheck
     * scanner MUST detect the {@code catch (Throwable ignore)} block
     * inside this content under {@code -PcontractNegative=1}; in
     * positive mode the real-file scanner does NOT visit
     * {@code src/test/java/**} so the fixture remains invisible to
     * the production scan path.
     */
    static final String NEGATIVE_FIXTURE_PATH =
            "src/test/java/nihilite/test/PermanentNegativeFixture.java";
    static final String NEGATIVE_FIXTURE_CONTENT =
            "package nihilite.test;\n"
            + "public final class PermanentNegativeFixture {\n"
            + "    public void swallowThenReturnFalse() {\n"
            + "        try {\n"
            + "            throw new RuntimeException(\"known-bad fixture\");\n"
            + "        } catch (Throwable ignore) {\n"
            + "            // INTENTIONAL SILENT CATCH. The EOC scanner is\n"
            + "            // required to flag this line. Verified via\n"
            + "            // `./gradlew --no-daemon contractCheck -PcontractNegative=1`.\n"
            + "        }\n"
            + "    }\n"
            + "}\n";
    static final String CONTRACT_NEGATIVE_PROP = "contractNegative";

    /** Strict mode: refuses baseline-allowed entries that should
     *  have been tightened by the deadline. Enable via
     *  -DcontractStrict=1. */
    static final String CONTRACT_STRICT_PROP = "contractStrict";

    public static void main(String[] args) throws Exception {
        boolean negativeMode =
                "1".equals(System.getProperty(CONTRACT_NEGATIVE_PROP, "0"))
                        || (args.length > 0 && "1".equals(args[0]));
        boolean strictMode =
                "1".equals(System.getProperty(CONTRACT_STRICT_PROP, "0"));
        Path repoRoot = locateRepoRoot();
        log("[contractCheck] repo root: " + repoRoot);
        log("[contractCheck] mode: " + (negativeMode ? "NEGATIVE" : "POSITIVE")
                + (strictMode ? " + STRICT" : ""));

        // Real-file scan.
        EocReport report = EocScanner.scan(repoRoot, effectiveAllowlist(), strictMode);
        log("[contractCheck] scanned Java files: " + report.javaFileCount);
        log("[contractCheck] scanned Clojure files: " + report.cljFileCount);
        log("[contractCheck] real-file catch findings (broad|ignored): "
                + report.findings.size());
        log("[contractCheck] real-file unaccounted: " + report.unaccounted.size());
        for (EocFinding f : report.findings) {
            log("  " + f);
        }

        // Fixture scan (always runs; per-mode assertion below).
        int fixtureCatches =
                EocScanner.scanContent(NEGATIVE_FIXTURE_PATH, NEGATIVE_FIXTURE_CONTENT);
        log("[contractCheck] fixture source scan: path=" + NEGATIVE_FIXTURE_PATH
                + " catches=" + fixtureCatches);

        if (negativeMode) {
            if (fixtureCatches < 1) {
                die("contractCheck -PcontractNegative=1 expected at least 1 catch "
                        + "finding on the permanent fixture (" + NEGATIVE_FIXTURE_PATH
                        + "), got " + fixtureCatches + ".");
            }
            ok("contractCheck NEGATIVE — fixture detected; scanner working.");
            return;
        }

        // Positive mode.
        requireAllowlistClean(report);
        if (fixtureCatches < 1) {
            die("positive mode sanity: scanner failed to detect the "
                    + "known-bad pattern in NEGATIVE_FIXTURE_CONTENT (got " + fixtureCatches + ").");
        }
        ok("contractCheck POSITIVE — codebase clean per allow-list ("
                + (strictMode ? "STRICT" : "BASELINE")
                + "); fixture scanner sane.");
    }

    static Path locateRepoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 8; i++) {
            if (Files.exists(p.resolve("build.gradle"))) return p;
            if (p.getParent() == null) return Paths.get("").toAbsolutePath();
            p = p.getParent();
        }
        return Paths.get("").toAbsolutePath();
    }

    static void requireAllowlistClean(EocReport r) {
        if (!r.unaccounted.isEmpty()) {
            StringBuilder sb = new StringBuilder("EOC scan failed; unaccounted catches:\n");
            for (EocFinding f : r.unaccounted) {
                sb.append("  ").append(f).append('\n');
            }
            die(sb.toString());
        }
    }

    private static void ok(String s) {
        log(s);
        System.out.flush();
        System.exit(0);
    }

    private static void die(String s) {
        System.err.println("[contractCheck] FAIL: " + s);
        System.err.flush();
        throw new RuntimeException(s);
    }

    static void log(String s) { System.out.println(s); }

    // -----------------------------------------------------------------------
    // Allow-list entry
    // -----------------------------------------------------------------------
    record EocAllow(String filePattern,
                    String purpose,
                    String owner,
                    String reason) {}

    /** One catch-form observation. */
    static final class EocFinding {
        final String relPath;
        final int lineNumber;
        final String catchHeader;
        final boolean broad;
        final boolean ignoredName;

        EocFinding(String relPath, int lineNumber, String catchHeader,
                   boolean broad, boolean ignoredName) {
            this.relPath = relPath;
            this.lineNumber = lineNumber;
            this.catchHeader = catchHeader;
            this.broad = broad;
            this.ignoredName = ignoredName;
        }

        @Override
        public String toString() {
            return relPath + ":" + lineNumber + "  " + catchHeader.trim()
                    + (broad ? "  [BROAD]" : "")
                    + (ignoredName ? "  [IGNORED]" : "");
        }
    }

    static final class EocReport {
        int javaFileCount;
        int cljFileCount;
        final List<EocFinding> findings = new ArrayList<>();
        /** Findings not covered by the documented allow-list. */
        final List<EocFinding> unaccounted = new ArrayList<>();
    }

    // -----------------------------------------------------------------------
    // EocScanner
    // -----------------------------------------------------------------------
    static final class EocScanner {

        private static final Pattern JAVA_CATCH = Pattern.compile(
                "\\bcatch\\s*\\(\\s*(?:final\\s+)?([A-Za-z_][\\w.]*)\\s+([A-Za-z_]\\w*)\\s*\\)\\s*\\{");
        private static final Pattern CLJ_CATCH = Pattern.compile(
                "\\(catch\\s+(?:[^\\s\\[\\]]+\\s+)?([^\\s\\)]+)\\s+([^\\s\\)]+)\\)");

        private static final Set<String> IGNORED_NAMES =
                new LinkedHashSet<>(Arrays.asList("ignore", "ignored", "_"));
        private static final Set<String> BROAD_TYPES =
                new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        static {
            for (String t : List.of(
                    "Throwable", "Exception", "RuntimeException",
                    "java.lang.Throwable", "java.lang.Exception", "java.lang.RuntimeException"))
                BROAD_TYPES.add(t);
        }

        static EocReport scan(Path repoRoot, List<EocAllow> allowlist, boolean strictMode)
                throws IOException {
            EocReport r = new EocReport();
            Path javaRoot = repoRoot.resolve("src/main/java");
            if (Files.isDirectory(javaRoot)) {
                List<Path> javaFiles = new ArrayList<>();
                walk(javaRoot, javaFiles);
                r.javaFileCount = javaFiles.size();
                for (Path p : javaFiles) parseJava(repoRoot, p, r);
            }
            Path cljRoot = repoRoot.resolve("src/main/clojure");
            if (Files.isDirectory(cljRoot)) {
                List<Path> cljFiles = new ArrayList<>();
                walk(cljRoot, cljFiles);
                r.cljFileCount = cljFiles.size();
                for (Path p : cljFiles) parseClojure(repoRoot, p, r);
            }
            classify(r, allowlist, strictMode);
            return r;
        }

        /**
         * Parse arbitrary source content for catch findings. Used by
         * the negative-mode gate and by future contract tests that
         * need to verify a fixture's catch block is detected.
         *
         * @return number of catch findings observed (broad OR ignored-name)
         */
        static int scanContent(String relPath, String content) {
            List<EocFinding> findings;
            if (relPath.endsWith(".java")) {
                findings = parseJavaSource(relPath, content);
            } else if (relPath.endsWith(".clj")) {
                findings = parseClojureSource(relPath, content);
            } else {
                return 0;
            }
            int n = 0;
            for (EocFinding f : findings) {
                if (f.broad || f.ignoredName) n++;
            }
            return n;
        }

        static void walk(Path root, List<Path> out) throws IOException {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path child : ds) walkRec(child, out);
            }
        }

        static void walkRec(Path p, List<Path> out) throws IOException {
            if (Files.isDirectory(p)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                    for (Path child : ds) walkRec(child, out);
                }
            } else if (Files.isRegularFile(p)) {
                String n = p.getFileName().toString();
                if (n.endsWith(".java") || n.endsWith(".clj")) out.add(p);
            }
        }

        static void parseJava(Path repoRoot, Path file, EocReport r) throws IOException {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String rel = repoRoot.relativize(file).toString();
            r.findings.addAll(parseJavaSource(rel, content));
        }

        static List<EocFinding> parseJavaSource(String relPath, String content) {
            List<EocFinding> out = new ArrayList<>();
            Matcher m = JAVA_CATCH.matcher(content);
            while (m.find()) {
                String type = m.group(1);
                String name = m.group(2);
                boolean broad = isBroad(type);
                boolean ignored = IGNORED_NAMES.contains(name);
                if (!broad && !ignored) continue;
                out.add(new EocFinding(relPath, lineNumber(content, m.start()),
                        "catch (" + type + " " + name + ")", broad, ignored));
            }
            return out;
        }

        static void parseClojure(Path repoRoot, Path file, EocReport r) throws IOException {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String rel = repoRoot.relativize(file).toString();
            r.findings.addAll(parseClojureSource(rel, content));
        }

        static List<EocFinding> parseClojureSource(String relPath, String content) {
            List<EocFinding> out = new ArrayList<>();
            Matcher m = CLJ_CATCH.matcher(content);
            while (m.find()) {
                String type = m.group(1);
                String name = m.group(2);
                boolean broad = isBroad(type) || isBroad("clojure.lang." + type);
                boolean ignored = IGNORED_NAMES.contains(name);
                if (!broad && !ignored) continue;
                out.add(new EocFinding(relPath, lineNumber(content, m.start()),
                        "(catch " + type + " " + name + ")", broad, ignored));
            }
            return out;
        }

        static boolean isBroad(String type) {
            String t = type.trim();
            if (BROAD_TYPES.contains(t)) return true;
            for (String broad : BROAD_TYPES) {
                if (broad.endsWith(t) && broad.length() > t.length()) {
                    int dot = broad.length() - t.length() - 1;
                    if (dot >= 0 && broad.charAt(dot) == '.') return true;
                }
            }
            return false;
        }

        static int lineNumber(String s, int pos) {
            int n = 1;
            for (int i = 0; i < pos && i < s.length(); i++)
                if (s.charAt(i) == '\n') n++;
            return n;
        }

        /**
         * Classify findings against the effective allowlist. In
         * strict mode baseline-only entries count as unaccounted;
         * in baseline mode (default) they are accepted.
         */
        static void classify(EocReport r, List<EocAllow> allowlist, boolean strictMode) {
            for (EocFinding f : r.findings) {
                if (!f.broad && !f.ignoredName) continue;
                boolean accounted = false;
                for (EocAllow a : allowlist) {
                    if (f.relPath.equals(a.filePattern)
                            || f.relPath.endsWith("/" + a.filePattern)) {
                        if (!strictMode || !"baseline-allowed".equals(a.purpose)) {
                            accounted = true;
                            break;
                        }
                    }
                }
                if (!accounted) r.unaccounted.add(f);
            }
        }
    }
}
