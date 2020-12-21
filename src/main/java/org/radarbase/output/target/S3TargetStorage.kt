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

package org.radarbase.output.target

import io.minio.*
import io.minio.errors.ErrorResponseException
import org.radarbase.output.config.S3Config
import org.radarbase.output.util.bucketBuild
import org.radarbase.output.util.objectBuild
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

class S3TargetStorage(config: S3Config) : TargetStorage {
    private val bucket: String = config.bucket
    private val s3Client: MinioClient

    init {
        s3Client = try {
            config.createS3Client()
        } catch (ex: IllegalArgumentException) {
            logger.warn("Invalid S3 configuration", ex)
            throw ex
        }

        logger.info("Object storage configured with endpoint {} in bucket {}",
                config.endpoint, config.bucket)

        // Check if the bucket already exists.
        val isExist: Boolean = s3Client.bucketExists(BucketExistsArgs.Builder().bucketBuild(bucket))
        if (isExist) {
            logger.info("Bucket $bucket already exists.")
        } else {
            s3Client.makeBucket(MakeBucketArgs.Builder().bucketBuild(bucket))
            logger.info("Bucket $bucket was created.")
        }
    }

    override fun status(path: Path): TargetStorage.PathStatus? {
        return try {
            s3Client.statObject(StatObjectArgs.Builder().objectBuild(bucket, path))
                    .let { TargetStorage.PathStatus(it.size()) }
        } catch (ex: ErrorResponseException) {
            if (ex.errorResponse().code() == "NoSuchKey" || ex.errorResponse().code() == "ResourceNotFound") {
                null
            } else {
                throw ex
            }
        }
    }

    @Throws(IOException::class)
    override fun newInputStream(path: Path): InputStream = s3Client.getObject(
            GetObjectArgs.Builder().objectBuild(bucket, path))

    @Throws(IOException::class)
    override fun move(oldPath: Path, newPath: Path) {
        s3Client.copyObject(CopyObjectArgs.Builder().objectBuild(bucket, newPath) {
            source(CopySource.Builder().objectBuild(bucket, oldPath))
        })
        delete(oldPath)
    }

    @Throws(IOException::class)
    override fun store(localPath: Path, newPath: Path) {
        s3Client.uploadObject(UploadObjectArgs.Builder().objectBuild(bucket, newPath) {
            filename(localPath.toAbsolutePath().toString())
        })
        Files.delete(localPath)
    }

    @Throws(IOException::class)
    override fun delete(path: Path) {
        s3Client.removeObject(RemoveObjectArgs.Builder().objectBuild(bucket, path))
    }

    override fun createDirectories(directory: Path) {
        // noop
    }

    companion object {
        private val logger = LoggerFactory.getLogger(S3TargetStorage::class.java)
    }
}
