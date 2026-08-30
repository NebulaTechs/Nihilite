package nihilite.hooks;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import net.bytebuddy.asm.Advice;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class ReturnAdvice {

    private static final Logger LOG = Logger.getLogger("nihilite.hooks.ReturnAdvice");

    private ReturnAdvice() {}

    private static final IFn CLJ_DISPATCH_RETURN =
            Clojure.var("nihilite.registry", "dispatch-return-for-spec");

    @Advice.AssignReturned.ToReturned(typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC)
    @Advice.OnMethodExit(inline = false, onThrowable = Throwable.class, suppress = Throwable.class)
    public static Object onExit(
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#c") Class<?> hostClass,
            @Advice.Origin("#d") String descriptor,
            @Advice.This(optional = true) Object self,
            @Advice.AllArguments Object[] args,
            @Advice.Return(typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC) Object original) {
        Object specId = null;
        try {
            String hostInternal = AdviceSupport.hostInternal(hostClass);
            int paramCount = args == null ? 0 : args.length;
            specId = AdviceSupport.lookupSpec(
                    hostInternal, methodName, paramCount, descriptor, "return");
            if (specId == null) {
                LOG.log(Level.FINE, "ReturnAdvice: no spec for " + hostInternal + "." + methodName + " " + descriptor);
                return original;
            }
            return CLJ_DISPATCH_RETURN.invoke(specId, self, args, original);
        } catch (Throwable t) {
            AdviceSupport.safeLogSevere(LOG, "return advice dispatch failed", t);
            throw new NihiliteAdviceException(specId == null ? null : (String) specId, t);
        }
    }
}