package nihilite;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

public final class javaagentClasspathDriver {

    public static volatile int PASS = 0;
    public static volatile int FAIL = 0;

    private javaagentClasspathDriver() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "spawn-jar-smoke".equals(args[0])) {
            spawnJavaJarSmoke(args);
            return;
        }
        runNreplMiscProbe();
        runRequireNreplServerProbe();
        runWorkerEquivalentProbe();
        runBootThenStartServerProbe();
        if (FAIL == 0 && PASS == 4) {
            System.out.println("DRIVER_PASS javaagent classpath: 4/4 probes OK");
            System.exit(0);
        } else {
            System.err.println("DRIVER_FAIL javaagent classpath: pass=" + PASS + " fail=" + FAIL);
            System.exit(1);
        }
    }

    private static void spawnJavaJarSmoke(String[] argv) throws Exception {
        String nihiliteJar = argv[1];
        String initScript = argv.length > 2 ? argv[2] : "";
        String initForm = initScript.isEmpty()
                ? "(do (require 'clojure.repl) (in-ns 'user))"
                : "(load-file \"" + initScript.replace("\\", "\\\\").replace("\"", "\\\"") + "\")";
        String[] cmd = new String[] {
                System.getProperty("java.home") + "/bin/java",
                "-Djdk.attach.allowAttachSelf=true",
                "-XX:+EnableDynamicAgentLoading",
                "-javaagent:" + nihiliteJar,
                "-Dnihilite.init=" + initForm,
                "-Dnihilite.port=0",
                "-Dnihilite.bind=127.0.0.1",
                "-jar", nihiliteJar
            };
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        java.io.InputStream is = p.getInputStream();
        java.io.ByteArrayOutputStream pipe = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        Thread reader = new Thread(() -> {
            try {
                int n;
                while ((n = is.read(buf)) != -1) {
                    synchronized (pipe) { pipe.write(buf, 0, n); }
                }
            } catch (Throwable ignored) {}
        });
        reader.setDaemon(true);
        reader.start();

        boolean bound = false;
        boolean initDone = false;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < deadline && p.isAlive()) {
            Thread.sleep(200);
            String snap;
            synchronized (pipe) { snap = pipe.toString(); }
            if (!initDone && snap.contains("init eval done")) initDone = true;
            if (!bound && snap.contains("nREPL bencode clients may connect")) bound = true;
            if (initDone && bound) break;
        }
        p.destroyForcibly();
        try { p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        String log;
        synchronized (pipe) { log = pipe.toString(); }
        if (!bound || !initDone) {
            System.err.println("javaagentClasspathDriver: jar-smoke incomplete (bound=" + bound
                    + ", initDone=" + initDone + ")");
            System.err.println("...captured log (full):\n" + log);
            FAIL++;
            return;
        }
        System.out.println("javaagentClasspathDriver: jar-smoke (nrepl server bound + init ran) OK");
        PASS++;
    }

    private static void runNreplMiscProbe() {
        try {
            ClassLoader sys = ClassLoader.getSystemClassLoader();
            System.out.println("javaagentClasspathDriver: system loader class = " + sys.getClass().getName());
            System.out.println("javaagentClasspathDriver: system loader is URLClassLoader? "
                    + (sys instanceof java.net.URLClassLoader));
            System.out.println("javaagentClasspathDriver: nrepl/misc.clj via system = "
                    + sys.getResource("nrepl/misc.clj"));
            IFn require = Clojure.var("clojure.core", "require");
            require.invoke(Clojure.read("nrepl.misc"));
            System.out.println("javaagentClasspathDriver: require nrepl.misc OK");
            PASS++;
        } catch (Throwable t) {
            System.err.println("javaagentClasspathDriver: require nrepl.misc FAILED");
            logCauseChain(t);
            FAIL++;
        }
    }

    private static void runRequireNreplServerProbe() {
        try {
            IFn require = Clojure.var("clojure.core", "require");
            require.invoke(Clojure.read("nrepl.server"));
            System.out.println("javaagentClasspathDriver: require nrepl.server OK");
            PASS++;
        } catch (Throwable t) {
            System.err.println("javaagentClasspathDriver: require nrepl.server FAILED");
            logCauseChain(t);
            FAIL++;
        }
    }

    private static void runWorkerEquivalentProbe() throws Exception {
        IFn require = Clojure.var("clojure.core", "require");
        final Throwable[] workerError = { null };
        final boolean[] workerDone = { false };
        Thread workerThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    require.invoke(Clojure.read("nihilite.registry"));
                    require.invoke(Clojure.read("nihilite.boot"));
                } catch (Throwable t) {
                    synchronized (workerError) { workerError[0] = t; }
                } finally {
                    synchronized (workerDone) { workerDone[0] = true; workerDone.notifyAll(); }
                }
            }
        }, "driver-worker-equivalent");
        workerThread.setDaemon(true);
        workerThread.start();
        try {
            require.invoke(Clojure.read("nihilite.boot"));
        } catch (Throwable t) {
            logCauseChain(t);
        }
        synchronized (workerDone) {
            while (!workerDone[0]) workerDone.wait();
        }
        Throwable we;
        synchronized (workerError) { we = workerError[0]; }
        if (we != null) {
            System.err.println("javaagentClasspathDriver: worker-equivalent concurrent require FAILED");
            logCauseChain(we);
            FAIL++;
        } else {
            System.out.println("javaagentClasspathDriver: worker-equivalent concurrent require OK");
            PASS++;
        }
    }

    private static void logCauseChain(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            System.err.println("  " + cause.getClass().getName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
            cause = cause.getCause();
        }
    }

    private static void runBootThenStartServerProbe() {
        IFn require = Clojure.var("clojure.core", "require");
        IFn startFn = Clojure.var("nihilite.boot", "start!");
        IFn stopFn  = Clojure.var("nihilite.boot", "stop!");
        try {
            require.invoke(Clojure.read("nihilite.boot"));
            Object handle = startFn.invoke(Clojure.read("{:port 0 :bind \"127.0.0.1\"}"));
            try {
                System.out.println("javaagentClasspathDriver: nihilite.boot/start! OK");
                PASS++;
            } finally {
                try { stopFn.invoke(handle); } catch (Throwable t) { /* ignore */ }
            }
        } catch (Throwable t) {
            System.err.println("javaagentClasspathDriver: nihilite.boot/start! FAILED");
            logCauseChain(t);
            FAIL++;
        }
    }
}