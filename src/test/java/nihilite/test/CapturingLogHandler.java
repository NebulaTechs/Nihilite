package nihilite.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

public final class CapturingLogHandler extends Handler {

    private final List<LogRecord> captured;

    public CapturingLogHandler() {
        this.captured = Collections.synchronizedList(new ArrayList<>());
    }

    public List<LogRecord> captured() {
        return captured;
    }

    @Override
    public void publish(LogRecord record) {
        if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
            captured.add(record);
        }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
}