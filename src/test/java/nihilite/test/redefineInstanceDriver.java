package nihilite.test;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import net.bytebuddy.agent.ByteBuddyAgent;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicReference;

public class redefineInstanceDriver {

    public static volatile AtomicReference<Object> CAPTURED = new AtomicReference<>();

    public int counter = 0;

    public String probe() {
        return "ORIGINAL-COUNTER=" + counter;
    }

    public static void main(String[] args) throws Exception {
        Instrumentation inst = ByteBuddyAgent.install();
        nihilite.agent.Agent.agentmain(null, inst);
        nihilite.hooks.HookInstaller.install(inst);

        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("nihilite.registry"));
        require.invoke(Clojure.read("nihilite.api"));

        IFn installHook = Clojure.var("nihilite.registry", "install!");
        IFn clearHook = Clojure.var("nihilite.registry", "clear!");

        IFn bridge = new clojure.lang.AFn() {
            @Override public Object invoke(Object self, Object args, Object methodName) {
                CAPTURED.set(self);
                return "REDEFINED-AT-ARITY0";
            }
        };

        IFn hashMap = Clojure.var("clojure.core", "hash-map");
        installHook.invoke(hashMap.invoke(
                Clojure.read(":id"),                 "inst-redef",
                Clojure.read(":target-internal"),    "nihilite/test/redefineInstanceDriver",
                Clojure.read(":method-name"),        "probe",
                Clojure.read(":position"),           Clojure.read(":redefine"),
                Clojure.read(":arity"),              0,
                Clojure.read(":descriptor"),         "()Ljava/lang/String;",
                Clojure.read(":bridge"),             bridge));

        IFn installRedisp = Clojure.var("nihilite.registry", "install-redefine-dispatcher!");
        installRedisp.invoke();

        redefineInstanceDriver instance = new redefineInstanceDriver();
        instance.counter = 99;
        String result = instance.probe();

        if (!"REDEFINED-AT-ARITY0".equals(result)) {
            System.err.println("redefineInstanceDriver FAIL: probe was \"" + result
                    + "\" expected \"REDEFINED-AT-ARITY0\"");
            System.exit(3);
        }
        Object captured = CAPTURED.get();
        if (captured == null) {
            System.err.println("redefineInstanceDriver FAIL: bridge did not run (CAPTURED null)");
            System.exit(4);
        }
        if (captured != instance) {
            System.err.println("redefineInstanceDriver FAIL: CAPTURED was " + captured
                    + " expected the calling instance " + instance);
            System.exit(5);
        }
        clearHook.invoke();
        System.out.println("DRIVER_PASS instance-method :redefine captures self via @This");
    }
}
