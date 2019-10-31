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

import com.beust.jcommander.IParameterValidator
import com.beust.jcommander.ParameterException

class NonEmptyValidator : IParameterValidator {
    override fun validate(name: String, value: String?) {
        if (value?.isEmpty() == true) {
            throw ParameterException("Parameter " + name + " should be supplied. "
                    + "It cannot be empty or null. (found " + value + ")."
                    + "Please run with --help or -h for more information.")
        }
    }
}
