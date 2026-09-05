package nihilite.server;

public final class ServerConstants {
    private ServerConstants() {}

    public static final String DEFAULT_HOST = "127.0.0.1";

    public static final int DEFAULT_PORT = 7888;

    public static final String VERSION_FALLBACK = "dev-j25-1.0";

    public static final String PORT_ARG_PREFIX = "--port=";

    public static final String BIND_ARG_PREFIX = "--bind=";

    public static final String PORT_PROPERTY = "nihilite.port";

    public static final String BIND_PROPERTY = "nihilite.bind";

    public static String runtimeVersion() {
        Package pkg = ServerConstants.class.getPackage();
        if (pkg != null) {
            String impl = pkg.getImplementationVersion();
            if (impl != null && !impl.isEmpty()) return impl;
        }
        return VERSION_FALLBACK;
    }
}