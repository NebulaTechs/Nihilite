package nihilite.hooks;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import net.bytebuddy.asm.Advice;

import java.util.logging.Level;
import java.util.logging.Logger;

/** :return-phase Advice target. Looks up the spec for the
 *  (host, method, arity) tuple and, if found, replaces the original
 *  return value with the user's Clojure fn's result. Falls back to
 *  the original value on any dispatch failure. */
public final class ReturnAdvice {

    private static final Logger LOG = Logger.getLogger("nihilite.hooks.ReturnAdvice");

    private ReturnAdvice() {}

    private static final IFn CLJ_LOOKUP_SPEC =
            Clojure.var("nihilite.registry", "lookup-spec-for-call");

    private static final IFn CLJ_DISPATCH_RETURN =
            Clojure.var("nihilite.registry", "dispatch-return-for-spec");

    @Advice.AssignReturned.ToReturned(typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC)
    @Advice.OnMethodExit(inline = false, onThrowable = Throwable.class, suppress = Throwable.class)
    public static Object onExit(
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#c") Class<?> hostClass,
            @Advice.This(optional = true) Object self,
            @Advice.AllArguments Object[] args,
            @Advice.Return(typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC) Object original) {
        try {
            String hostInternal = (hostClass == null)
                    ? "?" : hostClass.getName().replace('.', '/');
            int paramCount = args == null ? 0 : args.length;
            Object specId = CLJ_LOOKUP_SPEC.invoke(hostInternal, methodName, paramCount);
            if (specId == null) return original;
            return CLJ_DISPATCH_RETURN.invoke(specId, self, args, original);
        } catch (Throwable t) {
            String hostName = (hostClass == null) ? "?" : hostClass.getName();
            try {
                LOG.log(Level.SEVERE,
                        "advice onExit failed on " + hostName + "." + methodName, t);
            } catch (Throwable ignored) {
            }
            return original;
        }
    }
}