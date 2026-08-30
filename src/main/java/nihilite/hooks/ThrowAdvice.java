package nihilite.hooks;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import net.bytebuddy.asm.Advice;

import java.util.logging.Logger;

/** :throw-phase Advice: dispatch throws via Clojure spec; original throwable re-thrown after dispatch. */
public final class ThrowAdvice {

    private static final Logger LOG = Logger.getLogger("nihilite.hooks.ThrowAdvice");

    private ThrowAdvice() {}

    private static final IFn CLJ_DISPATCH_THROW =
            Clojure.var("nihilite.registry", "dispatch-throw-for-spec");

    @Advice.OnMethodExit(inline = false, onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onThrow(
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#c") Class<?> hostClass,
            @Advice.Origin("#d") String descriptor,
            @Advice.This(optional = true) Object self,
            @Advice.AllArguments Object[] args,
            @Advice.Thrown Throwable thrown) {
        if (thrown == null) return;
        Object specId = null;
        try {
            String hostInternal = AdviceSupport.hostInternal(hostClass);
            int paramCount = args == null ? 0 : args.length;
            specId = AdviceSupport.lookupSpec(
                    hostInternal, methodName, paramCount, descriptor, "throw");
            if (specId == null) return;
            CLJ_DISPATCH_THROW.invoke((String) specId, self, args, thrown);
        } catch (Throwable t) {
            AdviceSupport.safeLogSevere(LOG, "throw advice dispatch failed", t);
            throw new NihiliteAdviceException(specId == null ? null : (String) specId, t);
        }
    }
}