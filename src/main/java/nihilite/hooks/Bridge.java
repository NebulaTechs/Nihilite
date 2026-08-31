package nihilite.hooks;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import java.lang.instrument.Instrumentation;

public final class Bridge {

    private Bridge() {}

    private static final IFn CLJ_REG_LOOKUP =
            Clojure.var("nihilite.registry", "lookup");

    public static volatile Object REDISPATCHER;

    public static void installRedefineDispatcher(Object d) {
        REDISPATCHER = d;
    }

    public static int uninstallSpec(String specId) {
        if (specId == null) return 0;
        Instrumentation inst = nihilite.agent.Agent.currentInstrumentation();
        if (inst == null) return 0;
        Object spec = CLJ_REG_LOOKUP.invoke(specId);
        if (spec == null) return 0;
        String targetInternal = (String) Clojure.var("clojure.core", "get")
                .invoke(spec, ":target-internal");
        if (targetInternal == null) return 0;
        return nihilite.hooks.HookInstaller.uninstall(inst, targetInternal);
    }
}
