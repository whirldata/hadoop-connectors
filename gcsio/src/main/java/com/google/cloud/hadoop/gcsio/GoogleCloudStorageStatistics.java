package com.google.cloud.hadoop.gcsio;

import java.util.HashMap;

import static com.google.cloud.hadoop.gcsio.GoogleCloudStorageStatisticsType.TYPE_DURATION;

public enum GoogleCloudStorageStatistics {
   ACTION_HTTP_GET_REQUEST,
   ACTION_HTTP_GET_REQUEST_FAILURES,
   ACTION_HTTP_HEAD_REQUEST,
   ACTION_HTTP_HEAD_REQUEST_FAILURES;
}
