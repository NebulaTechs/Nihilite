package nihilite.agent;

import clojure.java.api.Clojure;
import clojure.lang.Compiler;
import clojure.lang.DynamicClassLoader;
import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.Symbol;
import nihilite.server.ServerConstants;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class Worker implements Runnable {

    private static final Logger LOG = Logger.getLogger("nihilite.agent.Worker");

    private static final int DEFAULT_PORT = ServerConstants.DEFAULT_PORT;
    private static final String DEFAULT_BIND = ServerConstants.DEFAULT_HOST;
    private static final String WORKER_BOUND_MSG = "[Nihilite-agent] worker bound port ";

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
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Symbol.intern("clojure.core"));
        require.invoke(Symbol.intern("nihilite.transport"));
        require.invoke(Symbol.intern("nihilite.boot"));
        require.invoke(Symbol.intern("nihilite.registry"));

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
            IFn awaitFn = Clojure.var("nihilite.boot", "await-runtime-ready!");
            awaitFn.invoke();
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
        LOG.info(String.format("[Nihilite-agent] Compiler/LOADER bound to %s wrapping %s",
                loader.getClass().getName(), hostCl));
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
                LOG.warning(String.format(
                        "[Nihilite-agent] nihilite.port out of range (%s), using default %d",
                        rawValue, DEFAULT_PORT));
                return DEFAULT_PORT;
            }
            return p;
        } catch (NumberFormatException nfe) {
            LOG.warning(String.format(
                    "[Nihilite-agent] nihilite.port unparseable (\"%s\"), using default %d",
                    rawValue, DEFAULT_PORT));
            return DEFAULT_PORT;
        }
    }
}