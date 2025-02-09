/*
 * Copyright 2013 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.fs.gcs;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.cloud.hadoop.gcsio.GoogleCloudStorageFileSystem;
import com.google.cloud.hadoop.gcsio.StorageResourceId;
import com.google.cloud.hadoop.gcsio.UriPaths;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.GoogleLogger;
import java.io.IOException;
import java.net.URI;
import org.apache.hadoop.fs.Path;

/**
 * GoogleHadoopFileSystem is a version of GoogleHadoopFileSystemBase which is rooted in a single
 * bucket at initialization time; in this case, Hadoop paths no longer correspond directly to
 * general GCS paths, and all Hadoop operations going through this FileSystem will never touch any
 * GCS bucket other than the bucket on which this FileSystem is rooted.
 *
 * <p>This implementation sacrifices a small amount of cross-bucket interoperability in favor of
 * more straightforward FileSystem semantics and compatibility with existing Hadoop applications. In
 * particular, it is not subject to bucket-naming constraints, and files are allowed to be placed in
 * root.
 */
public class GoogleHadoopFileSystem extends InstrumentatedGoogleHadoopFileSystem {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /** URI scheme for GoogleHadoopFileSystem */
  public static final String SCHEME = GoogleHadoopFileSystemBase.SCHEME;

  // The bucket the file system is rooted in used for default values of:
  // -- working directory
  // -- user home directories (only for Hadoop purposes).
  private String rootBucket;

  /**
   * Constructs an instance of GoogleHadoopFileSystem; the internal GoogleCloudStorageFileSystem
   * will be set up with config settings when initialize() is called.
   */
  public GoogleHadoopFileSystem() {
    super();
  }

  /**
   * Constructs an instance of GoogleHadoopFileSystem using the provided
   * GoogleCloudStorageFileSystem; initialize() will not re-initialize it.
   */
  @VisibleForTesting
  GoogleHadoopFileSystem(GoogleCloudStorageFileSystem gcsfs) {
    super(gcsfs);
  }

  /** Sets and validates the root bucket. */
  @Override
  @VisibleForTesting
  protected void configureBuckets(GoogleCloudStorageFileSystem gcsFs) throws IOException {
    rootBucket = initUri.getAuthority();
    checkArgument(rootBucket != null, "No bucket specified in GCS URI: %s", initUri);
    // Validate root bucket name
    UriPaths.fromStringPathComponents(
        rootBucket, /* objectName= */ null, /* allowEmptyObjectName= */ true);
    logger.atFiner().log(
        "configureBuckets: GoogleHadoopFileSystem root bucket is '%s'", rootBucket);
  }

  @Override
  protected void checkPath(Path path) {
    logger.atFiner().log("checkPath(path: %s)", path);
    // Validate scheme
    super.checkPath(path);
    URI uri = path.toUri();
    String bucket = uri.getAuthority();
    // Bucketless URIs will be qualified later
    if (bucket == null || bucket.equals(rootBucket)) {
      return;
    }
    throw new IllegalArgumentException(
        String.format(
            "Wrong bucket: %s, in path: %s, expected bucket: %s", bucket, path, rootBucket));
  }

  /** Get the name of the bucket in which file system is rooted. */
  @VisibleForTesting
  String getRootBucketName() {
    return rootBucket;
  }

  /** Override to allow a homedir subpath which sits directly on our FileSystem root. */
  @Override
  protected String getHomeDirectorySubpath() {
    return "user/" + System.getProperty("user.name");
  }

  /**
   * Validates GCS Path belongs to this file system. The bucket must match the root bucket provided
   * at initialization time.
   */
  @Override
  public Path getHadoopPath(URI gcsPath) {
    logger.atFiner().log("getHadoopPath(gcsPath: %s)", gcsPath);

    // Handle root. Delegate to getGcsPath on "gs:/" to resolve the appropriate gs://<bucket> URI.
    if (gcsPath.equals(getGcsPath(getFileSystemRoot()))) {
      return getFileSystemRoot();
    }

    StorageResourceId resourceId = StorageResourceId.fromUriPath(gcsPath, true);

    // Unlike the global-rooted GHFS, gs:// has no meaning in the bucket-rooted world.
    checkArgument(!resourceId.isRoot(), "Missing authority in gcsPath '%s'", gcsPath);
    checkArgument(
        resourceId.getBucketName().equals(rootBucket),
        "Authority of URI '%s' doesn't match root bucket '%s'",
        resourceId.getBucketName(),
        rootBucket);

    Path hadoopPath = new Path(getScheme() + "://" + rootBucket + '/' + resourceId.getObjectName());
    logger.atFiner().log("getHadoopPath(gcsPath: %s): %s", gcsPath, hadoopPath);
    return hadoopPath;
  }

  /**
   * Translates a "gs:/" style hadoopPath (or relative path which is not fully-qualified) into the
   * appropriate GCS path which is compatible with the underlying GcsFs or gsutil.
   */
  @Override
  public URI getGcsPath(Path hadoopPath) {
    logger.atFiner().log("getGcsPath(hadoopPath: %s)", hadoopPath);

    // Convert to fully qualified absolute path; the Path object will callback to get our current
    // workingDirectory as part of fully resolving the path.
    Path resolvedPath = makeQualified(hadoopPath);

    String objectName = resolvedPath.toUri().getPath();
    if (objectName != null && resolvedPath.isAbsolute()) {
      // Strip off leading '/' because GoogleCloudStorageFileSystem.getPath appends it explicitly
      // between bucket and objectName.
      objectName = objectName.substring(1);
    }

    // Construct GCS path uri.
    URI gcsPath =
        UriPaths.fromStringPathComponents(rootBucket, objectName, /* allowEmptyObjectName= */ true);
    logger.atFiner().log("getGcsPath(hadoopPath: %s): %s", hadoopPath, gcsPath);
    return gcsPath;
  }

  /**
   * As the global-rooted FileSystem, our hadoop-path "scheme" is exactly equal to the general GCS
   * scheme.
   */
  @Override
  public String getScheme() {
    return GoogleCloudStorageFileSystem.SCHEME;
  }

  @Override
  public Path getFileSystemRoot() {
    return new Path(getScheme() + "://" + rootBucket + '/');
  }

  /** Gets the default value of working directory. */
  @Override
  public Path getDefaultWorkingDirectory() {
    return getFileSystemRoot();
  }
}
