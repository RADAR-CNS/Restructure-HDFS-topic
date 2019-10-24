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

package org.radarbase.hdfs.util.commandline

import org.radarbase.hdfs.Application.Companion.CACHE_SIZE_DEFAULT

import com.beust.jcommander.DynamicParameter
import com.beust.jcommander.Parameter
import com.beust.jcommander.ParameterException
import com.beust.jcommander.converters.BaseConverter
import com.beust.jcommander.validators.PositiveInteger
import java.util.ArrayList
import java.util.HashMap
import java.util.function.Supplier
import org.radarbase.hdfs.Plugin

class CommandLineArgs {
    @Parameter(description = "<input_path_1> [<input_path_2> ...]", variableArity = true, required = true)
    var inputPaths: List<String> = ArrayList()

    @Parameter(names = ["-f", "--format"], description = "Format to use when converting the files. JSON and CSV are available by default.")
    var format = "csv"

    @Parameter(names = ["-c", "--compression"], description = "Compression to use when converting the files. Gzip is available by default.")
    var compression = "none"

    // Default set to false because causes loss of records from Biovotion data. https://github.com/RADAR-base/Restructure-HDFS-topic/issues/16
    @Parameter(names = ["-d", "--deduplicate"], description = "Boolean to define if to use deduplication or not.")
    var deduplicate = false

    @Parameter(names = ["-n", "--nameservice"], description = "The HDFS name services to connect to. Eg - '<HOST>' for single configurations or <CLUSTER_ID> for high availability web services.", required = true, validateWith = [NonEmptyValidator::class])
    lateinit var hdfsName: String

    @Parameter(names = ["--namenode-1"], description = "High availability HDFS first name node hostname.", validateWith = [NonEmptyValidator::class])
    var hdfsUri1: String? = null

    @Parameter(names = ["--namenode-2"], description = "High availability HDFS second name node hostname. Eg - '<HOST>'.", validateWith = [NonEmptyValidator::class])
    var hdfsUri2: String? = null

    @Parameter(names = ["--namenode-ha"], description = "High availability HDFS name node names. Eg - 'nn1,nn2'.", validateWith = [NonEmptyValidator::class])
    var hdfsHa: String? = null

    @Parameter(names = ["-o", "--output-directory"], description = "The output folder where the files are to be extracted.", required = true, validateWith = [NonEmptyValidator::class])
    lateinit var outputDirectory: String

    @Parameter(names = ["-h", "--help"], help = true, description = "Display the usage of the program with available options.")
    var help: Boolean = false

    @Parameter(names = ["--path-factory"], description = "Factory to create path names with", converter = InstantiatePluginConverter::class)
    var pathFactory: Plugin? = null

    @Parameter(names = ["--storage-driver"], description = "Storage driver to use for storing data", converter = InstantiatePluginConverter::class)
    var storageDriver: Plugin? = null

    @Parameter(names = ["--format-factory"], description = "Format factory class to use for storing data", converter = InstantiatePluginConverter::class)
    var formatFactory: Plugin? = null

    @Parameter(names = ["--compression-factory"], description = "Compression factory class to use for compressing/decompressing data", converter = InstantiatePluginConverter::class)
    var compressionFactory: Plugin? = null

    @DynamicParameter(names = ["-p", "--property"], description = "Properties used by custom plugins.")
    var properties: Map<String, String> = HashMap()

    @Parameter(names = ["-t", "--num-threads"], description = "Number of threads to use for processing", validateWith = [PositiveInteger::class])
    var numThreads = 1

    @Parameter(names = ["--timer"], description = "Enable timers")
    var enableTimer = false

    @Parameter(names = ["--tmp-dir"], description = "Temporary staging directory")
    var tmpDir: String? = null

    @Parameter(names = ["-s", "--cache-size"], description = "Number of files to keep in cache in a single thread.", validateWith = [PositiveInteger::class])
    var cacheSize = CACHE_SIZE_DEFAULT

    @Parameter(names = ["--max-files-per-topic"], description = "Maximum number of records to process, per topic. Set below 1 to disable this option.")
    var maxFilesPerTopic = 0

    @Parameter(names = ["--exclude-topic"], description = "Topic to exclude when processing the records. Can be supplied more than once to exclude multiple topics.")
    var excludeTopics: List<String> = ArrayList()

    @Parameter(names = ["-S", "--service"], description = "Run the output generation as a service")
    var asService = false

    @Parameter(names = ["-i", "--interval"], description = "Polling interval when running as a service (seconds)")
    var pollInterval = 3600

    @Parameter(names = ["--lock-directory"], description = "HDFS lock directory")
    var lockDirectory = "/logs/org.radarbase.hdfs/lock"

    class InstantiatePluginConverter(optionName: String) : BaseConverter<Plugin>(optionName) {
        override fun convert(value: String): Plugin {
            try {
                val cls = Class.forName(value)
                return cls.getConstructor().newInstance() as Plugin
            } catch (ex: ReflectiveOperationException) {
                throw ParameterException("Cannot convert $value to Plugin instance", ex)
            } catch (ex: ClassCastException) {
                throw ParameterException("Cannot convert $value to Plugin instance", ex)
            }
        }
    }
}