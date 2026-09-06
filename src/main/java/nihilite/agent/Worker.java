package nihilite.agent;

import clojure.java.api.Clojure;
import clojure.lang.Compiler;
import clojure.lang.DynamicClassLoader;
import clojure.lang.IFn;
import clojure.lang.Symbol;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class Worker implements Runnable {

    private static final Logger LOG = Logger.getLogger("nihilite.agent.Worker");

    @Override
    public void run() {
        try {
            initClojure();
            bindCompilerLoader();
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "[Nihilite-agent] worker failed", t);
        } finally {
            nihilite.agent.Agent.signalWorkerReady();
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

        try {
            IFn evalInit = Clojure.var("nihilite.boot", "eval-init!");
            Object initResult = evalInit.invoke();
            LOG.info("[Nihilite-agent] init form evaled: " + initResult);
        } catch (Throwable t) {
            LOG.log(Level.WARNING,
                    "[Nihilite-agent] eval-init! failed; continuing",
                    t);
        }
    }

    private static void bindCompilerLoader() {
        ClassLoader hostCl = resolveHostClassLoader();
        DynamicClassLoader loader = new DynamicClassLoader(hostCl);
        Compiler.LOADER.bindRoot(loader);
        LOG.info(String.format("[Nihilite-agent] Compiler/LOADER bound to %s wrapping %s",
                loader.getClass().getName(), hostCl));
    }

    private static ClassLoader resolveHostClassLoader() {
        String hint = System.getProperty("nihilite.compiler-loader-hint", "");
        if (!hint.isEmpty()) {
            java.lang.instrument.Instrumentation inst =
                    nihilite.agent.Agent.currentInstrumentation();
            if (inst != null) {
                String dotName = hint.replace('/', '.');
                for (Class<?> c : inst.getAllLoadedClasses()) {
                    if (c != null && dotName.equals(c.getName()) && c.getClassLoader() != null) {
                        LOG.info(String.format(
                                "[Nihilite-agent] compiler-loader hint '%s' resolved to %s",
                                hint, c.getClassLoader()));
                        return c.getClassLoader() == null ? ClassLoader.getSystemClassLoader() : c.getClassLoader();
                    }
                }
                LOG.warning(String.format(
                        "[Nihilite-agent] compiler-loader hint '%s' not found among loaded classes; "
                                + "falling back to system classloader", hint));
            } else {
                LOG.warning("[Nihilite-agent] compiler-loader hint set but no Instrumentation "
                        + "(standalone server mode); falling back to system classloader");
            }
        }
        return ClassLoader.getSystemClassLoader();
    }
}