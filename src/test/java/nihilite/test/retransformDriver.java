package nihilite.test;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import net.bytebuddy.agent.ByteBuddyAgent;

import java.lang.instrument.Instrumentation;


public final class retransformDriver {

    public static volatile int ENTERED = 0;
    public static volatile int RETURN_MUTATED = 0;
    public static volatile int REDEFINED = 0;

    private retransformDriver() {}

    public static void main(String[] args) throws Exception {
        Instrumentation inst = ByteBuddyAgent.install();

        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("nihilite.registry"));
        require.invoke(Clojure.read("nihilite.api"));

        nihilite.hooks.HookInstaller.install(inst);

        IFn installHook = Clojure.var("nihilite.registry", "install!");
        IFn swapBridge = Clojure.var("nihilite.api", "swap-bridge!");
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
            @Override public Object invoke(Object self, Object args, Object methodName) {
                REDEFINED++;
                return "REDEFINED-BY-DRIVER";
            }
        };

IFn entryCancelHandler = new clojure.lang.AFn() {
            @Override public Object invoke(Object ctx) {
                clojure.lang.IFn cancel =
                        (clojure.lang.IFn) clojure.java.api.Clojure.var(
                                "nihilite.registry", "ctx-cancel!");
                cancel.invoke(ctx, true);
                return null;
            }
        };

