/*
 * Copyright 2018 The Hyve
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

package org.radarbase.hdfs.accounting

import org.radarbase.hdfs.FileStoreFactory
import org.radarbase.hdfs.util.DirectFunctionalValue
import org.radarbase.hdfs.util.TemporaryDirectory
import org.radarbase.hdfs.util.Timer
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.Flushable
import java.io.IOException
import java.nio.file.Paths

class Accountant @Throws(IOException::class)
constructor(factory: FileStoreFactory, topic: String) : Flushable, Closeable {
    private val offsetFile: OffsetRangeFile
    private val tempDir: TemporaryDirectory = TemporaryDirectory(factory.settings.tempDir, "accounting-")

    val offsets: OffsetRangeSet
        get() = offsetFile.offsets

    init {
        val offsetPath = factory.settings.outputPath
                .resolve(OFFSETS_FILE_NAME)
                .resolve("$topic.csv")
        this.offsetFile = OffsetRangeFile.read(factory.storageDriver, offsetPath)
        this.offsetFile.setTempDir(tempDir.path)
    }

    fun process(ledger: Ledger) {
        val timeProcess = System.nanoTime()
        offsetFile.addAll(ledger.offsets)
        offsetFile.triggerWrite()
        Timer.add("accounting.process", timeProcess)
    }

    @Throws(IOException::class)
    override fun close() {
        val timeClose = System.nanoTime()
        var exception: IOException? = null

        try {
            offsetFile.close()
        } catch (ex: IOException) {
            logger.error("Failed to close offsets", ex)
            exception = ex
        }

        tempDir.close()

        if (exception != null) {
            throw exception
        }
        Timer.add("accounting.close", timeClose)
    }

    @Throws(IOException::class)
    override fun flush() {
        val timeFlush = System.nanoTime()

        try {
            offsetFile.flush()
        } finally {
            Timer.add("accounting.flush", timeFlush)
        }
    }

    class Ledger {
        internal val offsets: OffsetRangeSet = OffsetRangeSet { DirectFunctionalValue(it) }

        fun add(transaction: Transaction) {
            val timeAdd = System.nanoTime()
            offsets.add(transaction.topicPartition, transaction.offset)
            Timer.add("accounting.add", timeAdd)
        }
    }

    class Transaction(val topicPartition: TopicPartition, internal val offset: Long)

    companion object {
        private val logger = LoggerFactory.getLogger(Accountant::class.java)

        private val OFFSETS_FILE_NAME = Paths.get("offsets")
    }
}