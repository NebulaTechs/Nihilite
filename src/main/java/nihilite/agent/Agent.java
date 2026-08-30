package nihilite.agent;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import nihilite.hooks.HookInstaller;
import nihilite.server.ServerConstants;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Agent {

    private static final Logger LOG = Logger.getLogger("nihilite.agent.Agent");

    private static final AtomicReference<Instrumentation> REGISTERED_ON =
            new AtomicReference<>();

    public static Instrumentation currentInstrumentation() {
        return REGISTERED_ON.get();
    }

    private static final AtomicBoolean WORKER_STARTED = new AtomicBoolean(false);

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
                        String.format("[Nihilite-agent] init load failed: %s (%s)",
                                initPath, t),
                        t);
            }
        }

        if (inst != null && REGISTERED_ON.compareAndSet(null, inst)) {
            HookInstaller.install(inst);
            LOG.info("[Nihilite-agent] premain armed HookInstaller (ByteBuddy AgentBuilder)");
        }

        startWorkerOnce();

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        LOG.info(String.format("[Nihilite-agent] premain returned in %d ms", elapsedMs));
    }

    public static void agentmain(String args, Instrumentation inst) {
        long t0 = System.nanoTime();
        if (inst != null && REGISTERED_ON.compareAndSet(null, inst)) {
            HookInstaller.install(inst);
            LOG.info("[Nihilite-agent] agentmain armed HookInstaller (dynamic attach)");
        } else if (inst != null) {
            LOG.info(String.format(
                    "[Nihilite-agent] agentmain no-op (HookInstaller already registered for %s)",
                    REGISTERED_ON.get()));
        }
        startWorkerOnce();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        LOG.info(String.format("[Nihilite-agent] agentmain returned in %d ms", elapsedMs));
    }

    static boolean claimWorker() {
        return WORKER_STARTED.compareAndSet(false, true);
    }

    private static void startWorkerOnce() {
        if (!claimWorker()) {
            return;
        }
        Thread worker = new Thread(new Worker(), "nihilite-agent-worker");
        worker.setDaemon(false);
        worker.setContextClassLoader(ClassLoader.getSystemClassLoader());
        worker.start();
    }
}
