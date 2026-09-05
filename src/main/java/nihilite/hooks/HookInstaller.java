package nihilite.hooks;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Installs :entry/:return/:throw/:redefine ByteBuddy hooks on matched classes.
 *  Specs arrive as raw Clojure maps; keywords resolve differently under the agent
 *  classloader so keys are matched by toString() instead of instanceof.
 *  Key discovery runs once, on the first spec only. */
public final class HookInstaller {

    private static final Logger LOG = Logger.getLogger("nihilite.hooks.HookInstaller");

    private HookInstaller() {}

    private static final IFn CLJ_REG_MATCHING =
            Clojure.var("nihilite.registry", "matching");

    public static int uninstall(Instrumentation inst, String targetInternal) {
        if (inst == null || targetInternal == null) return 0;
        String dotName = targetInternal.replace('/', '.');
        int count = 0;
        for (Class<?> loaded : inst.getAllLoadedClasses()) {
            if (loaded == null) continue;
            String name = loaded.getName();
            if (name == null || !name.equals(dotName)) continue;
            if (!inst.isModifiableClass(loaded)) continue;
            try {
                inst.retransformClasses(loaded);
                count++;
                LOG.info("HookInstaller uninstall: retransformed " + dotName);
            } catch (java.lang.instrument.UnmodifiableClassException uce) {
                LOG.log(Level.WARNING,
                        "HookInstaller uninstall: cannot retransform " + dotName
                        + " (loader=" + loaded.getClassLoader() + ")", uce);
            } catch (Throwable t) {
                LOG.log(Level.WARNING,
                        "HookInstaller uninstall: retransform failed for " + dotName
                        + " (loader=" + loaded.getClassLoader() + ")", t);
            }
        }
        return count;
    }

