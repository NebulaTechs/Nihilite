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

/** Javaagent entry point. Loads init file, arms HookInstaller, spawns the worker. */
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
                        "[Nihilite-agent] init load failed: " + initPath + " (" + t + ")",
                        t);
            }
        }

        if (inst != null && REGISTERED_ON.compareAndSet(null, inst)) {
            HookInstaller.install(inst);
            LOG.info("[Nihilite-agent] premain armed HookInstaller (ByteBuddy AgentBuilder)");
        }

        startWorkerOnce();

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        LOG.info("[Nihilite-agent] premain returned in " + elapsedMs + " ms");
    }

    public static void agentmain(String args, Instrumentation inst) {
        long t0 = System.nanoTime();
        if (inst != null && REGISTERED_ON.compareAndSet(null, inst)) {
            HookInstaller.install(inst);
            LOG.info("[Nihilite-agent] agentmain armed HookInstaller (dynamic attach)");
        } else if (inst != null) {
            LOG.info("[Nihilite-agent] agentmain no-op (HookInstaller already "
                     + "registered for " + REGISTERED_ON.get() + ")");
        }
        startWorkerOnce();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        LOG.info("[Nihilite-agent] agentmain returned in " + elapsedMs + " ms");
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
