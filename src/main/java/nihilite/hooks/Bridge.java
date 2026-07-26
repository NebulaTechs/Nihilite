package nihilite.hooks;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Pure-JDK glue surface between the byte-code transformer's call
 *  sites and the Clojure runtime. Holds no Clojure references — the
 *  runtime side reaches it through {@link #installDispatcher} and
 *  {@link #installRedefineDispatcher}, both called from the
 *  non-daemon agent worker once Clojure is up. */
public final class Bridge {

    private static final Logger LOG = Logger.getLogger("nihilite.hooks.Bridge");

    private Bridge() {}

    /** Hook-event dispatch contract. Pure Java — no Clojure types. */
    public interface Dispatcher {
        void fire(String id, Object self, Object[] args);
    }

    /** Set by the agent worker after Clojure boots. Volatile gives
     *  the byte-code call sites a happens-before edge on every read. */
    private static volatile Dispatcher DISPATCHER;

    /** :redefine-phase IFn slot. Set by the agent worker after the
     *  redefine dispatcher is installed; read directly by
     *  {@link GenericDispatcher}. */
    public static volatile Object REDISPATCHER;

    public static void installDispatcher(Dispatcher d) {
        DISPATCHER = d;
    }

    public static void installRedefineDispatcher(Object d) {
        REDISPATCHER = d;
    }


    public static boolean dispatcherInstalled() {
        return DISPATCHER != null;
    }

    /** The only front door from byte-code. Silent no-op when the
     *  runtime isn't up yet — byte-code fires early during class
     *  load and must not crash. */
    public static void fire(String id, Object self, Object[] args) {
        Dispatcher d = DISPATCHER;
        if (d == null) return;
        try {
            d.fire(id, self, args);
        } catch (Throwable t) {
            try {
                LOG.log(Level.SEVERE, "bridge fire failed (id=" + id + ")", t);
            } catch (Throwable st) {
                LOG.log(Level.SEVERE, "bridge-fire log fallback failed", st);
            }
        }
    }
}