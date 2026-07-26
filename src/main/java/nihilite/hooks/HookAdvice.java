package nihilite.hooks;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import net.bytebuddy.asm.Advice;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class HookAdvice {

    private static final Logger LOG = Logger.getLogger("nihilite.hooks.HookAdvice");

    private HookAdvice() {}

    private static final IFn CLJ_LOOKUP_SPEC =
            Clojure.var("nihilite.registry", "lookup-spec-for-call");

    private static final IFn CLJ_DISPATCH_ENTRY =
            Clojure.var("nihilite.registry", "dispatch-for-spec");

    @Advice.OnMethodEnter(inline = false)
    public static void onEntry(
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#c") Class<?> hostClass,
            @Advice.This(optional = true) Object self,
            @Advice.AllArguments Object[] args) {
        try {
            String hostInternal = (hostClass == null)
                    ? "?" : hostClass.getName().replace('.', '/');
            int paramCount = args == null ? 0 : args.length;
            Object specId = CLJ_LOOKUP_SPEC.invoke(hostInternal, methodName, paramCount);
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