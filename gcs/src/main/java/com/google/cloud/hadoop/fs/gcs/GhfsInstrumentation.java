/*
 * Copyright 2021 Google Inc. All Rights Reserved.
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

import static org.apache.hadoop.fs.statistics.StoreStatisticNames.SUFFIX_FAILURES;

import com.google.common.flogger.GoogleLogger;
import java.io.Closeable;
import java.net.URI;
import java.util.EnumSet;
import java.util.UUID;
import org.apache.hadoop.fs.statistics.*;
import org.apache.hadoop.fs.statistics.impl.IOStatisticsBinding;
import org.apache.hadoop.fs.statistics.impl.IOStatisticsStore;
import org.apache.hadoop.fs.statistics.impl.IOStatisticsStoreBuilder;
import org.apache.hadoop.metrics2.MetricsCollector;
import org.apache.hadoop.metrics2.MetricsSource;
import org.apache.hadoop.metrics2.MetricsSystem;
import org.apache.hadoop.metrics2.impl.MetricsSystemImpl;
import org.apache.hadoop.metrics2.lib.MetricsRegistry;
import org.apache.hadoop.metrics2.lib.MutableCounterLong;
import org.apache.hadoop.metrics2.lib.MutableGaugeLong;
import org.apache.hadoop.metrics2.lib.MutableMetric;

/**
 * Instrumentation of GCS.
 *
 * <p>Counters and metrics are generally addressed in code by their name or {@link GhfsStatistic}
 * key. There <i>may</i> be some Statistics which do not have an entry here. To avoid attempts to
 * access such counters failing, the operations to increment/query metric values are designed to
 * handle lookup failures.
 */