IFn throwHandler = new clojure.lang.AFn() {
            @Override public Object invoke(Object ctx) {
                try {
                    java.lang.reflect.Field f =
                            DummyTarget.class.getDeclaredField("THROW_OBSERVED");
                    int cur = f.getInt(null);
                    f.setInt(null, cur + 1);
                } catch (Throwable ignored) {}
                return null;
            }
        };

        clearHook.invoke();

        installHook.invoke(hashMap.invoke(
                Clojure.read(":id"),            "driver-entry",
                Clojure.read(":target-internal"), "nihilite/test/retransformDriver$DummyTarget",
                Clojure.read(":method-name"),    "probe",
                Clojure.read(":position"),       Clojure.read(":entry"),
                Clojure.read(":arity"),          1,
                Clojure.read(":descriptor"),     "(I)Ljava/lang/String;",
                Clojure.read(":bridge"),         entryHandler,
                Clojure.read(":note"),           "driver :entry"));

        installHook.invoke(hashMap.invoke(
                Clojure.read(":id"),            "driver-return",
                Clojure.read(":target-internal"), "nihilite/test/retransformDriver$DummyTarget",
                Clojure.read(":method-name"),    "probeReturn",
                Clojure.read(":position"),       Clojure.read(":return"),
                Clojure.read(":arity"),          0,
                Clojure.read(":descriptor"),     "()Ljava/lang/String;",
                Clojure.read(":action"),         Clojure.read(":modify"),
                Clojure.read(":bridge"),         returnHandler,
                Clojure.read(":note"),           "driver :return"));

        installHook.invoke(hashMap.invoke(
                Clojure.read(":id"),            "driver-redefine",
                Clojure.read(":target-internal"), "nihilite/test/retransformDriver$DummyTarget",
                Clojure.read(":method-name"),    "probeRedef",
                Clojure.read(":position"),       Clojure.read(":redefine"),
                Clojure.read(":arity"),          0,
                Clojure.read(":descriptor"),     "()Ljava/lang/String;",
                Clojure.read(":bridge"),         redefineHandler,
                Clojure.read(":note"),           "driver :redefine"));

        installHook.invoke(hashMap.invoke(
                Clojure.read(":id"),            "driver-entry-cancel",
                Clojure.read(":target-internal"), "nihilite/test/retransformDriver$DummyTarget",
                Clojure.read(":method-name"),    "probeCancel",
                Clojure.read(":position"),       Clojure.read(":entry"),
                Clojure.read(":arity"),          0,
                Clojure.read(":descriptor"),     "()Ljava/lang/String;",
                Clojure.read(":action"),         Clojure.read(":cancel"),
                Clojure.read(":bridge"),         entryCancelHandler,
                Clojure.read(":note"),           "driver :entry :cancel"));

        installHook.invoke(hashMap.invoke(
                Clojure.read(":id"),            "driver-throw",
                Clojure.read(":target-internal"), "nihilite/test/retransformDriver$DummyTarget",
                Clojure.read(":method-name"),    "probeThrow",
                Clojure.read(":position"),       Clojure.read(":throw"),
                Clojure.read(":arity"),          0,
                Clojure.read(":descriptor"),     "()Ljava/lang/String;",
                Clojure.read(":bridge"),         throwHandler,
                Clojure.read(":note"),           "driver :throw"));

        ClassLoader cl = retransformDriver.class.getClassLoader();
        Class<?> target = cl.loadClass("nihilite.test.retransformDriver$DummyTarget");
        java.lang.reflect.Method probe = target.getDeclaredMethod("probe", int.class);
        java.lang.reflect.Method probeReturn = target.getDeclaredMethod("probeReturn");
        java.lang.reflect.Method probeRedef = target.getDeclaredMethod("probeRedef");
        java.lang.reflect.Method probeCancel = target.getDeclaredMethod("probeCancel");
        java.lang.reflect.Method probeThrow = target.getDeclaredMethod("probeThrow");

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

        // probeCancel(): :entry :cancel must short-circuit host body;
        // caller receives HookCancelledException, BODY_EXECUTED_AFTER_CANCEL stays false.
        try {
            probeCancel.invoke(null);
            System.err.println("retransformDriver FAIL: probeCancel returned normally; "
                    + "expected HookCancelledException");
            System.exit(12);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (!(cause instanceof nihilite.hooks.HookCancelledException)) {
                System.err.println("retransformDriver FAIL: probeCancel cause was "
                        + (cause == null ? "null" : cause.getClass().getName())
                        + " expected HookCancelledException");
                System.exit(13);
            }
        }
        if (DummyTarget.BODY_EXECUTED_AFTER_CANCEL) {
            System.err.println(
                    "retransformDriver FAIL: probeCancel host body executed; short-circuit broken");
            System.exit(14);
        }

        // probeThrow(): host body throws IllegalStateException, :throw advice observes it
        // and the original exception is re-thrown to the caller.
        try {
            probeThrow.invoke(null);
            System.err.println("retransformDriver FAIL: probeThrow returned normally; expected throw");
            System.exit(15);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (!(cause instanceof IllegalStateException)
                    || !"driver-probe-throw".equals(cause.getMessage())) {
                System.err.println("retransformDriver FAIL: probeThrow cause was "
                        + (cause == null ? "null"
                                : cause.getClass().getName() + ":" + cause.getMessage())
                        + " expected IllegalStateException:driver-probe-throw");
                System.exit(16);
            }
        }
        if (DummyTarget.THROW_OBSERVED != 1) {
            System.err.println("retransformDriver FAIL: THROW_OBSERVED="
                    + DummyTarget.THROW_OBSERVED + " expected 1");
            System.exit(17);
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

        int enteredBeforeSwap = ENTERED;
        IFn swappedHandler = new clojure.lang.AFn() {
            @Override public Object invoke(Object ctx) {
                ENTERED++;
                return null;
            }
        };
        swapBridge.invoke(Clojure.read("\"driver-entry\""), swappedHandler);
        probe.invoke(null, 3);
        if (ENTERED != enteredBeforeSwap + 1) {
            System.err.println("retransformDriver FAIL: ENTERED after swap-bridge = "
                    + ENTERED + " expected " + (enteredBeforeSwap + 1));
            System.exit(24);
        }
        // also verify the swap-target's bridge is now the swappedHandler
        // (i.e. the old entryHandler closure is no longer reachable via the spec).
        IFn lookup = Clojure.var("nihilite.registry", "lookup");
        Object lookedUpSpec = lookup.invoke(Clojure.read("\"driver-entry\""));
        IFn currentBridge = (IFn) clojure.java.api.Clojure.var(
                "clojure.core", "get").invoke(lookedUpSpec, Clojure.read(":bridge"));
        if (!clojure.lang.Util.identical(currentBridge, swappedHandler)) {
            System.err.println("retransformDriver FAIL: post-swap bridge is not swappedHandler");
            System.exit(25);
        }

        // re-trigger cancel + throw after retransform
        try {
            probeCancel.invoke(null);
            System.err.println("retransformDriver FAIL: post-retransform probeCancel returned normally");
            System.exit(18);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (!(ite.getCause() instanceof nihilite.hooks.HookCancelledException)) {
                System.err.println("retransformDriver FAIL: post-retransform cancel cause was "
                        + (ite.getCause() == null ? "null" : ite.getCause().getClass().getName()));
                System.exit(19);
            }
        }
        try {
            probeThrow.invoke(null);
            System.err.println("retransformDriver FAIL: post-retransform probeThrow returned normally");
            System.exit(20);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (!(ite.getCause() instanceof IllegalStateException)) {
                System.err.println("retransformDriver FAIL: post-retransform throw cause was "
                        + (ite.getCause() == null ? "null" : ite.getCause().getClass().getName()));
                System.exit(21);
            }
        }
        if (DummyTarget.THROW_OBSERVED != 2) {
            System.err.println("retransformDriver FAIL: post-retransform THROW_OBSERVED="
                    + DummyTarget.THROW_OBSERVED + " expected 2");
            System.exit(22);
        }
        if (DummyTarget.BODY_EXECUTED_AFTER_CANCEL) {
            System.err.println(
                    "retransformDriver FAIL: post-retransform probeCancel body still executed");
            System.exit(23);
        }

        clearHook.invoke();
        if (!listIds.invoke().toString().equals("()")) {
            System.err.println("retransformDriver FAIL: list-ids not empty after clear");
            System.exit(11);
        }

        System.out.println("DRIVER_PASS retransform + :return-mutation + :redefine-substitution + :entry-cancel + :throw-observation + swap-bridge all proven");
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
        /** :entry :cancel target. If the cancel short-circuit is honored,
         *  {@link #BODY_EXECUTED_AFTER_CANCEL} stays false and the host
         *  caller receives {@link nihilite.hooks.HookCancelledException}.
         *  If the short-circuit is broken, BODY_EXECUTED_AFTER_CANCEL
         *  becomes true and the caller sees "should-never-see". */
        public static volatile boolean BODY_EXECUTED_AFTER_CANCEL = false;
        public static String probeCancel() {
            BODY_EXECUTED_AFTER_CANCEL = true;
            return "should-never-see";
        }
        /** :throw target. The method body throws; the :throw advice must
         *  observe the throw and re-throw the original. */
        public static volatile int THROW_OBSERVED = 0;
        public static String probeThrow() {
            throw new IllegalStateException("driver-probe-throw");
        }
    }
}