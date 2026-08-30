package nihilite.hooks;

public final class HookCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HookCancelledException() {
        super("hook :action :cancel short-circuited the host method");
    }
}