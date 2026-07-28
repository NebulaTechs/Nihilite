package nihilite.hooks;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import net.bytebuddy.asm.Advice;

import java.util.logging.Level;
import java.util.logging.Logger;

/** :throw-phase Advice target. Wraps the host method body in a
 *  try/catch; on Throwable, looks up the spec and calls
 *  `nihilite.registry/dispatch-throw-for-spec` (the same
 *  Clojure.var pattern HookAdvice uses for :entry). Per D2.4
 *  (plan v2.1), the host method body's behavior is otherwise
 *  unchanged — the original throwable is re-thrown after
 *  dispatch. */
public final class ThrowAdvice {

    private static final Logger LOG = Logger.getLogger("nihilite.hooks.ThrowAdvice");

    private ThrowAdvice() {}

    private static final IFn CLJ_LOOKUP_SPEC =
            Clojure.var("nihilite.registry", "lookup-spec-for-call");

    private static final IFn CLJ_DISPATCH_THROW =
            Clojure.var("nihilite.registry", "dispatch-throw-for-spec");

    @Advice.OnMethodExit(inline = false, onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onThrow(
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#c") Class<?> hostClass,
            @Advice.This(optional = true) Object self,
            @Advice.AllArguments Object[] args,
            @Advice.Thrown Throwable thrown) {
        if (thrown == null) return;
        try {
            String hostInternal = (hostClass == null)
                    ? "?" : hostClass.getName().replace('.', '/');
            int paramCount = args == null ? 0 : args.length;
            Object specId = CLJ_LOOKUP_SPEC.invoke(hostInternal, methodName, paramCount);
            if (specId == null) return;
            CLJ_DISPATCH_THROW.invoke(specId.toString(), self, args, thrown);
        } catch (Throwable t) {
            try {
                LOG.log(Level.FINE, "throw advice dispatch failed", t);
            } catch (Throwable ignored) {
            }
        }
    }
}