package org.radarcns.hdfs.util;

import javax.annotation.Nonnull;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/** Timer for multi-threaded timings. The timer may be disabled to increase program performance. */
public final class Timer {
    private final ConcurrentMap<Category, LongAdder> times;
    private static final Timer instance = new Timer();
    private volatile boolean isEnabled;

    public static Timer getInstance() {
        return instance;
    }

    private Timer() {
        this.times = new ConcurrentHashMap<>();
        this.isEnabled = true;
    }

    /** Add number of nanoseconds to given type of measurement. */
    public void add(String type, long nanoTimeStart) {
        if (isEnabled) {
            long time = System.nanoTime() - nanoTimeStart;
            Category cat = new Category(type);
            times.computeIfAbsent(cat, c -> new LongAdder()).add(time);
        }
    }

    /**
     * Enable or disable timer. A disabled timer will have much less performance impact on
     * timed code.
     */
    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
    }

    @Override
    public String toString() {
        if (!isEnabled) {
            return "Timings: disabled";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Timings:");

        ConcurrentMap<String, List<Map.Entry<Category, LongAdder>>> timesByType = this.times
                .entrySet().stream()
                .collect(Collectors.groupingByConcurrent(c -> c.getKey().type));

        timesByType.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> {
                    builder.append("\n\t");
                    builder.append(entry.getKey());
                    builder.append(" - time: ");
                    formatTime(builder, entry.getValue().stream()
                            .mapToLong(e -> e.getValue().sum())
                            .sum());
                    builder.append(" - threads: ");
                    builder.append(entry.getValue().size());
                });

        return builder.toString();
    }

    private static void formatTime(StringBuilder builder, long nanoTime) {
        int seconds = (int)(nanoTime / 1_000_000_000L);
        int millis = (int) (nanoTime / 1_000_000L);
        ProgressBar.formatTime(builder, seconds);
        builder.append('.');
        if (millis < 100) builder.append('0');
        if (millis < 10) builder.append('0');
        builder.append(millis);
    }

    private static class Category {
        private final String type;
        private final String thread;

        Category(@Nonnull String type) {
            this.type = type;
            this.thread = Thread.currentThread().getName();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Category category = (Category) o;
            return Objects.equals(type, category.type) &&
                    Objects.equals(thread, category.thread);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, thread);
        }
    }
}
