package nihilite.hooks;

import clojure.lang.IFn;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;

/** :redefine-phase MethodDelegation target. Clojure fn replaces method body at invocation. */
public final class GenericDispatcher {

    private GenericDispatcher() {}

    @RuntimeType
    public static Object dispatch(
            @Origin Class<?> hostClass,
            @Origin String methodSig,
            @AllArguments Object[] args) {
        String hostInternal = hostClass.getName().replace('.', '/');
        String methodName = extractMethodName(methodSig);
        String descriptor = extractDescriptor(methodSig);
        Object dispatcher = Bridge.REDISPATCHER;
        if (dispatcher == null) {
            throw new IllegalStateException(
                    "GenericDispatcher: worker not booted (REDISPATCHER null)");
        }
        return ((IFn) dispatcher).invoke(hostInternal, methodName, args, descriptor);
    }

    private static String extractMethodName(String sig) {
        int paren = sig.indexOf('(');
        int lastDot = sig.lastIndexOf('.', paren < 0 ? sig.length() : paren);
        return paren < 0
                ? sig.substring(lastDot + 1)
                : sig.substring(lastDot + 1, paren);
    }

    private static String extractDescriptor(String sig) {
        int paren = sig.indexOf('(');
        if (paren < 0) return null;
        return sig.substring(paren);
    }
}