class GhfsInstrumentation
    implements Closeable, MetricsSource, IOStatisticsSource, DurationTrackerFactory {
  private static final String METRICS_SOURCE_BASENAME = "GCSMetrics";

  /**
   * {@value} Currently all gcs metrics are placed in a single "context". Distinct contexts may be
   * used in the future.
   */
  public static final String CONTEXT = "GoogleHadoopFilesystem";

  /** {@value} The name of the gcs-specific metrics system instance used for gcs metrics. */
  public static final String METRICS_SYSTEM_NAME = "google-hadoop-file-system";

  /**
   * {@value} The name of a field added to metrics records that uniquely identifies a specific
   * FileSystem instance.
   */
  public static final String METRIC_TAG_FILESYSTEM_ID = "gcsFilesystemId";

  /**
   * {@value} The name of a field added to metrics records that indicates the hostname portion of
   * the FS URL.
   */
  public static final String METRIC_TAG_BUCKET = "bucket";

  /**
   * metricsSystemLock must be used to synchronize modifications to metricsSystem and the following
   * counters.
   */
  private static final Object METRICS_SYSTEM_LOCK = new Object();

  private static MetricsSystem metricsSystem = null;
  private static int metricsSourceNameCounter = 0;
  private static int metricsSourceActiveCounter = 0;

  private final MetricsRegistry registry =
      new MetricsRegistry("googleHadoopFilesystem").setContext(CONTEXT);

  /**
   * This is the IOStatistics store for the GoogleHadoopFileSystem instance. It is not kept in sync
   * with the rest of the Ghfsinstrumentation. Most inner statistics implementation classes only
   * update this store when it is pushed back, such as as in close().
   */
  private IOStatisticsStore instanceIOStatistics;

  /** Duration Tracker Factory for the Instrumentation */
  private DurationTrackerFactory durationTrackerFactory;

  private String metricsSourceName;

  private static final GoogleLogger LOG = GoogleLogger.forEnclosingClass();

  /**
   * Construct the instrumentation for a filesystem.
   *
   * @param name URI of filesystem.
   */
  public GhfsInstrumentation(URI name) {
    UUID fileSystemInstanceID = UUID.randomUUID();
    registry.tag(
        METRIC_TAG_FILESYSTEM_ID,
        "A unique identifier for the instance",
        fileSystemInstanceID.toString());
    registry.tag(METRIC_TAG_BUCKET, "Hostname from the FS URL", name.getHost());
    IOStatisticsStoreBuilder storeBuilder = IOStatisticsBinding.iostatisticsStore();
    EnumSet.allOf(GhfsStatistic.class).stream()
        .forEach(
            stat -> {
              // declare all counter statistics
              if (stat.getType() == GhfsStatisticTypeEnum.TYPE_COUNTER) {
                counter(stat);
                storeBuilder.withCounters(stat.getSymbol());
                // declare all gauge statistics
              } else if (stat.getType() == GhfsStatisticTypeEnum.TYPE_GAUGE) {
                gauge(stat);
                storeBuilder.withGauges(stat.getSymbol());
                // and durations
              } else if (stat.getType() == GhfsStatisticTypeEnum.TYPE_DURATION) {
                duration(stat);
                storeBuilder.withDurationTracking(stat.getSymbol());
              }
            });

    // register with Hadoop metrics
    registerAsMetricsSource(name);
    // and build the IO Statistics
    instanceIOStatistics = storeBuilder.build();
    // duration track metrics (Success/failure) and IOStatistics.
    durationTrackerFactory =
        IOStatisticsBinding.pairedTrackerFactory(
            instanceIOStatistics, new MetricDurationTrackerFactory());
  }

  public void close() {
    synchronized (METRICS_SYSTEM_LOCK) {
      metricsSystem.unregisterSource(metricsSourceName);
      metricsSourceActiveCounter--;
      int activeSources = metricsSourceActiveCounter;
      if (activeSources == 0) {
        LOG.atInfo().log("Shutting down metrics publisher");
        metricsSystem.publishMetricsNow();
        metricsSystem.shutdown();
        metricsSystem = null;
      }
    }
  }

  /**
   * Get the instance IO Statistics.
   *
   * @return statistics.
   */
  @Override
  public IOStatisticsStore getIOStatistics() {
    return instanceIOStatistics;
  }

  /**
   * Increments a mutable counter and the matching instance IOStatistics counter. No-op if the
   * counter is not defined, or the count == 0.
   *
   * @param op operation
   * @param count increment value
   */
  public void incrementCounter(GhfsStatistic op, long count) {
    if (count == 0) {
      return;
    }
    String name = op.getSymbol();
    incrementMutableCounter(name, count);
    instanceIOStatistics.incrementCounter(name, count);
  }

  /**
   * Register this instance as a metrics source.
   *
   * @param name gs:// URI for the associated FileSystem instance
   */
  private void registerAsMetricsSource(URI name) {
    int number;
    synchronized (METRICS_SYSTEM_LOCK) {
      getMetricsSystem();

      metricsSourceActiveCounter++;
      number = ++metricsSourceNameCounter;
    }
    metricsSourceName = METRICS_SOURCE_BASENAME + number + "-" + name.getHost();
    metricsSystem.register(metricsSourceName, "", this);
  }

  public MetricsSystem getMetricsSystem() {
    synchronized (METRICS_SYSTEM_LOCK) {
      if (metricsSystem == null) {
        metricsSystem = new MetricsSystemImpl();
        metricsSystem.init(METRICS_SYSTEM_NAME);
      }
    }
    return metricsSystem;
  }
  /**
   * Create a counter in the registry.
   *
   * @param name counter name
   * @param desc counter description
   * @return a new counter
   */
  protected final MutableCounterLong counter(String name, String desc) {
    return registry.newCounter(name, desc, 0L);
  }

  /**
   * Create a counter in the registry.
   *
   * @param op statistic to count
   * @return a new counter
   */
  protected final MutableCounterLong counter(GhfsStatistic op) {
    return counter(op.getSymbol(), op.getDescription());
  }

  /**
   * Registering a duration adds the success and failure counters.
   *
   * @param op statistic to track
   */
  protected final void duration(GhfsStatistic op) {
    counter(op.getSymbol(), op.getDescription());
    counter(op.getSymbol() + SUFFIX_FAILURES, op.getDescription());
  }
  /**
   * Create a gauge in the registry.
   *
   * @param name name gauge name
   * @param desc description
   * @return the gauge
   */
  protected final MutableGaugeLong gauge(String name, String desc) {
    return registry.newGauge(name, desc, 0L);
  }

  /**
   * Create a gauge in the registry.
   *
   * @param op statistic to count
   * @return the gauge
   */
  protected final MutableGaugeLong gauge(GhfsStatistic op) {
    return gauge(op.getSymbol(), op.getDescription());
  }

  public MetricsRegistry getRegistry() {
    return registry;
  }

  /**
   * Look up a metric from both the registered set and the lighter weight stream entries.
   *
   * @param name metric name
   * @return the metric or null
   */
  public MutableMetric lookupMetric(String name) {
    MutableMetric metric = getRegistry().get(name);
    return metric;
  }

  /**
   * Lookup a counter by name. Return null if it is not known.
   *
   * @param name counter name
   * @return the counter
   * @throws IllegalStateException if the metric is not a counter
   */
  private MutableCounterLong lookupCounter(String name) {
    MutableMetric metric = lookupMetric(name);
    if (metric == null) {
      return null;
    }
    if (!(metric instanceof MutableCounterLong)) {
      throw new IllegalStateException(
          String.format(
              "Metric %s is not a MutableCounterLong: %s (type: %s)",
              name, metric, metric.getClass()));
    }
    return (MutableCounterLong) metric;
  }

  /**
   * Increments a Mutable counter. No-op if not a positive integer.
   *
   * @param name counter name.
   * @param count increment value
   */
  private void incrementMutableCounter(final String name, final long count) {
    if (count > 0) {
      MutableCounterLong counter = lookupCounter(name);
      if (counter != null) {
        counter.incr(count);
      }
    }
  }

  /**
   * Increments a Mutable counter for request failure
   *
   * @param symbol counter name.
   * @param count increment value
   */
  public void incrementFailureStatistics(String symbol, Long count) {
    incrementMutableCounter(symbol + SUFFIX_FAILURES, count);
  }

  /**
   * A duration tracker which updates a mutable counter with a metric. The metric is updated with
   * the count on start; after a failure the failures count is incremented by one.
   */
  private final class MetricUpdatingDurationTracker implements DurationTracker {
    /** Name of the statistics value to be updated */
    private final String symbol;

    private boolean failed;

    private MetricUpdatingDurationTracker(final String symbol, final long count) {
      this.symbol = symbol;
      incrementMutableCounter(symbol, count);
    }

    @Override
    public void failed() {
      failed = true;
    }

    /** Close: on failure increment any mutable counter of failures. */
    @Override
    public void close() {
      if (failed) {
        incrementMutableCounter(symbol + SUFFIX_FAILURES, 1);
      }
    }
  }

  /**
   * Get the duration tracker factory.
   *
   * @return duration tracking for the instrumentation.
   */
  public DurationTrackerFactory getDurationTrackerFactory() {
    return durationTrackerFactory;
  }

  private final class MetricDurationTrackerFactory implements DurationTrackerFactory {

    @Override
    public DurationTracker trackDuration(final String key, final long count) {
      return new MetricUpdatingDurationTracker(key, count);
    }
  }

  /**
   * The duration tracker updates the metrics with the count and IOStatistics will full duration
   * information.
   *
   * @param key statistic key prefix
   * @param count #of times to increment the matching counter in this operation.
   * @return a duration tracker.
   */
  @Override
  public DurationTracker trackDuration(final String key, final long count) {
    return durationTrackerFactory.trackDuration(key, count);
  }

  @Override
  public void getMetrics(MetricsCollector metricsCollector, boolean b) {}
}
