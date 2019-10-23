/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarbase.hdfs;

import static org.radarbase.hdfs.util.ProgressBar.formatTime;

import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.mapred.FsInput;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.radarbase.hdfs.accounting.Accountant;
import org.radarbase.hdfs.accounting.OffsetRange;
import org.radarbase.hdfs.accounting.OffsetRangeSet;
import org.radarbase.hdfs.accounting.RemoteLockManager;
import org.radarbase.hdfs.accounting.TopicPartition;
import org.radarbase.hdfs.data.FileCacheStore;
import org.radarbase.hdfs.util.ProgressBar;
import org.radarbase.hdfs.util.ReadOnlyFunctionalValue;
import org.radarbase.hdfs.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RadarHdfsRestructure {
    private static final Logger logger = LoggerFactory.getLogger(RadarHdfsRestructure.class);

    /** Number of offsets to process in a single task. */
    private static final long BATCH_SIZE = 500_000;

    private final int numThreads;
    private final Configuration conf;
    private final FileStoreFactory fileStoreFactory;
    private final RecordPathFactory pathFactory;
    private final long maxFilesPerTopic;
    private List<String> excludeTopics;

    private LongAdder processedFileCount;
    private LongAdder processedRecordsCount;

    private RemoteLockManager lockManager;

    public RadarHdfsRestructure(FileStoreFactory factory) {
        conf = factory.getHdfsSettings().getConfiguration();
        conf.set("fs.defaultFS", "hdfs://" + factory.getHdfsSettings().getHdfsName());
        this.numThreads = factory.getSettings().getNumThreads();
        long maxFiles = factory.getSettings().getMaxFilesPerTopic();
        if (maxFiles < 1) {
            maxFiles = Long.MAX_VALUE;
        }
        this.maxFilesPerTopic = maxFiles;
        this.excludeTopics = factory.getSettings().getExcludeTopics();
        this.fileStoreFactory = factory;
        this.pathFactory = factory.getPathFactory();
    }

    public long getProcessedFileCount() {
        return processedFileCount.sum();
    }

    public long getProcessedRecordsCount() {
        return processedRecordsCount.sum();
    }

    public void start(String directoryName) throws IOException {
        // Get files and directories
        Path path = new Path(directoryName);
        FileSystem fs = path.getFileSystem(conf);
        path = fs.getFileStatus(path).getPath();  // get absolute file

        List<Path> topics = getTopicPaths(fs, path);


        try (Accountant accountant = new Accountant(fileStoreFactory)) {
            logger.info("Retrieving file list from {}", path);

            Instant timeStart = Instant.now();
            // Get filenames to process
            List<TopicFileList> topicPaths = getTopicPaths(fs, path, accountant.getOffsets());
            logger.info("Time retrieving file list: {}",
                    formatTime(Duration.between(timeStart, Instant.now())));

            processPaths(topicPaths, accountant);
        } catch (InterruptedException e) {
            logger.error("Processing interrupted");
            Thread.currentThread().interrupt();
        }
    }

    private List<Path> getTopicPaths(FileSystem fs, Path path) {
        List<Path> topics = findTopicPaths(fs, path)
                .distinct()
                .filter(f -> !excludeTopics.contains(f.getName()))
                .collect(Collectors.toList());

        Collections.shuffle(topics);
        return topics;
    }

    private List<TopicFileList> getTopicPaths(FileSystem fs, Path startPath, Path topicPath, OffsetRangeSet seenFiles) {
        Path actualPath = startPath.toUri().getRawPath();
        Map<String, List<TopicFile>> topics = walk(fs, topicPath)
                .filter(f -> f.getName().endsWith(".avro"))
                .map(f -> new TopicFile(f.getParent().getParent().getName(), f))
                .filter(f -> !seenFiles.contains(f.range))
                .collect(Collectors.groupingBy(TopicFile::getTopic));

        return topics.values().stream()
                .map(v -> new TopicFileList(v.stream().limit(maxFilesPerTopic)))
                .collect(Collectors.toList());
    }

    private Stream<Path> walk(FileSystem fs, Path path) {
        FileStatus[] files;
        try {
            files = fs.listStatus(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Stream.of(files).parallel()
                .flatMap(f -> {
                    if (f.isDirectory()) {
                        if (f.getPath().getName().equals("+tmp")) {
                            return Stream.empty();
                        } else {
                            return walk(fs, f.getPath());
                        }
                    } else {
                        return Stream.of(f.getPath());
                    }
                });
    }

    private Stream<Path> findTopicPaths(FileSystem fs, Path path) {
        FileStatus[] files;
        try {
            files = fs.listStatus(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Stream.of(files).parallel()
                .flatMap(f -> {
                    Path p = f.getPath();
                    String filename = p.getName();
                    if (f.isDirectory()) {
                        if (filename.equals("+tmp")) {
                            return Stream.empty();
                        } else {
                            return findTopicPaths(fs, p);
                        }
                    } else if (filename.endsWith(".avro")) {
                        return Stream.of(p.getParent().getParent());
                    } else {
                        return Stream.empty();
                    }
                })
                .distinct();
    }

    private void processPaths(List<TopicFileList> topicPaths, Accountant accountant) throws InterruptedException {
        int numFiles = topicPaths.stream()
                .mapToInt(TopicFileList::numberOfFiles)
                .sum();
        long numOffsets = topicPaths.stream()
                .mapToLong(TopicFileList::numberOfOffsets)
                .sum();

        logger.info("Converting {} files with {} records",
                numFiles, NumberFormat.getNumberInstance().format(numOffsets));

        processedFileCount = new LongAdder();
        processedRecordsCount = new LongAdder();
        OffsetRangeSet seenOffsets = accountant.getOffsets()
                .withFactory(ReadOnlyFunctionalValue::new);

        ExecutorService executor = Executors.newWorkStealingPool(pathFactory.isTopicPartitioned() ? this.numThreads : 1);

        ProgressBar progressBar = new ProgressBar(numOffsets, 50, 500, TimeUnit.MILLISECONDS);

        // Actually process the files

        topicPaths.stream()
                // ensure that largest values go first on the executor queue
                .sorted(Comparator.comparingLong(TopicFileList::numberOfOffsets).reversed())
                .forEach(paths -> {
                    String size = NumberFormat.getNumberInstance().format(paths.size);
                    String topic = paths.files.get(0).topic;
                    logger.info("Processing {} records for topic {}", size, topic);
                    executor.execute(() -> {
                        long batchSize = Math.round(BATCH_SIZE * ThreadLocalRandom.current().nextDouble(0.75, 1.25));
                        long currentSize = 0;
                        try (FileCacheStore cache = fileStoreFactory.newFileCacheStore(accountant)) {
                            for (TopicFile file : paths.files) {
                                try {
                                    this.processFile(file, cache, progressBar, seenOffsets);
                                } catch (JsonMappingException exc) {
                                    logger.error("Cannot map values", exc);
                                }
                                processedFileCount.increment();

                                currentSize += file.size();
                                if (currentSize >= batchSize) {
                                    currentSize = 0;
                                    cache.flush();
                                }
                            }
                        } catch (IOException | UncheckedIOException ex) {
                            logger.error("Failed to process file", ex);
                        } catch (IllegalStateException ex) {
                            logger.warn("Shutting down");
                        }
                    });
                });

        progressBar.update(0);

        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        progressBar.update(numOffsets);
    }

    private void processFile(TopicFile file, FileCacheStore cache,
            ProgressBar progressBar, OffsetRangeSet seenOffsets) throws IOException {
        logger.debug("Reading {}", file.path);

        // Read and parseFilename avro file
        FsInput input = new FsInput(file.path, conf);

        // processing zero-length files may trigger a stall. See:
        // https://github.com/RADAR-base/Restructure-HDFS-topic/issues/3
        if (input.length() == 0) {
            logger.warn("File {} has zero length, skipping.", file.path);
            return;
        }

        Timer timer = Timer.getInstance();

        long timeRead = System.nanoTime();
        DataFileReader<GenericRecord> dataFileReader = new DataFileReader<>(input,
                new GenericDatumReader<>());

        GenericRecord record = null;
        long offset = file.range.getOffsetFrom();
        while (dataFileReader.hasNext()) {
            record = dataFileReader.next(record);
            timer.add("read", timeRead);

            long timeAccount = System.nanoTime();
            boolean alreadyContains = seenOffsets.contains(file.range.getTopicPartition(), offset);
            timer.add("accounting.create", timeAccount);
            if (!alreadyContains) {
                // Get the fields
                this.writeRecord(file.range.getTopicPartition(), record, cache, offset, 0);
            }
            processedRecordsCount.increment();
            progressBar.update(processedRecordsCount.sum());

            offset++;
            timeRead = System.nanoTime();
        }
    }

    private void writeRecord(TopicPartition topicPartition, GenericRecord record,
            FileCacheStore cache, long offset, int suffix) throws IOException {
        RecordPathFactory.RecordOrganization metadata = pathFactory.getRecordOrganization(
                topicPartition.topic, record, suffix);

        Timer timer = Timer.getInstance();

        long timeAccount = System.nanoTime();
        Accountant.Transaction transaction = new Accountant.Transaction(topicPartition, offset);
        timer.add("accounting.create", timeAccount);

        // Write data
        long timeWrite = System.nanoTime();
        FileCacheStore.WriteResponse response = cache.writeRecord(
                metadata.getPath(), record, transaction);
        timer.add("write", timeWrite);

        if (!response.isSuccessful()) {
            // Write was unsuccessful due to different number of columns,
            // try again with new file name
            writeRecord(topicPartition, record, cache, offset, ++suffix);
        }
    }

    private static class TopicFileList {
        private final List<TopicFile> files;
        private final long size;

        public TopicFileList(Stream<TopicFile> files) {
            this.files = files.collect(Collectors.toList());
            this.size = this.files.stream()
                    .mapToLong(TopicFile::size)
                    .sum();
        }

        public int numberOfFiles() {
            return this.files.size();
        }

        public long numberOfOffsets() {
            return size;
        }
    }

    private static class TopicFile {
        private final String topic;
        private final Path path;
        private final OffsetRange range;

        private TopicFile(String topic, Path path) {
            this.topic = topic;
            this.path = path;
            this.range = OffsetRange.parseFilename(path.getName());
        }

        public String getTopic() {
            return topic;
        }

        public long size() {
            return 1 + range.getOffsetTo() - range.getOffsetFrom();
        }
    }
}
