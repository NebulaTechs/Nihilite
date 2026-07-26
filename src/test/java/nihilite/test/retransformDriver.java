package nihilite.test;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import net.bytebuddy.agent.ByteBuddyAgent;

import java.lang.instrument.Instrumentation;

/** In-process pure-JVM driver for nihilite.hooks.HookInstaller.
 *  Exercises all three hook phases on separate methods:
 *    probe       -> :entry     observe
 *    probeReturn -> :return    mutate return value
 *    probeRedef  -> :redefine  full body substitution (MethodDelegation)
 *  All three methods exist on the same class to verify that the
 *  AsmVisitorWrapper channel (:entry, :return) and the
 *  MethodRegistry channel (:redefine) compose without conflict
 *  when matchers are disjoint.
 *
 *  <p>Exits 0 with literal {@code DRIVER_PASS retransform + :return-mutation + :redefine-substitution all proven}
 *  on stdout when all three phases work.
 */
public final class retransformDriver {

    public static volatile int ENTERED = 0;
    public static volatile int RETURN_MUTATED = 0;
    public static volatile int REDEFINED = 0;

    private retransformDriver() {}

    public static void main(String[] args) throws Exception {
        Instrumentation inst = ByteBuddyAgent.install();

        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("nihilite.registry"));

        nihilite.hooks.HookInstaller.install(inst);

        IFn installHook = Clojure.var("nihilite.registry", "install!");
        IFn clearHook = Clojure.var("nihilite.registry", "clear!");
        IFn listIds = Clojure.var("nihilite.registry", "list-ids");
        IFn installRedisp = Clojure.var("nihilite.registry", "install-redefine-dispatcher!");
        installRedisp.invoke();
        IFn hashMap = Clojure.var("clojure.core", "hash-map");

        IFn entryHandler = new clojure.lang.AFn() {
            @Override public Object invoke(Object ctx) { ENTERED++; return null; }
        };

        IFn returnHandler = new clojure.lang.AFn() {
            @Override public Object invoke(Object ctx) {
                RETURN_MUTATED++;
                return "MUTATED-BY-DRIVER";
            }
        };

IFn redefineHandler = new clojure.lang.AFn() {
            @Override public Object invoke(Object args, Object methodName) {
                REDEFINED++;
                return "REDEFINED-BY-DRIVER";
            }
        };

        clearHook.invoke();

        installHook.invoke(hashMap.invoke(
                Clojure.read(":id"),            "driver-entry",
                Clojure.read(":target-internal"), "nihilite/test/retransformDriver$DummyTarget",
                Clojure.read(":method-name"),    "probe",
                Clojure.read(":position"),       Clojure.read(":entry"),
                Clojure.read(":arity"),          1,
                Clojure.read(":bridge"),         entryHandler,
                Clojure.read(":note"),           "driver :entry"));

        installHook.invoke(hashMap.invoke(
                Clojure.read(":id"),            "driver-return",
                Clojure.read(":target-internal"), "nihilite/test/retransformDriver$DummyTarget",
                Clojure.read(":method-name"),    "probeReturn",
                Clojure.read(":position"),       Clojure.read(":return"),
                Clojure.read(":arity"),          0,
                Clojure.read(":bridge"),         returnHandler,
                Clojure.read(":note"),           "driver :return"));

        installHook.invoke(hashMap.invoke(
                Clojure.read(":id"),            "driver-redefine",
                Clojure.read(":target-internal"), "nihilite/test/retransformDriver$DummyTarget",
                Clojure.read(":method-name"),    "probeRedef",
                Clojure.read(":position"),       Clojure.read(":redefine"),
                Clojure.read(":arity"),          0,
                Clojure.read(":bridge"),         redefineHandler,
                Clojure.read(":note"),           "driver :redefine"));

        ClassLoader cl = retransformDriver.class.getClassLoader();
        Class<?> target = cl.loadClass("nihilite.test.retransformDriver$DummyTarget");
        java.lang.reflect.Method probe = target.getDeclaredMethod("probe", int.class);
        java.lang.reflect.Method probeReturn = target.getDeclaredMethod("probeReturn");
        java.lang.reflect.Method probeRedef = target.getDeclaredMethod("probeRedef");

        // probe(int): :entry fires
        String result = (String) probe.invoke(null, 1);
        if (ENTERED != 1) {
            System.err.println("retransformDriver FAIL: ENTERED=" + ENTERED + " expected 1");
            System.exit(3);
        }
        if (!"original-1".equals(result)) {
            System.err.println("retransformDriver FAIL: probe result was \""
                    + result + "\" expected \"original-1\"");
            System.exit(4);
        }

        // probeReturn(): :return mutates the original "untouched-return" string
        String returnResult = (String) probeReturn.invoke(null);
        if (RETURN_MUTATED != 1) {
            System.err.println("retransformDriver FAIL: RETURN_MUTATED=" + RETURN_MUTATED + " expected 1");
            System.exit(5);
        }
        if (!"MUTATED-BY-DRIVER".equals(returnResult)) {
            System.err.println("retransformDriver FAIL: probeReturn was \""
                    + returnResult + "\" expected \"MUTATED-BY-DRIVER\"");
            System.exit(6);
        }

        // probeRedef(): original body REPLACED by clj fn returning "REDIFINED-BY-DRIVER"
        String redefResult = (String) probeRedef.invoke(null);
        if (REDEFINED != 1) {
            System.err.println("retransformDriver FAIL: REDEFINED=" + REDEFINED + " expected 1");
            System.exit(7);
        }
        if (!"REDEFINED-BY-DRIVER".equals(redefResult)) {
            System.err.println("retransformDriver FAIL: probeRedef was \""
                    + redefResult + "\" expected \"REDEFINED-BY-DRIVER\"");
            System.exit(8);
        }

        // retransform and re-fire all three
        try {
            inst.retransformClasses(target);
        } catch (Throwable t) {
            System.err.println("retransformDriver FAIL: retransformClasses threw " + t);
            System.exit(9);
        }

        probe.invoke(null, 2);
        probeReturn.invoke(null);
        probeRedef.invoke(null);

        if (ENTERED != 2 || RETURN_MUTATED != 2 || REDEFINED != 2) {
            System.err.println("retransformDriver FAIL: after retransform ENTERED="
                    + ENTERED + " RETURN_MUTATED=" + RETURN_MUTATED
                    + " REDEFINED=" + REDEFINED);
            System.exit(10);
        }

        clearHook.invoke();
        if (!listIds.invoke().toString().equals("()")) {
            System.err.println("retransformDriver FAIL: list-ids not empty after clear");
            System.exit(11);
        }

        System.out.println("DRIVER_PASS retransform + :return-mutation + :redefine-substitution all proven");
    }

    /** Sentinel target class. Each hook phase targets a different method
     *  because MethodDelegation and Advice on the same method clobber
     *  each other. */
    public static final class DummyTarget {
        public static String probe(int x) {
            return "original-" + x;
        }
        public static String probeReturn() {
            return "untouched-return";
        }
        public static String probeRedef() {
            return "SHOULD-NEVER-BE-SEEN";
        }
    }
}