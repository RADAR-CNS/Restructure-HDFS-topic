package org.radarcns.hdfs;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

import static java.time.ZoneOffset.UTC;

public abstract class RecordPathFactory implements Plugin {
    private static final Logger logger = LoggerFactory.getLogger(RecordPathFactory.class);
    private static final Pattern ILLEGAL_CHARACTER_PATTERN = Pattern.compile("[^a-zA-Z0-9_-]+");

    public static final DateTimeFormatter HOURLY_TIME_BIN_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HH'00'")
            .withZone(UTC);

    private Path root;
    private String extension;

    public RecordOrganization getRecordOrganization(String topic, GenericRecord record, int attempt) {
        GenericRecord keyField = (GenericRecord) record.get("key");
        GenericRecord valueField = (GenericRecord) record.get("value");

        if (keyField == null || valueField == null) {
            logger.error("Failed to process {}", record);
            throw new IllegalArgumentException("Failed to process " + record + "; no key or value");
        }

        Instant time = RecordPathFactory.getDate(keyField, valueField);

        Path relativePath = getRelativePath(topic, keyField, valueField, time, attempt);
        Path outputPath = getRoot().resolve(relativePath);
        String category = getCategory(keyField, valueField);
        return new RecordOrganization(outputPath, category, time);
    }

    public abstract Path getRelativePath(String topic, GenericRecord key, GenericRecord value, Instant time, int attempt);

    public abstract String getCategory(GenericRecord key, GenericRecord value);

    public Path getRoot() {
        return this.root;
    }

    public void setRoot(Path rootDirectory) {
        this.root = rootDirectory;
    }

    public String getExtension() {
        return this.extension;
    }

    public void setExtension(String extension) {
        this.extension = extension;
    }

    public boolean isTopicPartitioned() {
        return true;
    }

    public DateTimeFormatter getTimeBinFormat() {
        return HOURLY_TIME_BIN_FORMAT;
    }

    public String getTimeBin(Instant time) {
        return time == null ? "unknown_date" : getTimeBinFormat().format(time);
    }

    public static class RecordOrganization {
        private final Path path;
        private final Instant time;
        private final String category;

        public RecordOrganization(Path path, String category, Instant time) {
            this.path = path;
            this.time = time;
            this.category = category;
        }

        public Path getPath() {
            return path;
        }

        public Instant getTime() {
            return time;
        }

        public String getCategory() {
            return category;
        }
    }

    public static Instant getDate(GenericRecord keyField, GenericRecord valueField) {
        Schema.Field timeField = valueField.getSchema().getField("time");
        if (timeField != null) {
            double time = (Double) valueField.get(timeField.pos());
            // Convert from millis to date and apply dateFormat
            return Instant.ofEpochMilli((long) (time * 1000d));
        }

        // WindowedKey
        timeField = keyField.getSchema().getField("start");
        if (timeField == null) {
            return null;
        }
        return Instant.ofEpochMilli((Long) keyField.get("start"));
    }

    public static String sanitizeId(Object id, String defaultValue) {
        if (id == null) {
            return defaultValue;
        }
        String idString = ILLEGAL_CHARACTER_PATTERN.matcher(id.toString()).replaceAll("");
        if (idString.isEmpty()) {
            return defaultValue;
        } else {
            return idString;
        }
    }
}
