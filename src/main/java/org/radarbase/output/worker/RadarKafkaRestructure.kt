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

package org.radarbase.output.worker

import org.radarbase.output.FileStoreFactory
import org.radarbase.output.accounting.Accountant
import org.radarbase.output.kafka.TopicFile
import org.radarbase.output.kafka.TopicFileList
import org.radarbase.output.path.RecordPathFactory
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder
import java.util.stream.Collectors
import java.util.stream.Stream
import kotlin.streams.asStream

/**
 * Performs the following actions
 * - Recursively scans target directory for any avro files
 *    - Deduces the topic name from two directories up
 *    - Continue until all files have been scanned
 * - In separate threads, start worker for all topics
 *    - Acquire a lock before processing to avoid multiple processing of files
 */
class RadarKafkaRestructure(
        private val fileStoreFactory: FileStoreFactory
): Closeable {
    private val pathFactory: RecordPathFactory = fileStoreFactory.pathFactory
    private val maxFilesPerTopic: Int = fileStoreFactory.config.worker.maxFilesPerTopic ?: Int.MAX_VALUE
    private val kafkaStorage = fileStoreFactory.kafkaStorage

    private val lockManager = fileStoreFactory.remoteLockManager
    private val excludeTopics: Set<String> = fileStoreFactory.config.topics
            .filter { (_, conf) -> conf.exclude }
            .map { (topic, _) -> topic }
            .toSet()

    private val isClosed = AtomicBoolean(false)

    val processedFileCount = LongAdder()
    val processedRecordsCount = LongAdder()

    @Throws(IOException::class, InterruptedException::class)
    fun process(directoryName: String) {
        // Get files and directories
        val absolutePath = Paths.get(directoryName)

        logger.info("Scanning topics...")

        val paths = getTopicPaths(absolutePath)

        logger.info("{} topics found", paths.size)

        paths.parallelStream()
                .forEach { p ->
                    try {
                        val (fileCount, recordCount) = mapTopic(p)
                        processedFileCount.add(fileCount)
                        processedRecordsCount.add(recordCount)
                    } catch (ex: Exception) {
                        logger.warn("Failed to map topic", ex)
                    }
                }
    }

    private fun mapTopic(topicPath: Path): ProcessingStatistics {
        if (isClosed.get()) {
            return ProcessingStatistics(0L, 0L)
        }

        val topic = topicPath.fileName.toString()

        return try {
            lockManager.acquireTopicLock(topic)?.use {
                Accountant(fileStoreFactory, topic).use { accountant ->
                    RestructureWorker(pathFactory, kafkaStorage, accountant, fileStoreFactory, isClosed).use { worker ->
                        try {
                            val seenFiles = accountant.offsets
                            val topicPaths = TopicFileList(topic, findRecordPaths(topic, topicPath)
                                    .filter { f -> !seenFiles.contains(f.range) }
                                    .take(maxFilesPerTopic)
                                    .toList())

                            if (topicPaths.numberOfFiles > 0) {
                                worker.processPaths(topicPaths)
                            }
                        } catch (ex: Exception) {
                            logger.error("Failed to map files of topic {}", topic, ex)
                        }

                        ProcessingStatistics(worker.processedFileCount, worker.processedRecordsCount)
                    }
                }
            }
        } catch (ex: IOException) {
            logger.error("Failed to map files of topic {}", topic, ex)
            null
        } ?: ProcessingStatistics(0L, 0L)
    }

    private fun findTopicPaths(path: Path): Stream<Path> {
        val fileStatuses = kafkaStorage.list(path)
        val avroFile = fileStatuses.find {  !it.isDirectory && it.path.fileName.toString().endsWith(".avro", true) }

        return if (avroFile != null) {
            Stream.of(avroFile.path.parent.parent)
        } else {
            fileStatuses.asSequence().asStream()
                    .filter { it.isDirectory && it.path.fileName.toString() != "+tmp" }
                    .flatMap { findTopicPaths(it.path) }
        }
    }

    private fun findRecordPaths(topic: String, path: Path): Sequence<TopicFile> = kafkaStorage.list(path)
            .flatMap { status ->
                val filename = status.path.fileName.toString()
                when {
                    status.isDirectory && filename != "+tmp" -> findRecordPaths(topic, status.path)
                    filename.endsWith(".avro") -> sequenceOf(TopicFile(topic, status.path))
                    else -> emptySequence()
                }
            }

    override fun close() {
        isClosed.set(true)
    }

    private fun getTopicPaths(path: Path): List<Path> = findTopicPaths(path)
                .distinct()
                .filter { f -> !excludeTopics.contains(f.fileName.toString()) }
                .collect(Collectors.toList())
                .also { it.shuffle() }

    private data class ProcessingStatistics(val fileCount: Long, val recordCount: Long)

    companion object {
        private val logger = LoggerFactory.getLogger(RadarKafkaRestructure::class.java)
    }
}
