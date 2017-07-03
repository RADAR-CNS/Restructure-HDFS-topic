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

package org.radarcns.util;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nonnull;
import org.apache.avro.generic.GenericRecord;

/** Keeps file handles of a file. */
public class FileCache implements Closeable, Flushable, Comparable<FileCache> {
    private final OutputStream[] streams;
    private final Writer writer;
    private final RecordConverter recordConverter;
    private final File file;
    private long lastUse;

    /**
     * File cache of given file, using given converter factory.
     * @param converterFactory converter factory to create a converter to write files with.
     * @param file file to cache.
     * @param record example record to create converter from, this is not written to file.
     * @param gzip whether to gzip the records
     * @throws IOException
     */
    public FileCache(RecordConverterFactory converterFactory, File file,
            GenericRecord record, boolean gzip) throws IOException {
        this.file = file;
        boolean fileIsNew = !file.exists() || file.length() == 0;

        this.streams = new OutputStream[gzip ? 3 : 2];
        this.streams[0] = new FileOutputStream(file, true);
        this.streams[1] = new BufferedOutputStream(this.streams[0]);
        if (gzip) {
            this.streams[2] = new GZIPOutputStream(this.streams[1]);
        }

        this.writer = new OutputStreamWriter(this.streams[this.streams.length - 1]);
        this.recordConverter = converterFactory.converterFor(writer, record, fileIsNew);
    }

    /** Write a record to the cache. */
    public void writeRecord(GenericRecord record) throws IOException {
        this.recordConverter.writeRecord(record);
        lastUse = System.nanoTime();
    }

    @Override
    public void close() throws IOException {
        recordConverter.close();
        writer.close();
        for (int i = streams.length - 1; i >= 0; i--) {
            streams[i].close();
        }
    }

    @Override
    public void flush() throws IOException {
        recordConverter.flush();
    }

    /**
     * Compares time that the filecaches were last used. If equal, it lexicographically compares
     * the absolute path of the file.
     * @param other FileCache to compare with.
     */
    @Override
    public int compareTo(@Nonnull FileCache other) {
        int result = Long.compare(lastUse, other.lastUse);
        if (result != 0) {
            return result;
        }
        return file.compareTo(other.file);
    }

    /** File that the cache is maintaining. */
    public File getFile() {
        return file;
    }
}
