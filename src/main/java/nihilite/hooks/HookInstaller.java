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
import java.util.logging.Level;
import java.util.logging.Logger;

/** Three hook phases — :entry, :return, :redefine — installed on
 *  classes whose internal name matches a registered spec. Two
 *  ByteBuddy channels compose: Advice for :entry and :return
 *  (with AssignReturned to mutate the original return), and
 *  MethodDelegation for :redefine (full body substitution).
 *  See {@link HookAdvice}, {@link ReturnAdvice}, and
 *  {@link GenericDispatcher} for the three targets. */
public final class HookInstaller {

    private static final Logger LOG = Logger.getLogger("nihilite.hooks.HookInstaller");

    private HookInstaller() {}

    private static final IFn CLJ_REG_MATCHING =
            Clojure.var("nihilite.registry", "matching");

    public static void install(Instrumentation inst) {
        try {
            new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Reiterating.INSTANCE)
                .ignore(ElementMatchers.nameStartsWith("java.")
                        .or(ElementMatchers.nameStartsWith("javax."))
                        .or(ElementMatchers.nameStartsWith("jdk."))
                        .or(ElementMatchers.nameStartsWith("sun."))
                        .or(ElementMatchers.nameStartsWith("com.sun."))
                        .or(ElementMatchers.isSynthetic()))
                .type(HookTypeMatcher.INSTANCE)
                .transform(HookInstaller::applyTransformer)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onTransformation(
                            TypeDescription typeDescription,
                            ClassLoader classLoader,
                            JavaModule module,
                            boolean loaded,
                            DynamicType dynamicType) {
                        /* silent on success */
                    }

                    @Override
                    public void onError(
                            String typeName,
                            ClassLoader classLoader,
                            JavaModule module,
                            boolean loaded,
                            Throwable throwable) {
                        LOG.log(Level.WARNING, "transform error on " + typeName, throwable);
                    }
                })
                .installOn(inst);
            LOG.info("HookInstaller armed (byte-buddy AgentBuilder, RETRANSFORMATION, "
                    + "Reiterating, Advice.AssignReturned post-processor)");
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "HookInstaller install failed", t);
        }
    }

    private static net.bytebuddy.dynamic.DynamicType.Builder<?> applyTransformer(
            net.bytebuddy.dynamic.DynamicType.Builder<?> builder,
            TypeDescription typeDescription,
            ClassLoader classLoader,
            JavaModule module,
            java.security.ProtectionDomain protectionDomain) {

        java.util.List<?> specs;
        try {
            String internal = typeDescription.getInternalName();
            Object raw = CLJ_REG_MATCHING.invoke(internal);
            if (!(raw instanceof java.util.List<?>)) return builder;
            specs = (java.util.List<?>) raw;
        } catch (Throwable t) {
            return builder;
        }

        java.util.List<String> entryMethods = new java.util.ArrayList<>();
        java.util.List<String> returnMethods = new java.util.ArrayList<>();
        java.util.List<String> throwMethods = new java.util.ArrayList<>();
        java.util.List<String> redefineMethods = new java.util.ArrayList<>();

        Object posKey = null, methodNameKey = null;
        for (Object s : specs) {
            java.util.Map<?, ?> sm = (java.util.Map<?, ?>) s;
            if (posKey == null) {
                for (Object k : sm.keySet()) {
                    if (k != null && k.toString().equals(":position")) {
                        posKey = k;
                        for (Object k2 : sm.keySet()) {
                            if (k2 != null && k2.toString().equals(":method-name")) {
                                methodNameKey = k2;
                                break;
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
            if (p.equals(":entry")) entryMethods.add(name);
            else if (p.equals(":return")) returnMethods.add(name);
            else if (p.equals(":throw")) throwMethods.add(name);
            else if (p.endsWith("redefine")) redefineMethods.add(name);
        }

        if (!entryMethods.isEmpty()) {
            ElementMatcher.Junction<MethodDescription> m = ElementMatchers.none();
            for (String name : entryMethods) m = m.or(ElementMatchers.named(name));
            builder = builder.visit(
                    net.bytebuddy.asm.Advice.withCustomMapping()
                            .with(new net.bytebuddy.asm.Advice.AssignReturned.Factory())
                            .to(HookAdvice.class)
                            .on(m.and(ElementMatchers.not(ElementMatchers.isConstructor()))
                                    .and(ElementMatchers.not(ElementMatchers.isTypeInitializer()))));
        }
        if (!returnMethods.isEmpty()) {
            ElementMatcher.Junction<MethodDescription> m = ElementMatchers.none();
            for (String name : returnMethods) m = m.or(ElementMatchers.named(name));
            builder = builder.visit(
                    net.bytebuddy.asm.Advice.withCustomMapping()
                            .with(new net.bytebuddy.asm.Advice.AssignReturned.Factory())
                            .to(ReturnAdvice.class)
                            .on(m.and(ElementMatchers.not(ElementMatchers.isConstructor()))
                                    .and(ElementMatchers.not(ElementMatchers.isTypeInitializer()))));
        }
        if (!throwMethods.isEmpty()) {
            ElementMatcher.Junction<MethodDescription> m = ElementMatchers.none();
            for (String name : throwMethods) m = m.or(ElementMatchers.named(name));
            builder = builder.visit(
                    net.bytebuddy.asm.Advice.withCustomMapping()
                            .to(ThrowAdvice.class)
                            .on(m.and(ElementMatchers.not(ElementMatchers.isConstructor()))
                                    .and(ElementMatchers.not(ElementMatchers.isTypeInitializer()))));
        }
        if (!redefineMethods.isEmpty()) {
            ElementMatcher.Junction<MethodDescription> redefineMatcher = ElementMatchers.none();
            for (String name : redefineMethods) {
                redefineMatcher = redefineMatcher.or(ElementMatchers.named(name));
            }
            redefineMatcher = redefineMatcher
                    .and(ElementMatchers.not(ElementMatchers.isConstructor()))
                    .and(ElementMatchers.not(ElementMatchers.isTypeInitializer()));
            builder = builder.method(redefineMatcher).intercept(
                    MethodDelegation.to(GenericDispatcher.class)
                            .withAssigner(DynamicAssigner.INSTANCE));
        }
        return builder;
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
                if (!(raw instanceof java.util.List<?>)) return false;
                return !((java.util.List<?>) raw).isEmpty();
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