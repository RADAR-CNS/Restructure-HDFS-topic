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

package org.radarcns.data;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
public class FileCacheStoreTest {

    @Parameterized.Parameters
    public static Collection<Boolean> doStage() {
        return Arrays.asList(Boolean.TRUE, Boolean.FALSE);
    }

    @Parameterized.Parameter
    public Boolean doStage;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void appendLine() throws IOException {
        Path f1 = folder.newFile().toPath();
        Path f2 = folder.newFile().toPath();
        Path f3 = folder.newFile().toPath();
        Path d4 = folder.newFolder().toPath();
        Path f4 = d4.resolve("f4.txt");
        Path newFile = folder.newFile().toPath();

        Files.delete(f1);
        Files.delete(d4);

        RecordConverterFactory csvFactory = CsvAvroConverter.getFactory();
        Schema simpleSchema = SchemaBuilder.record("simple").fields()
                .name("a").type("string").noDefault()
                .endRecord();

        Schema conflictSchema = SchemaBuilder.record("simple").fields()
                .name("a").type("string").noDefault()
                .name("b").type("string").noDefault()
                .endRecord();

        GenericRecord record;

        try (FileCacheStore cache = new FileCacheStore(csvFactory, 2, false, false, doStage)) {
            record = new GenericRecordBuilder(simpleSchema).set("a", "something").build();
            assertEquals(cache.writeRecord(f1, record), FileCacheStore.WriteStatus.NO_CACHE_AND_WRITE);
            record = new GenericRecordBuilder(simpleSchema).set("a", "somethingElse").build();
            assertEquals(cache.writeRecord(f1, record), FileCacheStore.WriteStatus.CACHE_AND_WRITE);
            record = new GenericRecordBuilder(simpleSchema).set("a", "something").build();
            assertEquals(cache.writeRecord(f2, record), FileCacheStore.WriteStatus.NO_CACHE_AND_WRITE);
            record = new GenericRecordBuilder(simpleSchema).set("a", "third").build();
            assertEquals(cache.writeRecord(f1, record), FileCacheStore.WriteStatus.CACHE_AND_WRITE);
            record = new GenericRecordBuilder(simpleSchema).set("a", "f3").build();
            assertEquals(cache.writeRecord(f3, record), FileCacheStore.WriteStatus.NO_CACHE_AND_WRITE);
            record = new GenericRecordBuilder(simpleSchema).set("a", "f2").build();
            assertEquals(cache.writeRecord(f2, record), FileCacheStore.WriteStatus.NO_CACHE_AND_WRITE);
            record = new GenericRecordBuilder(simpleSchema).set("a", "f3").build();
            assertEquals(cache.writeRecord(f3, record), FileCacheStore.WriteStatus.CACHE_AND_WRITE);
            record = new GenericRecordBuilder(simpleSchema).set("a", "f4").build();
            assertEquals(cache.writeRecord(f4, record), FileCacheStore.WriteStatus.NO_CACHE_AND_WRITE);
            record = new GenericRecordBuilder(simpleSchema).set("a", "f3").build();
            assertEquals(cache.writeRecord(f3, record), FileCacheStore.WriteStatus.CACHE_AND_WRITE);
            record = new GenericRecordBuilder(conflictSchema).set("a", "f3"). set("b", "conflict").build();
            assertEquals(cache.writeRecord(f3, record), FileCacheStore.WriteStatus.CACHE_AND_NO_WRITE);
            record = new GenericRecordBuilder(conflictSchema).set("a", "f1"). set("b", "conflict").build();
            // Cannot write to file even though the file is not in cache since schema is different
            assertEquals(cache.writeRecord(f1, record), FileCacheStore.WriteStatus.NO_CACHE_AND_NO_WRITE);
            // Can write the same record to a new file
            assertEquals(cache.writeRecord(newFile, record), FileCacheStore.WriteStatus.NO_CACHE_AND_WRITE);
        }

        assertEquals("a\nsomething\nsomethingElse\nthird\n", new String(Files.readAllBytes(f1)));
        assertEquals("a\nsomething\nf2\n", new String(Files.readAllBytes(f2)));
        assertEquals("a\nf3\nf3\nf3\n", new String(Files.readAllBytes(f3)));
        assertEquals("a\nf4\n", new String(Files.readAllBytes(f4)));
        assertEquals("a,b\nf1,conflict\n", new String(Files.readAllBytes(newFile)));
    }
}
