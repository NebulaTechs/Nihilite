package nihilite.agent;

import clojure.java.api.Clojure;
import clojure.lang.Compiler;
import clojure.lang.DynamicClassLoader;
import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.Symbol;
import nihilite.hooks.HookInstaller;
import nihilite.server.ServerConstants;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Javaagent entry point. Loads the init file (if any), arms
 *  HookInstaller on the supplied Instrumentation, and spawns the
 *  non-daemon worker that boots the Clojure runtime + nREPL
 *  dispatcher on the attached JVM. */
public final class Agent {

    private static final Logger LOG = Logger.getLogger("nihilite.agent.Agent");

    private static final AtomicReference<Instrumentation> REGISTERED_ON =
            new AtomicReference<>();

    private static final String PREMAIN_REGISTERED_MSG =
            "[Nihilite-agent] premain armed HookInstaller (ByteBuddy AgentBuilder)";

    private static final String AGENTMAIN_REGISTERED_MSG =
            "[Nihilite-agent] agentmain armed HookInstaller (dynamic attach)";

    private static final String WORKER_BOUND_MSG =
            "[Nihilite-agent] worker bound port ";

    private Agent() {}

    public static void premain(String args, Instrumentation inst) {
        long t0 = System.nanoTime();

        String initPath = System.getProperty(ServerConstants.INIT_PROPERTY, "");
        if (!initPath.isEmpty()) {
            try {
                IFn loadFile = Clojure.var("clojure.core", "load-file");
                loadFile.invoke(initPath);
            } catch (Throwable t) {
                LOG.log(Level.WARNING,
                        "[Nihilite-agent] init load failed: " + initPath + " (" + t + ")",
                        t);
            }
        }

        if (inst != null && REGISTERED_ON.compareAndSet(null, inst)) {
            HookInstaller.install(inst);
            LOG.info(PREMAIN_REGISTERED_MSG);
        }

        Thread worker = new Thread(new AgentWorker(), "nihilite-agent-worker");
        worker.setDaemon(false);
        worker.setContextClassLoader(ClassLoader.getSystemClassLoader());
        worker.start();

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        LOG.info("[Nihilite-agent] premain returned in " + elapsedMs
                 + " ms; worker=" + worker.getName());
    }

    /** Dynamic-attach entry point (Attach API). Mirrors premain but
     *  emits an `agentmain`-tagged log; re-running is a no-op (the
     *  `REGISTERED_ON` cell is already set). */
    public static void agentmain(String args, Instrumentation inst) {
        long t0 = System.nanoTime();
        if (inst != null && REGISTERED_ON.compareAndSet(null, inst)) {
            HookInstaller.install(inst);
            LOG.info(AGENTMAIN_REGISTERED_MSG);
        } else if (inst != null) {
            LOG.info("[Nihilite-agent] agentmain no-op (HookInstaller already "
                     + "registered for " + REGISTERED_ON.get() + ")");
        }
        Thread worker = new Thread(new AgentWorker(), "nihilite-agent-worker");
        worker.setDaemon(false);
        worker.setContextClassLoader(ClassLoader.getSystemClassLoader());
        worker.start();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        LOG.info("[Nihilite-agent] agentmain returned in " + elapsedMs
                 + " ms; worker=" + worker.getName());
    }

    private static final class AgentWorker implements Runnable {

        private static final int DEFAULT_PORT = ServerConstants.DEFAULT_PORT;
        private static final String DEFAULT_BIND = ServerConstants.DEFAULT_HOST;

        @Override
        public void run() {
            try {
                initClojure();
                waitForRuntimeViaAdapter();
                bindCompilerLoader();
                int port = bootNrepl();
                LOG.info(WORKER_BOUND_MSG + port);
            } catch (Throwable t) {
                LOG.log(Level.SEVERE, "[Nihilite-agent] worker failed", t);
            }
        }

        private static void initClojure() {
            // Clojure.var interns but doesn't load the namespace — always
            // (require ...) first so each var actually resolves at runtime.
            IFn require = Clojure.var("clojure.core", "require");
            require.invoke(Symbol.intern("clojure.core"));
            require.invoke(Symbol.intern("nihilite.transport"));
            require.invoke(Symbol.intern("nihilite.boot"));
            require.invoke(Symbol.intern("nihilite.registry"));
            require.invoke(Symbol.intern("nihilite.adapter"));
            require.invoke(Symbol.intern("nihilite.hooks"));

            try {
                IFn installRedisp = Clojure.var(
                        "nihilite.registry", "install-redefine-dispatcher!");
                Object result = installRedisp.invoke();
                LOG.info("[Nihilite-agent] redefine dispatcher installed: " + result);
            } catch (Throwable t) {
                LOG.log(Level.WARNING,
                        "[Nihilite-agent] install-redefine-dispatcher! failed; "
                                + ":redefine specs will throw at first call",
                        t);
            }
        }

        private static void waitForRuntimeViaAdapter() {
            try {
                IFn defaultAdapter = Clojure.var("nihilite.adapter", "default-adapter");
                Object adapter = defaultAdapter.invoke();
                if (adapter == null) {
                    LOG.info("[Nihilite-agent] no default-adapter installed; runtime-sentinel skipped");
                    return;
                }
                IFn waitFn = Clojure.var("nihilite.adapter", "wait-until-runtime-ready!");
                waitFn.invoke(adapter);
            } catch (Throwable t) {
                LOG.log(Level.WARNING,
                        "[Nihilite-agent] runtime-sentinel wait failed (soft); continuing",
                        t);
            }
        }

        private static void bindCompilerLoader() {
            ClassLoader hostCl = ClassLoader.getSystemClassLoader();
            DynamicClassLoader loader = new DynamicClassLoader(hostCl);
            Compiler.LOADER.bindRoot(loader);
            LOG.info("[Nihilite-agent] Compiler/LOADER bound to "
                     + loader.getClass().getName() + " wrapping " + hostCl);
        }

        private static int bootNrepl() {
            int port = parsePort(System.getProperty("nihilite.port", null));
            String bind = System.getProperty("nihilite.bind", DEFAULT_BIND);
            String mapLiteral = "{:port " + port + " :bind \"" + bind + "\"}";
            IPersistentMap startArgs = (IPersistentMap) Clojure.read(mapLiteral);
            IFn startFn = Clojure.var("nihilite.boot", "start!");
            startFn.invoke(startArgs);
            return port;
        }

        private static int parsePort(String rawValue) {
            if (rawValue == null || rawValue.isEmpty()) return DEFAULT_PORT;
            try {
                int p = Integer.parseInt(rawValue);
                if (p < 1 || p > 65535) {
                    LOG.warning("[Nihilite-agent] nihilite.port out of range (" + rawValue
                                + "), using default " + DEFAULT_PORT);
                    return DEFAULT_PORT;
                }
                return p;
            } catch (NumberFormatException nfe) {
                LOG.warning("[Nihilite-agent] nihilite.port unparseable (\""
                            + rawValue + "\"), using default " + DEFAULT_PORT);
                return DEFAULT_PORT;
            }
        }
    }
}
