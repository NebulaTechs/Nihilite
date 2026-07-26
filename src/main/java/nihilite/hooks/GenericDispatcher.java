package nihilite.hooks;

import clojure.lang.IFn;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;

/** :redefine-phase MethodDelegation target. The user's Clojure fn
 *  registered against a spec replaces the entire method body of the
 *  matched host method at invocation time. See nihilite.registry
 *  for spec dispatch. */
public final class GenericDispatcher {

    private GenericDispatcher() {}

    public static Object dispatch(
            @Origin Class<?> hostClass,
            @Origin String methodSig,
            @AllArguments Object[] args) throws Exception {
        String hostInternal = hostClass.getName().replace('.', '/');
        String methodName = extractMethodName(methodSig);
        Object dispatcher = Bridge.REDISPATCHER;
        if (dispatcher == null) {
            throw new IllegalStateException(
                    "GenericDispatcher: worker not booted (REDISPATCHER null)");
        }
        return ((IFn) dispatcher).invoke(hostInternal, methodName, args);
    }

    /** Parse {@code "public static java.lang.String Foo.bar(int)"} ->
     *  {@code "bar"}. Handles constructors ({@code "<init>"}) and
     *  array types. */
    private static String extractMethodName(String sig) {
        int paren = sig.indexOf('(');
        int lastDot = sig.lastIndexOf('.', paren < 0 ? sig.length() : paren);
        return paren < 0
                ? sig.substring(lastDot + 1)
                : sig.substring(lastDot + 1, paren);
    }
}