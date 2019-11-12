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

package org.radarbase.hdfs

import java.io.IOException

interface Plugin {

    /**
     * Initialize plugin. Throws IllegalArgumentException if required properties are not provided
     * or if they are of the wrong format.
     */
    @Throws(IOException::class)
    fun init(properties: Map<String, String>) {
        // do nothing
    }
}
