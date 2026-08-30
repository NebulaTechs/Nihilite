package nihilite.hooks;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

/** Shared Clojure var resolutions and helpers for ByteBuddy Advice classes. */
final class AdviceSupport {

    private static final IFn LOOKUP_SPEC =
            Clojure.var("nihilite.registry", "lookup-spec-for-call");

    private AdviceSupport() {}

    static String hostInternal(Class<?> hostClass) {
        return hostClass == null ? "?" : hostClass.getName().replace('.', '/');
    }

    static Object lookupSpec(String hostInternal, String methodName, int argCount,
                             String descriptor, String phase) {
        return LOOKUP_SPEC.invoke(hostInternal, methodName, argCount, descriptor, phase);
    }
}