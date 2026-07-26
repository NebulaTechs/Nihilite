package nihilite.server;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Symbol;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Standalone Clojure+nREPL server entry point. Parses CLI args,
 *  hands the configured :port/:bind to nihilite.boot/start!, loads
 *  the optional init file, and parks the main thread on a
 *  CountDownLatch until the JVM shutdown hook releases it. */
public final class ServerMain {

    private static final Logger LOG = Logger.getLogger("nihilite.server.ServerMain");

    private static volatile Object server;

    private static volatile java.util.concurrent.CountDownLatch latch;

    private ServerMain() {}

    public static void main(String[] args) {
        LOG.info("Nihilite server " + ServerConstants.runtimeVersion() + " — starting");

        applyArgs(args);
        applyDefaults();

        System.setProperty("nihilite.runtime.version",
                ServerConstants.runtimeVersion());

        try {
            IFn require = Clojure.var("clojure.core", "require");
            require.invoke(Symbol.intern("nihilite.boot"));

            IFn startFn = Clojure.var("nihilite.boot", "start!");
            IFn stopFn  = Clojure.var("nihilite.boot", "stop!");
            IFn loadInitFn = Clojure.var("nihilite.boot", "load-init!");

            int port = Integer.parseInt(System.getProperty(ServerConstants.PORT_PROPERTY));
            String bind = System.getProperty(ServerConstants.BIND_PROPERTY);

            Object startArgs = Clojure.read(
                    "{:port " + port +
                    " :bind \"" + bind + "\"}");
            clojure.lang.IPersistentMap startArgsMap =
                    (clojure.lang.IPersistentMap) startArgs;

            server = startFn.invoke(startArgsMap);

            LOG.info("canonical listener bound on " + bind + ":" + port
                    + " — clients may connect; HTTP/WS share the same port");

            loadInitFn.invoke();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("shutdown hook firing");
                try {
                    stopFn.invoke(server);
                    LOG.info("nihilite.boot/stop! returned cleanly");
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "stop! raised", t);
                }
                if (latch != null) latch.countDown();
            }, "nihilite-server-shutdown"));

            latch = new java.util.concurrent.CountDownLatch(1);
            latch.await();

        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "FATAL", t);
            System.exit(1);
        }

        LOG.info("exiting");
    }

    private static void applyArgs(String[] args) {
        if (args == null) return;
        int positional = 0;
        for (String a : args) {
            if (a == null || a.isEmpty()) continue;
            if (a.startsWith(ServerConstants.PORT_ARG_PREFIX)) {
                System.setProperty(ServerConstants.PORT_PROPERTY,
                        a.substring(ServerConstants.PORT_ARG_PREFIX.length()));
            } else if (a.startsWith(ServerConstants.BIND_ARG_PREFIX)) {
                System.setProperty(ServerConstants.BIND_PROPERTY,
                        a.substring(ServerConstants.BIND_ARG_PREFIX.length()));
            } else {
                if (positional == 0) {
                    try {
                        int p = Integer.parseInt(a);
                        if (p >= 1 && p <= 65535) {
                            System.setProperty(ServerConstants.PORT_PROPERTY, a);
                            positional = 1;
                            continue;
                        }
                    } catch (NumberFormatException ignored) {}
                    System.setProperty(ServerConstants.BIND_PROPERTY, a);
                    positional = 1;
                } else if (positional == 1) {
                    try {
                        int p = Integer.parseInt(a);
                        if (p >= 1 && p <= 65535) {
                            System.setProperty(ServerConstants.PORT_PROPERTY, a);
                            positional = 2;
                            continue;
                        }
                    } catch (NumberFormatException ignored) {}
                }
                LOG.warning("ignoring unrecognized CLI arg: " + a);
            }
        }
    }

    private static void applyDefaults() {
        if (System.getProperty(ServerConstants.PORT_PROPERTY) == null) {
            System.setProperty(ServerConstants.PORT_PROPERTY,
                    Integer.toString(ServerConstants.DEFAULT_PORT));
        }
        if (System.getProperty(ServerConstants.BIND_PROPERTY) == null) {
            System.setProperty(ServerConstants.BIND_PROPERTY, ServerConstants.DEFAULT_HOST);
        }
    }
}