    public static void install(Instrumentation inst) {
        try {
            baseBuilder()
                .type(HookTypeMatcher.INSTANCE)
                .transform(HookInstaller::applyAdviceTransformer)
                .installOn(inst);
            baseBuilder()
                .type(HookTypeMatcher.INSTANCE)
                .transform(HookInstaller::applyRedefineTransformer)
                .installOn(inst);
            LOG.info("HookInstaller armed (byte-buddy AgentBuilder, RETRANSFORMATION, Reiterating)");
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "HookInstaller install failed", t);
        }
    }

    private static AgentBuilder baseBuilder() {
        return new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Reiterating.INSTANCE)
            .ignore(ElementMatchers.nameStartsWith("java.")
                    .or(ElementMatchers.nameStartsWith("javax."))
                    .or(ElementMatchers.nameStartsWith("jdk."))
                    .or(ElementMatchers.nameStartsWith("sun."))
                    .or(ElementMatchers.nameStartsWith("com.sun."))
                    .or(ElementMatchers.nameStartsWith("clojure."))
                    .or(ElementMatchers.nameStartsWith("nrepl."))
                    .or(ElementMatchers.nameStartsWith("nihilite.agent."))
                    .or(ElementMatchers.nameStartsWith("nihilite.hooks."))
                    .or(ElementMatchers.nameStartsWith("nihilite.boot."))
                    .or(ElementMatchers.nameStartsWith("nihilite.transport."))
                    .or(ElementMatchers.nameStartsWith("nihilite.registry."))
                    .or(ElementMatchers.isSynthetic()));
    }

    private record MethodKey(String name, String descriptor) {}

    private static ElementMatcher.Junction<MethodDescription> matcherFor(Set<MethodKey> keys) {
        ElementMatcher.Junction<MethodDescription> m = ElementMatchers.none();
        for (MethodKey k : keys) {
            ElementMatcher.Junction<MethodDescription> one = ElementMatchers.named(k.name);
            if (k.descriptor != null && !k.descriptor.isEmpty()) {
                one = one.and(ElementMatchers.hasDescriptor(k.descriptor));
            }
            m = m.or(one);
        }
        return m.and(ElementMatchers.not(ElementMatchers.isConstructor()))
                .and(ElementMatchers.not(ElementMatchers.isTypeInitializer()));
    }

    private static DynamicType.Builder<?> applyAdviceTransformer(
            DynamicType.Builder<?> builder,
            TypeDescription typeDescription,
            ClassLoader classLoader,
            JavaModule module,
            java.security.ProtectionDomain protectionDomain) {

        SpecBuckets buckets = collectBuckets(typeDescription);
        if (buckets == null) return builder;
        DynamicType.Builder<?> b = builder;
        if (!buckets.entryMethods.isEmpty()) {
            b = b.visit(
                    net.bytebuddy.asm.Advice.to(HookAdvice.class)
                            .on(matcherFor(buckets.entryMethods)));
        }
        if (!buckets.returnMethods.isEmpty()) {
            b = b.visit(
                    net.bytebuddy.asm.Advice
                            .withCustomMapping()
                            .with(new net.bytebuddy.asm.Advice.AssignReturned.Factory())
                            .to(ReturnAdvice.class)
                            .on(matcherFor(buckets.returnMethods)));
        }
        if (!buckets.throwMethods.isEmpty()) {
            b = b.visit(
                    net.bytebuddy.asm.Advice.to(ThrowAdvice.class)
                            .on(matcherFor(buckets.throwMethods)));
        }
        return b;
    }

    private static DynamicType.Builder<?> applyRedefineTransformer(
            DynamicType.Builder<?> builder,
            TypeDescription typeDescription,
            ClassLoader classLoader,
            JavaModule module,
            java.security.ProtectionDomain protectionDomain) {

        SpecBuckets buckets = collectBuckets(typeDescription);
        if (buckets == null || buckets.redefineMethods.isEmpty()) return builder;
        return builder.method(matcherFor(buckets.redefineMethods)).intercept(
                MethodDelegation.to(GenericDispatcher.class)
                        .withAssigner(DynamicAssigner.INSTANCE));
    }

    private static final class SpecBuckets {
        final Set<MethodKey> entryMethods    = new HashSet<>();
        final Set<MethodKey> returnMethods   = new HashSet<>();
        final Set<MethodKey> throwMethods    = new HashSet<>();
        final Set<MethodKey> redefineMethods = new HashSet<>();
    }

    private static SpecBuckets collectBuckets(TypeDescription typeDescription) {
        List<?> specs;
        try {
            String internal = typeDescription.getInternalName();
            Object raw = CLJ_REG_MATCHING.invoke(internal);
            if (!(raw instanceof List<?>)) return null;
            specs = (List<?>) raw;
            if (specs.isEmpty()) return null;
        } catch (Throwable t) {
            return null;
        }
        SpecBuckets out = new SpecBuckets();
        Object posKey = null, methodNameKey = null, descKey = null;
        for (Object s : specs) {
            Map<?, ?> sm = (Map<?, ?>) s;
            if (posKey == null) {
                for (Object k : sm.keySet()) {
                    if (k != null && k.toString().equals(":position")) {
                        posKey = k;
                        for (Object k2 : sm.keySet()) {
                            if (k2 != null && k2.toString().equals(":method-name")) {
                                methodNameKey = k2;
                            }
                            if (k2 != null && k2.toString().equals(":source-descriptor")) {
                                descKey = k2;
                            }
                        }
                        break;
                    }
                }
            }
            if (posKey == null) break;
            Object pos = sm.get(posKey);
            if (pos == null) continue;
            String p = pos.toString();
            Object mn = methodNameKey == null ? null : sm.get(methodNameKey);
            String name = mn == null ? null : mn.toString();
            if (name == null) continue;
            Object desc = descKey == null ? null : sm.get(descKey);
            String descriptor = (desc == null) ? null : desc.toString();
            MethodKey mk = new MethodKey(name, descriptor);
            if (p.equals(":entry")) out.entryMethods.add(mk);
            else if (p.equals(":return")) out.returnMethods.add(mk);
            else if (p.equals(":throw")) out.throwMethods.add(mk);
            else if (p.equals(":redefine")) out.redefineMethods.add(mk);
        }
        return out;
    }

    static final class HookTypeMatcher
            implements ElementMatcher.Junction<TypeDescription> {

        static final HookTypeMatcher INSTANCE = new HookTypeMatcher();

        private HookTypeMatcher() {}

        @Override
        public boolean matches(TypeDescription target) {
            try {
                String internal = target.getInternalName();
                Object raw = CLJ_REG_MATCHING.invoke(internal);
                if (!(raw instanceof List<?>)) return false;
                return !((List<?>) raw).isEmpty();
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public <U extends TypeDescription> Junction<U> and(
                ElementMatcher<? super U> other) {
            throw new UnsupportedOperationException(
                    "HookTypeMatcher is terminal; wrap with ElementMatchers.and() instead");
        }

        @Override
        public <U extends TypeDescription> Junction<U> or(
                ElementMatcher<? super U> other) {
            throw new UnsupportedOperationException(
                    "HookTypeMatcher is terminal; wrap with ElementMatchers.or() instead");
        }
    }
}
