package nihilite.hooks;

public final class NihiliteAdviceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String specId;

    public NihiliteAdviceException(String specId, Throwable cause) {
        super("nihilite advice failed for spec=" + specId, cause);
        this.specId = specId;
    }

    public String getSpecId() {
        return specId;
    }
}