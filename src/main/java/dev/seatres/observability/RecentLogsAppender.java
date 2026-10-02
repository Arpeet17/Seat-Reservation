package dev.seatres.observability;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.encoder.Encoder;

/**
 * Keeps the most recent JSON log lines in memory so reviewers and operators can read logs through
 * GET /admin/logs without access to the hosting platform's console. Bounded, so it can never grow
 * without limit; per instance, so it complements (does not replace) stdout log shipping.
 */
public class RecentLogsAppender extends AppenderBase<ILoggingEvent> {

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static volatile int capacity = 2000;

    private Encoder<ILoggingEvent> encoder;

    public void setEncoder(Encoder<ILoggingEvent> encoder) {
        this.encoder = encoder;
    }

    public void setCapacity(int capacity) {
        RecentLogsAppender.capacity = capacity;
    }

    @Override
    public void start() {
        if (encoder == null) {
            addError("no encoder set for " + getName());
            return;
        }
        encoder.start();
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        String line = new String(encoder.encode(event), StandardCharsets.UTF_8).strip();
        synchronized (LOCK) {
            LINES.addLast(line);
            while (LINES.size() > capacity) {
                LINES.removeFirst();
            }
        }
    }

    /** Newest last; lines containing every non-null filter string, at most {@code limit}. */
    public static List<String> recent(int limit, String... contains) {
        List<String> snapshot;
        synchronized (LOCK) {
            snapshot = new ArrayList<>(LINES);
        }
        List<String> out = new ArrayList<>();
        for (int i = snapshot.size() - 1; i >= 0 && out.size() < limit; i--) {
            String line = snapshot.get(i);
            boolean match = true;
            for (String c : contains) {
                if (c != null && !line.contains(c)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                out.add(0, line);
            }
        }
        return out;
    }
}
