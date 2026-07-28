package nihilite.hooks;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import net.bytebuddy.asm.Advice;

import java.util.logging.Level;
import java.util.logging.Logger;

/** :invoke-phase Advice target. Per plan v2.1 §4.5:
     - :invoke-before  fires with :args before the host method body runs
     - :invoke-return  fires with :return-value after the host method body returns
     - :invoke-throw   fires with :throwable if the host method body throws;
       the throwable is re-thrown after dispatch.

   Per D8.1 the FULL per-callsite over-approximation requires a
   ClassVisitor pass that injects advice at every INVOKE* bytecode -
   this is deferred to .omo/plans/hook-system-p2.2b.md.

For commit 6 the advice fires once per HOST METHOD (same shape as
   HookAdvice), but the dispatch path is named :invoke-* so downstream
   tests and selectors can distinguish. The per-callsite behavior
   lands in P2.2b; the dispatch contract ships now so consumers can
   wire handlers. */

public final class InvokeAdvice {

    private static final Logger LOG = Logger.getLogger("nihilite.hooks.InvokeAdvice");

    private InvokeAdvice() {}

    private static final IFn CLJ_LOOKUP_SPEC =
            Clojure.var("nihilite.registry", "lookup-spec-for-call");

    private static final IFn CLJ_DISPATCH_INVOKE =
            Clojure.var("nihilite.registry", "dispatch-invoke-for-spec");

    @Advice.OnMethodEnter(inline = false)
    public static void onInvokeBefore(
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
            CLJ_DISPATCH_INVOKE.invoke(specId.toString(), ":invoke-before",
                                       self, args, null, null);
        } catch (Throwable t) {
            try {
                LOG.log(Level.FINE, "invoke-before advice failed", t);
            } catch (Throwable ignored) {
            }
        }
    }

    @Advice.OnMethodExit(inline = false)
    public static void onInvokeReturn(
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#c") Class<?> hostClass,
            @Advice.This(optional = true) Object self,
            @Advice.AllArguments Object[] args,
            @Advice.Return(typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC) Object returnValue) {
        try {
            String hostInternal = (hostClass == null)
                    ? "?" : hostClass.getName().replace('.', '/');
            int paramCount = args == null ? 0 : args.length;
            Object specId = CLJ_LOOKUP_SPEC.invoke(hostInternal, methodName, paramCount);
            if (specId == null) return;
            CLJ_DISPATCH_INVOKE.invoke(specId.toString(), ":invoke-return",
                                       self, args, returnValue, null);
        } catch (Throwable t) {
            try {
                LOG.log(Level.FINE, "invoke-return advice failed", t);
            } catch (Throwable ignored) {
            }
        }
    }

    @Advice.OnMethodExit(inline = false, onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onInvokeThrow(
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
            CLJ_DISPATCH_INVOKE.invoke(specId.toString(), ":invoke-throw",
                                       self, args, null, thrown);
        } catch (Throwable t) {
            try {
                LOG.log(Level.FINE, "invoke-throw advice failed", t);
            } catch (Throwable ignored) {
            }
        }
    }
}