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

package org.radarcns.hdfs;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.radarcns.hdfs.OffsetRange;
import org.radarcns.hdfs.OffsetRangeFile;
import org.radarcns.hdfs.OffsetRangeSet;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OffsetRangeFileTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Rule
    public ExpectedException exception = ExpectedException.none();
    private Path testFile;

    @Before
    public void setUp() throws IOException {
        testFile = folder.newFile().toPath();
    }

    @Test
    public void readEmpty() throws IOException {
        assertTrue(OffsetRangeFile.read(testFile).isEmpty());

        Files.delete(testFile);

        // will create on write
        try (OffsetRangeFile.Writer ignored = new OffsetRangeFile.Writer(testFile)) {
            assertTrue(OffsetRangeFile.read(testFile).isEmpty());
        }
    }

    @Test
    public void write() throws IOException {
        try (OffsetRangeFile.Writer rangeFile = new OffsetRangeFile.Writer(testFile)) {
            rangeFile.write(OffsetRange.parseFilename("a+0+0+1"));
            rangeFile.write(OffsetRange.parseFilename("a+0+1+2"));
        }
        System.out.println(new String(Files.readAllBytes(testFile)));

        OffsetRangeSet set = OffsetRangeFile.read(testFile);
        System.out.println(set);
        assertTrue(set.contains(OffsetRange.parseFilename("a+0+0+1")));
        assertTrue(set.contains(OffsetRange.parseFilename("a+0+1+2")));
        assertTrue(set.contains(OffsetRange.parseFilename("a+0+0+2")));
        assertFalse(set.contains(OffsetRange.parseFilename("a+0+0+3")));
        assertFalse(set.contains(OffsetRange.parseFilename("a+0+2+3")));
        assertFalse(set.contains(OffsetRange.parseFilename("a+1+0+1")));
        assertFalse(set.contains(OffsetRange.parseFilename("b+0+0+1")));
    }

    @Test
    public void cleanUp() throws IOException {
        try (OffsetRangeFile.Writer rangeFile = new OffsetRangeFile.Writer(testFile)) {
            rangeFile.write(OffsetRange.parseFilename("a+0+0+1"));
            rangeFile.write(OffsetRange.parseFilename("a+0+1+2"));
            rangeFile.write(OffsetRange.parseFilename("a+0+4+4"));
        }

        try (BufferedReader br = Files.newBufferedReader(testFile)) {
            assertEquals(4, br.lines().count());
        }

        OffsetRangeSet rangeSet = OffsetRangeFile.read(testFile);
        assertEquals(2, rangeSet.size("a", 0));

        OffsetRangeFile.cleanUp(testFile);

        try (BufferedReader br = Files.newBufferedReader(testFile)) {
            assertEquals(3, br.lines().count());
        }

        OffsetRangeSet updatedRangeSet = OffsetRangeFile.read(testFile);
        assertEquals(2, rangeSet.size("a", 0));

        assertEquals(rangeSet, updatedRangeSet);
    }
}