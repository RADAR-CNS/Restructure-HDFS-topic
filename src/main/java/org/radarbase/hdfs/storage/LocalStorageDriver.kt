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

package org.radarbase.hdfs.storage

import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions

class LocalStorageDriver : StorageDriver {
    private var uid = -1
    private var gid = -1

    override fun init(properties: Map<String, String>) {
        uid = properties["localUid"]?.toIntOrNull() ?: -1
        gid = properties["localGid"]?.toIntOrNull() ?: -1
    }

    @Throws(IOException::class)
    override fun status(path: Path): StorageDriver.PathStatus? {
        return if (Files.exists(path)) {
            StorageDriver.PathStatus(Files.size(path))
        } else {
            null
        }
    }

    @Throws(IOException::class)
    override fun newInputStream(path: Path): InputStream = Files.newInputStream(path)

    @Throws(IOException::class)
    override fun move(oldPath: Path, newPath: Path) {
        try {
            Files.move(oldPath, newPath, REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (ex: AtomicMoveNotSupportedException) {
            Files.move(oldPath, newPath, REPLACE_EXISTING)
        }
    }

    @Throws(IOException::class)
    override fun store(localPath: Path, newPath: Path) {
        if (uid >= 0) {
            Files.setAttribute(localPath, "unix:uid", uid)
        }
        if (gid >= 0) {
            Files.setAttribute(localPath, "unix:gid", gid)
        }
        Files.setPosixFilePermissions(localPath, PosixFilePermissions.fromString("rw-r--r--"))
        move(localPath, newPath)
    }

    @Throws(IOException::class)
    override fun delete(path: Path) = Files.delete(path)
}
