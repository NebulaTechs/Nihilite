package nihilite.hooks;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import net.bytebuddy.asm.Advice;

import java.util.logging.Logger;

public final class HookAdvice {

    private static final Logger LOG = Logger.getLogger("nihilite.hooks.HookAdvice");

    private HookAdvice() {}

    private static final IFn CLJ_DISPATCH_ENTRY =
            Clojure.var("nihilite.registry", "dispatch-for-spec");

    private static final Keyword SHORT_CIRCUIT =
            Keyword.intern("nihilite.registry", "short-circuit");

    @Advice.OnMethodEnter(inline = false)
    public static Object onEntry(
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#c") Class<?> hostClass,
            @Advice.Origin("#d") String descriptor,
            @Advice.This(optional = true) Object self,
            @Advice.AllArguments Object[] args) {
        Object specId;
        try {
            String hostInternal = AdviceSupport.hostInternal(hostClass);
            int paramCount = args == null ? 0 : args.length;
            specId = AdviceSupport.lookupSpec(
                    hostInternal, methodName, paramCount, descriptor, "entry");
        } catch (Throwable t) {
            AdviceSupport.safeLogSevere(LOG, "entry advice lookup failed", t);
            throw new NihiliteAdviceException(null, t);
        }
        if (specId == null) return null;

        Object dispatchResult;
        try {
            dispatchResult = CLJ_DISPATCH_ENTRY.invoke(specId, self, args);
        } catch (Throwable t) {
            AdviceSupport.safeLogSevere(LOG, "entry advice dispatch failed", t);
            throw new NihiliteAdviceException((String) specId, t);
        }

        if (SHORT_CIRCUIT == dispatchResult) {
            throw new HookCancelledException();
        }
        return null;
    }
}