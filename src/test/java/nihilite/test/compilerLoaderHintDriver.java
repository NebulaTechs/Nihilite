package nihilite.test;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import net.bytebuddy.agent.ByteBuddyAgent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;


public final class compilerLoaderHintDriver {

    public static volatile int PASS = 0;
    public static volatile int FAIL = 0;

    private compilerLoaderHintDriver() {}

    public static void fail(String why) {
        System.err.println("compilerLoaderHintDriver FAIL: " + why);
        FAIL++;
    }

    public static void main(String[] args) throws Exception {
        ClassLoader appLoader = compilerLoaderHintDriver.class.getClassLoader();
        Class<?> hintClass = Class.forName(
                "nihilite.test.compilerLoaderHintDriver$HintTarget", true, appLoader);
        ClassLoader hintedLoader = hintClass.getClassLoader();

        Instrumentation inst = ByteBuddyAgent.install();
        System.out.println("ByteBuddyAgent installed: " + inst);

        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("nihilite.registry"));

        Class<?> worker = Class.forName("nihilite.agent.Worker", true, appLoader);
        Method m = worker.getDeclaredMethod("resolveHostClassLoader", new Class<?>[0]);
        m.setAccessible(true);
        java.util.function.Supplier<ClassLoader> resolve =
                () -> {
                    try {
                        return (ClassLoader) m.invoke(null);
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                };

        String hintName = "nihilite/test/compilerLoaderHintDriver$HintTarget";
        String savedHint = System.getProperty("nihilite.compiler-loader-hint");
        try {
            System.setProperty("nihilite.compiler-loader-hint", hintName);
            ClassLoader resolved = resolve.get();
            if (resolved == null) {
                fail("resolved ClassLoader was null");
            } else if (!hintedLoader.equals(resolved)) {
                fail("hint resolved to " + resolved + " expected " + hintedLoader);
            } else {
                System.out.println("compilerLoaderHintDriver: hint '" + hintName
                        + "' -> " + resolved + " (expected " + hintedLoader + ") OK");
                PASS++;
            }

            System.setProperty("nihilite.compiler-loader-hint",
                    "totally/not/a/real/Class$InThisJvm");
            ClassLoader fallback = resolve.get();
            if (fallback == null) {
                fail("fallback ClassLoader was null");
            } else if (!fallback.equals(ClassLoader.getSystemClassLoader())) {
                fail("unresolved hint did not fall back to system loader; got " + fallback);
            } else {
                System.out.println("compilerLoaderHintDriver: unresolved hint fell back to "
                        + "system loader OK");
                PASS++;
            }

            System.clearProperty("nihilite.compiler-loader-hint");
            ClassLoader noneHint = resolve.get();
            if (!noneHint.equals(ClassLoader.getSystemClassLoader())) {
                fail("no-hint path did not return system loader; got " + noneHint);
            } else {
                System.out.println("compilerLoaderHintDriver: no-hint -> system loader OK");
                PASS++;
            }

        } finally {
            if (savedHint == null) {
                System.clearProperty("nihilite.compiler-loader-hint");
            } else {
                System.setProperty("nihilite.compiler-loader-hint", savedHint);
            }
        }

        if (FAIL == 0 && PASS == 3) {
            System.out.println("DRIVER_PASS compiler-loader-hint: 3/3 paths OK");
            System.exit(0);
        } else {
            System.err.println("DRIVER_FAIL compiler-loader-hint: pass=" + PASS + " fail=" + FAIL);
            System.exit(1);
        }
    }

    public static final class HintTarget {
    }
}