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

package org.radarcns.hdfs.data;

import org.apache.avro.generic.GenericRecord;
import org.radarcns.hdfs.Frequency;
import org.radarcns.hdfs.OffsetRange;
import org.radarcns.hdfs.OffsetRangeFile;
import org.radarcns.hdfs.util.ThrowingConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.radarcns.hdfs.util.ThrowingConsumer.tryCatch;

/**
 * Caches open file handles. If more than the limit is cached, the half of the files that were used
 * the longest ago cache are evicted from cache.
 */
public class FileCacheStore implements Flushable, Closeable {
    private static final Logger logger = LoggerFactory.getLogger(FileCacheStore.class);

    private final boolean deduplicate;
    private final Path tmpDir;
    private final Compression compression;

    private final StorageDriver storageDriver;
    private RecordConverterFactory converterFactory;
    private final int maxFiles;
    private final Map<Path, FileCache> caches;
    private OffsetRangeFile offsets;
    private Frequency bins;

    public FileCacheStore(StorageDriver storageDriver, RecordConverterFactory converterFactory, int maxFiles, Compression compression, Path tmpDir, boolean deduplicate) throws IOException {
        this.storageDriver = storageDriver;
        this.converterFactory = converterFactory;
        this.maxFiles = maxFiles;
        this.caches = new HashMap<>(maxFiles * 4 / 3 + 1);
        this.compression = compression;
        this.deduplicate = deduplicate;
        this.tmpDir = tmpDir.resolve(UUID.randomUUID().toString());
        Files.createDirectories(this.tmpDir);
    }

    public void setBookkeeping(OffsetRangeFile offsets, Frequency bins) {
        this.offsets = offsets;
        this.bins = bins;
    }

    /**
     * Append a record to given file. If the file handle and writer are already open in this cache,
     * those will be used. Otherwise, the file will be opened and the file handle cached.
     *
     * @param path file to append data to
     * @param record data
     * @return Integer value according to one of the response codes.
     * @throws IOException when failing to open a file or writing to it.
     */
    public WriteResponse writeRecord(Path path, GenericRecord record, OffsetRange range, Frequency.Bin bin) throws IOException {
        FileCache cache = caches.get(path);
        boolean hasCache = cache != null;
        if (!hasCache) {
            ensureCapacity();

            Path dir = path.getParent();
            Files.createDirectories(dir);

            try {
                cache = new FileCache(storageDriver, converterFactory, path, record, compression,
                        tmpDir, deduplicate, offsets, bins);
            } catch (IOException ex) {
                logger.error("Could not open cache for {}", path, ex);
                return WriteResponse.NO_CACHE_AND_NO_WRITE;
            }
            caches.put(path, cache);
        }

        try {
            if (cache.writeRecord(range, bin, record)) {
                return hasCache ? WriteResponse.CACHE_AND_WRITE : WriteResponse.NO_CACHE_AND_WRITE;
            } else {
                // The file path was not in cache but the file exists and this write is
                // unsuccessful because of different number of columns
                return hasCache ? WriteResponse.CACHE_AND_NO_WRITE : WriteResponse.NO_CACHE_AND_NO_WRITE;
            }
        } catch (IOException ex) {
            logger.error("Failed to write record. Closing cache {}.", cache.getPath(), ex);
            cache.markError();
            caches.remove(cache.getPath());
            cache.close();
            return WriteResponse.NO_CACHE_AND_NO_WRITE;
        }
    }

    /**
     * Ensure that a new filecache can be added. Evict files used longest ago from cache if needed.
     */
    private void ensureCapacity() throws IOException {
        if (caches.size() == maxFiles) {
            ArrayList<FileCache> cacheList = new ArrayList<>(caches.values());
            Collections.sort(cacheList);
            for (int i = 0; i < cacheList.size() / 2; i++) {
                FileCache rmCache = cacheList.get(i);
                caches.remove(rmCache.getPath());
                rmCache.close();
            }
        }
    }

    @Override
    public void flush() throws IOException {
        allCaches(FileCache::flush);
    }

    @Override
    public void close() throws IOException {
        try {
            allCaches(FileCache::close);
            if (tmpDir != null) {
                Files.walk(tmpDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(tryCatch(Files::delete, (p, ex) -> logger.warn(
                                "Failed to remove temporary file {}: {}", p, ex)));
            }
        } finally {
            caches.clear();
        }
    }

    private void allCaches(ThrowingConsumer<FileCache> cacheHandler) throws IOException {
        try {
            caches.values().parallelStream()
                    .forEach(tryCatch(cacheHandler, (c, ex) -> {
                        throw new IllegalStateException(ex);
                    }));
        } catch (IllegalStateException ex) {
            throw (IOException) ex.getCause();
        }
    }

    // Response codes for each write record case
    public enum WriteResponse {
        /** Cache hit and write was successful. */
        CACHE_AND_WRITE(true, true),
        /** Cache hit and write was unsuccessful because of a mismatch in number of columns. */
        CACHE_AND_NO_WRITE(true, false),
        /** Cache miss and write was successful. */
        NO_CACHE_AND_WRITE(false, true),
        /** Cache miss and write was unsuccessful because of a mismatch in number of columns. */
        NO_CACHE_AND_NO_WRITE(false, false);

        private final boolean successful;
        private final boolean cacheHit;

        /**
         * Write status.
         * @param cacheHit whether the cache was used to write.
         * @param successful whether the write was successful.
         */
        WriteResponse(boolean cacheHit, boolean successful) {
            this.cacheHit = cacheHit;
            this.successful = successful;
        }

        public boolean isSuccessful() {
            return successful;
        }

        public boolean isCacheHit() {
            return cacheHit;
        }
    }
}
