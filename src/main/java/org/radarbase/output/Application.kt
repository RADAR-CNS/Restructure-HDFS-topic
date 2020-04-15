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

package org.radarbase.output

import com.beust.jcommander.JCommander
import com.beust.jcommander.ParameterException
import io.minio.MinioClient
import org.apache.hadoop.fs.FileSystem
import org.radarbase.output.accounting.Accountant
import org.radarbase.output.accounting.RedisRemoteLockManager
import org.radarbase.output.accounting.RemoteLockManager
import org.radarbase.output.compression.Compression
import org.radarbase.output.config.CommandLineArgs
import org.radarbase.output.config.HdfsConfig
import org.radarbase.output.config.ResourceType
import org.radarbase.output.config.RestructureConfig
import org.radarbase.output.format.RecordConverterFactory
import org.radarbase.output.kafka.HdfsKafkaStorage
import org.radarbase.output.kafka.KafkaStorage
import org.radarbase.output.kafka.S3KafkaStorage
import org.radarbase.output.path.RecordPathFactory
import org.radarbase.output.storage.StorageDriver
import org.radarbase.output.util.ProgressBar.Companion.format
import org.radarbase.output.util.Timer
import org.radarbase.output.worker.FileCacheStore
import org.radarbase.output.worker.RadarKafkaRestructure
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPool
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.Temporal
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Main application.  */
class Application(
        config: RestructureConfig
) : FileStoreFactory {

    override val config = config.apply { validate() }
    override val recordConverter: RecordConverterFactory = config.format.createConverter()
    override val compression: Compression = config.compression.createCompression()
    override val pathFactory: RecordPathFactory = config.paths.createFactory().apply {
        extension = recordConverter.extension + compression.extension
        root = config.paths.output
    }
    override val storageDriver: StorageDriver = config.target.toStorageDriver()
    override val redisPool: JedisPool = JedisPool(config.redis.uri)
    override val remoteLockManager: RemoteLockManager = RedisRemoteLockManager(
            redisPool, config.redis.lockPrefix)

    private val s3SourceClient: MinioClient? = if (config.source.sourceType == ResourceType.S3) {
        requireNotNull(config.source.s3).createS3Client()
    } else null

    override val kafkaStorage: KafkaStorage
        get() = when(config.source.sourceType) {
            ResourceType.S3 -> {
                val s3Config = requireNotNull(config.source.s3)
                val minioClient = requireNotNull(s3SourceClient)
                S3KafkaStorage(minioClient, s3Config.bucket, config.paths.temp)
            }
            ResourceType.HDFS -> {
                val hdfsConfig = requireNotNull(config.source.hdfs)
                val fileSystem = FileSystem.get(hdfsConfig.configuration)
                HdfsKafkaStorage(fileSystem)
            }
            else -> throw IllegalStateException("Cannot create kafka storage for type ${config.source.sourceType}")
        }

    @Throws(IOException::class)
    override fun newFileCacheStore(accountant: Accountant): FileCacheStore {
        return FileCacheStore(this, accountant)
    }

    fun start() {
        System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism",
                (config.worker.numThreads - 1).toString())

        try {
            Files.createDirectories(config.paths.temp)
        } catch (ex: IOException) {
            logger.error("Failed to create temporary directory")
            return
        }

        if (config.service.enable) {
            logger.info("Running as a Service with poll interval of {} seconds", config.service.interval)
            logger.info("Press Ctrl+C to exit...")
            val executorService = Executors.newSingleThreadScheduledExecutor()

            executorService.scheduleAtFixedRate(::runRestructure,
                    config.service.interval / 4, config.service.interval, TimeUnit.SECONDS)

            try {
                Thread.sleep(java.lang.Long.MAX_VALUE)
            } catch (e: InterruptedException) {
                logger.info("Interrupted, shutting down...")
                executorService.shutdownNow()
                try {
                    executorService.awaitTermination(java.lang.Long.MAX_VALUE, TimeUnit.SECONDS)
                    Thread.currentThread().interrupt()
                } catch (ex: InterruptedException) {
                    logger.info("Interrupted again...")
                }
            }
        } else {
            runRestructure()
        }
    }

    private fun runRestructure() {
        val timeStart = Instant.now()
        try {
            RadarKafkaRestructure(this).use { restructure ->
                for (input in config.paths.inputs) {
                    logger.info("In:  {}", config.paths.inputs)
                    logger.info("Out: {}", pathFactory.root)
                    restructure.process(input.toString())
                }

                val numberFormat = NumberFormat.getNumberInstance()
                logger.info("Processed {} files and {} records in {}",
                        numberFormat.format(restructure.processedFileCount),
                        numberFormat.format(restructure.processedRecordsCount),
                        timeStart.durationSince().format())
            }
        } catch (ex: IOException) {
            logger.error("Processing failed", ex)
        } catch (e: InterruptedException) {
            logger.error("Processing interrupted")
        } finally {
            // Print timings and reset the timings for the next iteration.
            if (Timer.isEnabled) {
                logger.info("{}", Timer)
                Timer.reset()
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(Application::class.java)
        const val CACHE_SIZE_DEFAULT = 100

        private fun Temporal.durationSince() = Duration.between(this, Instant.now())

        @JvmStatic
        fun main(args: Array<String>) {
            val commandLineArgs = CommandLineArgs()
            val parser = JCommander.newBuilder().addObject(commandLineArgs).build()

            parser.programName = "radar-hdfs-restructure"
            try {
                parser.parse(*args)
            } catch (ex: ParameterException) {
                logger.error(ex.message)
                parser.usage()
                exitProcess(1)
            }

            if (commandLineArgs.help) {
                parser.usage()
                exitProcess(0)
            }

            logger.info("Starting at {}...",
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now()))

            // Enable singleton timer statements in the code.
            Timer.isEnabled = commandLineArgs.enableTimer

            val application = try {
                val restructureConfig = RestructureConfig.load(commandLineArgs.configFile)
                        .run { commandLineArgs.asService?.let { copy(service = service.copy(enable = it)) } ?: this }
                        .run { commandLineArgs.pollInterval?.let { copy(service = service.copy(interval = it)) } ?: this }
                        .run { commandLineArgs.cacheSize?.let { copy(worker = worker.copy(cacheSize = it)) } ?: this }
                        .run { commandLineArgs.numThreads?.let { copy(worker = worker.copy(numThreads = it)) } ?: this }
                        .run { commandLineArgs.maxFilesPerTopic?.let { copy(worker = worker.copy(maxFilesPerTopic = it)) } ?: this }
                        .run { commandLineArgs.tmpDir?.let { copy(paths = paths.copy(temp = Paths.get(it))) } ?: this }
                        .run { commandLineArgs.inputPaths?.let { inputs -> copy(paths = paths.copy(inputs = inputs.map { Paths.get(it) })) } ?: this }
                        .run { commandLineArgs.outputDirectory?.let { copy(paths = paths.copy(output = Paths.get(it))) } ?: this }
                        .run { commandLineArgs.hdfsName?.let { copy(source = source.copy(hdfs = source.hdfs?.copy(name = it) ?: HdfsConfig(name = it))) } ?: this }
                        .run { commandLineArgs.format?.let { copy(format = format.copy(type = it)) } ?: this }
                        .run { commandLineArgs.deduplicate?.let { copy(format = format.copy(deduplication = format.deduplication.copy(enable = it))) } ?: this }
                        .run { commandLineArgs.compression?.let { copy(compression = compression.copy(type = it)) } ?: this }
                        .also { it.validate() }

                Application(restructureConfig)
            } catch (ex: IllegalArgumentException) {
                logger.error("Illegal argument", ex)
                exitProcess(1)
            } catch (ex: IOException) {
                logger.error("Failed to initialize plugins", ex)
                exitProcess(1)
            } catch (e: IllegalStateException) {
                logger.error("Cannot process configuration", e)
                exitProcess(1)
            }

            application.start()
        }
    }
}
