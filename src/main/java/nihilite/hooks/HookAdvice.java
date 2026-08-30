package nihilite.hooks;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import net.bytebuddy.asm.Advice;

import java.util.logging.Level;
import java.util.logging.Logger;

/** :entry-phase Advice: dispatch via Clojure spec. */
public final class HookAdvice {

    private static final Logger LOG = Logger.getLogger("nihilite.hooks.HookAdvice");

    private HookAdvice() {}

    private static final IFn CLJ_DISPATCH_ENTRY =
            Clojure.var("nihilite.registry", "dispatch-for-spec");

    @Advice.OnMethodEnter(inline = false)
    public static void onEntry(
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#c") Class<?> hostClass,
            @Advice.Origin("#d") String descriptor,
            @Advice.This(optional = true) Object self,
            @Advice.AllArguments Object[] args) {
        try {
            String hostInternal = AdviceSupport.hostInternal(hostClass);
            int paramCount = args == null ? 0 : args.length;
            Object specId = AdviceSupport.lookupSpec(
                    hostInternal, methodName, paramCount, descriptor, "entry");
            if (specId == null) return;
            CLJ_DISPATCH_ENTRY.invoke(specId, self, args);
        } catch (Throwable t) {
            String hostName = (hostClass == null) ? "?" : hostClass.getName();
            try {
                LOG.log(Level.SEVERE,
                        "advice onEntry failed on " + hostName + "." + methodName, t);
            } catch (Throwable ignored) {
            }
        }
    }
}