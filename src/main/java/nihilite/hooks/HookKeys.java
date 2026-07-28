package nihilite.hooks;

import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical method-key builder for the hook-system P0 surface.
 *  Concatenates a class-internal name, method name, and JLS
 *  field descriptor into a single stable string:
 *
 *    <internal-name> "/" <method-name> "#" <descriptor>
 *
 *  The separator `#` is not legal in any JVM-internal name, method
 *  name, or JLS descriptor, so the concatenation is unambiguous.
 *
 *  Mirrors `nihilite.registry/method-key` byte-for-byte; the
 *  shared test in `nihilite.test.hook-keys` proves agreement.
 *  A bounded {@link LinkedHashMap} cache caps the {@link StringBuilder}
 *  churn on the hot class-load path; the cache is FIFO at 1024
 *  entries so repeated triples resolve in O(1) without leaking.
 */
public final class HookKeys {

    /** Bounded FIFO cache of `(internal|name|descriptor) -> methodKey`. */
    private static final int CACHE_SIZE = 1024;
    private static final Map<String, String> CACHE =
            java.util.Collections.synchronizedMap(
                    new LinkedHashMap<String, String>(CACHE_SIZE, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<String, String> eldest) {
                            return size() > CACHE_SIZE;
                        }
                    });

    private HookKeys() {}

    /** Build the canonical method-key string. Pure function on the
     *  three inputs; identical inputs always yield identical
     *  outputs and the result is interned via the bounded cache.
     */
    public static String build(String internal, String methodName, String descriptor) {
        String cacheKey = internal + "|" + methodName + "|" + descriptor;
        String cached = CACHE.get(cacheKey);
        if (cached != null) return cached;
        String fresh = internal + "/" + methodName + "#" + descriptor;
        CACHE.put(cacheKey, fresh);
        return fresh;
    }
}