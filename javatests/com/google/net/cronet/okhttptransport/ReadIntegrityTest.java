/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.net.cronet.okhttptransport;

import static com.google.common.truth.Truth.assertThat;

import com.google.net.cronet.testing.CronetEngineTestAppRule;
import java.util.Arrays;
import java.util.Collection;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * This test verifies that the bridge correctly reads various sizes of the response body from
 * Cronet, without any data corruption.
 */
@RunWith(Parameterized.class)
public class ReadIntegrityTest {

  /** See {@link OkHttpBridgeRequestCallback#CRONET_BYTE_BUFFER_CAPACITY}. */
  private static final int CRONET_BYTE_BUFFER_CAPACITY = 32 * 1024;

  /**
   * This is the segment size defined by the internal okio.Segment.SIZE. This is the size OkIo uses
   * to read chunks of the response body from the bridge.
   */
  private static final int OKIO_SEGMENT_SIZE = 8 * 1024;

  @Rule public final MockWebServer server = new MockWebServer();

  @Rule public final CronetEngineTestAppRule cronetEngineRule = new CronetEngineTestAppRule();

  /** The size of the response body to read. */
  @Parameter(0)
  public int responseSizeBytes;

  @Parameters(name = "{0}")
  public static Collection<Object[]> parameters() {
    return Arrays.asList(
        new Object[][] {
          {0},
          {1},
          {2},
          {500},
          {1000},
          {2000},
          {OKIO_SEGMENT_SIZE - 1},
          {OKIO_SEGMENT_SIZE},
          {OKIO_SEGMENT_SIZE + 1},
          {OKIO_SEGMENT_SIZE * 2 - 1},
          {OKIO_SEGMENT_SIZE * 2},
          {OKIO_SEGMENT_SIZE * 2 + 1},
          {OKIO_SEGMENT_SIZE * 3 - 1},
          {OKIO_SEGMENT_SIZE * 3},
          {OKIO_SEGMENT_SIZE * 3 + 1},
          {CRONET_BYTE_BUFFER_CAPACITY - 1},
          {CRONET_BYTE_BUFFER_CAPACITY},
          {CRONET_BYTE_BUFFER_CAPACITY + 1},
          {CRONET_BYTE_BUFFER_CAPACITY + OKIO_SEGMENT_SIZE - 1},
          {CRONET_BYTE_BUFFER_CAPACITY + OKIO_SEGMENT_SIZE},
          {CRONET_BYTE_BUFFER_CAPACITY + OKIO_SEGMENT_SIZE + 1},
          {CRONET_BYTE_BUFFER_CAPACITY + OKIO_SEGMENT_SIZE * 2 - 1},
          {CRONET_BYTE_BUFFER_CAPACITY + OKIO_SEGMENT_SIZE * 2},
          {CRONET_BYTE_BUFFER_CAPACITY + OKIO_SEGMENT_SIZE * 2 + 1},
          {CRONET_BYTE_BUFFER_CAPACITY + OKIO_SEGMENT_SIZE * 3 - 1},
          {CRONET_BYTE_BUFFER_CAPACITY + OKIO_SEGMENT_SIZE * 3},
          {CRONET_BYTE_BUFFER_CAPACITY + OKIO_SEGMENT_SIZE * 3 + 1},
          {CRONET_BYTE_BUFFER_CAPACITY * 2 - 1},
          {CRONET_BYTE_BUFFER_CAPACITY * 2},
          {CRONET_BYTE_BUFFER_CAPACITY * 2 + 1},
          {CRONET_BYTE_BUFFER_CAPACITY * 2 + OKIO_SEGMENT_SIZE - 1},
          {CRONET_BYTE_BUFFER_CAPACITY * 2 + OKIO_SEGMENT_SIZE},
          {CRONET_BYTE_BUFFER_CAPACITY * 2 + OKIO_SEGMENT_SIZE + 1},
          {CRONET_BYTE_BUFFER_CAPACITY * 2 + OKIO_SEGMENT_SIZE * 2 - 1},
          {CRONET_BYTE_BUFFER_CAPACITY * 2 + OKIO_SEGMENT_SIZE * 2},
          {CRONET_BYTE_BUFFER_CAPACITY * 2 + OKIO_SEGMENT_SIZE * 2 + 1},
          {CRONET_BYTE_BUFFER_CAPACITY * 2 + OKIO_SEGMENT_SIZE * 3 - 1},
          {CRONET_BYTE_BUFFER_CAPACITY * 2 + OKIO_SEGMENT_SIZE * 3},
          {CRONET_BYTE_BUFFER_CAPACITY * 2 + OKIO_SEGMENT_SIZE * 3 + 1},
        });
  }

  @Test
  public void testReadIntegrity() throws Exception {
    Call.Factory callFactory =
        new OkHttpClient.Builder()
            .addInterceptor(CronetInterceptor.newBuilder(cronetEngineRule.getEngine()).build())
            .build();

    Buffer expectedResponseBody = TestUtils.generateRandomBytes(responseSizeBytes);

    server.enqueue(
        new MockResponse()
            .setBody(expectedResponseBody)
            .clearHeaders()
            .addHeader("content-length", String.valueOf(responseSizeBytes)));

    Request request = new Request.Builder().url(server.url("/")).build();
    Call call = callFactory.newCall(request);
    Response response = call.execute();

    Buffer actualResponseBody = new Buffer();
    long actualBytesRead = response.body().source().readAll(actualResponseBody);

    assertThat(response.code()).isEqualTo(200);
    assertThat(actualBytesRead).isEqualTo(responseSizeBytes);
    assertThat(actualResponseBody).isEqualTo(expectedResponseBody);
  }
}
