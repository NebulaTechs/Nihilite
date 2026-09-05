package nihilite.agent;

import nihilite.hooks.HookInstaller;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Agent {

    private static final Logger LOG = Logger.getLogger("nihilite.agent.Agent");

    private static final AtomicReference<Instrumentation> REGISTERED_ON =
            new AtomicReference<>();

    private static final AtomicBoolean SYSTEM_SEARCH_EXTENDED = new AtomicBoolean(false);

    private static volatile CountDownLatch WORKER_READY = new CountDownLatch(1);

    public static Instrumentation currentInstrumentation() {
        return REGISTERED_ON.get();
    }

    private static final AtomicBoolean WORKER_STARTED = new AtomicBoolean(false);

    private Agent() {}

    private static void extendSystemClassLoaderSearch(Instrumentation inst) {
        if (inst == null || !SYSTEM_SEARCH_EXTENDED.compareAndSet(false, true)) return;
        try {
            URL where = Agent.class.getProtectionDomain().getCodeSource().getLocation();
            if (where == null || !"file".equalsIgnoreCase(where.getProtocol())) return;
            File jar = new File(URI.create(where.toString()));
            if (!jar.isFile()) return;
            try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar)) {
                inst.appendToSystemClassLoaderSearch(jarFile);
            }
            LOG.info("[Nihilite-agent] appended " + jar + " to system classloader search");
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[Nihilite-agent] appendToSystemClassLoaderSearch failed", t);
        }
    }

    public static void premain(String args, Instrumentation inst) {
        long t0 = System.nanoTime();

        extendSystemClassLoaderSearch(inst);

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
        extendSystemClassLoaderSearch(inst);
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

    public static void awaitWorkerReady() {
        try {
            WORKER_READY.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    static void signalWorkerReady() {
        WORKER_READY.countDown();
    }
}
