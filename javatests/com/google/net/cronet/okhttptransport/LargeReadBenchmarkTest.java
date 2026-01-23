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

import android.util.Log;
import com.google.net.cronet.testing.CronetEngineTestAppRule;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Random;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import okio.Okio;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * A benchmark test that measures the time it takes the bridge to read a large response body from a
 * local test server.
 *
 * <p>To get meaningful results, this benchmark should be run on a real device, with <a
 * href="https://developer.android.com/games/optimize/adpf/fixed-performance-mode">Fixed Performance
 * Mode</a> enabled, e.g. {@code adb shell cmd power set-fixed-performance-mode-enabled true}.
 * Validate it's enabled by checking that CPU clocks are locked. If they are not, try rebooting the
 * device, and enabling the mode again. Check if device is overheating, e.g. {@code adb shell
 * dumpsys thermalservice}. It's also recommended to stop all background work, e.g. {@code adb shell
 * am kill-all}, enable Airplane mode, and keep the screen on. This produces more stable results
 * with lower variance, which are capable of indicating smaller performance changes with higher
 * confidence.
 *
 * <p>You may want to increase the response size (e.g. 100-250mb) when running on a local device.
 * Larger response sizes tend to be more sensitive to the performance changes. This benchmark seems
 * to have a fixed overhead, so increasing the response size helps to reduce its influence.
 *
 * <p>The benchmark results (read duration in milliseconds) are output to the Logcat, one line for
 * each iteration. The benchmark is run with 5 warmup iterations and 10 test iterations.
 *
 * <p>At the time of writing, this benchmark was used to measure the performance impact of changing
 * bridge buffer when reading the response body from Cronet, see <a
 * href="https://github.com/google/cronet-transport-for-okhttp/issues/47">google/cronet-transport-for-okhttp#47</a>.
 */
@RunWith(Parameterized.class)
public class LargeReadBenchmarkTest {

  @Rule public final MockWebServer server = new MockWebServer();

  @Rule public final CronetEngineTestAppRule cronetEngineRule = new CronetEngineTestAppRule();

  @Parameter(0)
  public String iterationName;

  @Parameter(1)
  public int responseSizeMb;

  @Parameters(name = "{0}")
  public static Collection<Object[]> parameters() {
    return Arrays.asList(
        new Object[][] {
          {" WarmupIteration#01", 10},
          {" WarmupIteration#02", 10},
          {" WarmupIteration#03", 10},
          {"Iteration#01", 50},
          {"Iteration#02", 50},
          {"Iteration#03", 50},
          {"Iteration#04", 50},
          {"Iteration#05", 50},
          {"Iteration#06", 50},
          {"Iteration#07", 50},
          {"Iteration#08", 50},
          {"Iteration#09", 50},
          {"Iteration#10", 50},
        });
  }

  @Test
  public void testCronetCallFactory() throws Exception {
    Call.Factory callFactory = CronetCallFactory.newBuilder(cronetEngineRule.getEngine()).build();
    runAndMeasure("CronetCallFactory", callFactory);
  }

  @Test
  public void testCronetInterceptor() throws Exception {
    Call.Factory callFactory =
        new OkHttpClient.Builder()
            .addInterceptor(CronetInterceptor.newBuilder(cronetEngineRule.getEngine()).build())
            .build();
    runAndMeasure("CronetInterceptor", callFactory);
  }

  @Test
  public void testOkHttpClient() throws Exception {
    Call.Factory callFactory = new OkHttpClient.Builder().build();
    runAndMeasure("OkHttpClient", callFactory);
  }

  private void runAndMeasure(String testName, Call.Factory callFactory) throws Exception {
    int responseSizeBytes = responseSizeMb * 1024 * 1024;
    Buffer responseBody = generateRandomBytes(responseSizeBytes);

    server.enqueue(
        new MockResponse()
            .setBody(responseBody)
            .clearHeaders()
            .addHeader("content-length", String.valueOf(responseSizeBytes)));

    Request request = new Request.Builder().url(server.url("/")).build();
    Call call = callFactory.newCall(request);
    Response response = call.execute();

    long readStartNs = System.nanoTime();
    long bytesRead = response.body().source().readAll(Okio.blackhole());
    long readEndNs = System.nanoTime();

    logResults(testName, bytesRead, readStartNs, readEndNs);

    assertThat(response.code()).isEqualTo(200);
    assertThat(bytesRead).isEqualTo(responseSizeBytes);
  }

  private Buffer generateRandomBytes(int byteCount) {
    Buffer buffer = new Buffer();
    byte[] bytes = new byte[8192];
    Random random = new Random();

    while (buffer.size() < byteCount) {
      random.nextBytes(bytes);
      int remaining = byteCount - (int) buffer.size();
      int toWrite = Math.min(bytes.length, remaining);
      buffer.write(bytes, 0, toWrite);
    }

    assertThat(buffer.size()).isEqualTo(byteCount);

    return buffer;
  }

  private void logResults(String testName, long bytesRead, long readStartNs, long readEndNs) {
    long readDurationMs = (readEndNs - readStartNs) / 1_000_000L;
    Log.e(
        "LargeReadBenchmarkTest",
        String.format(
            Locale.US,
            "%s - %s - bytes read: %d, read duration: %dms",
            testName,
            iterationName,
            bytesRead,
            readDurationMs));
  }
}
