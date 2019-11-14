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

package org.radarbase.hdfs.format

import java.io.Closeable
import java.io.Flushable
import java.io.IOException
import org.apache.avro.generic.GenericRecord

/** Converts a GenericRecord to Java primitives or writes it to file.  */
interface RecordConverter : Flushable, Closeable {
    @Throws(IOException::class)
    fun writeRecord(record: GenericRecord): Boolean

    fun convertRecord(record: GenericRecord): Map<String, Any?>
}
