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

import android.content.Context;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.net.cronet.testing.CronetEngineTestAppRule;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okio.Buffer;
import okio.Sink;
import okio.Timeout;
import org.junit.Before;
import org.junit.Ignore;
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
 *
 * <p>This benchmark must be run on API 33+ devices because it uses {@link Thread#onSpinWait()}.
 */
@RunWith(Parameterized.class)
public class LargeReadBenchmarkTest {

  @Rule public final MockWebServer server = new MockWebServer();

  @Rule public final CronetEngineTestAppRule cronetEngineRule = new CronetEngineTestAppRule();

  @Parameter(0)
  public String iterationName;

  @Parameter(1)
  public int responseSizeMb;

  @Parameter(2)
  public Protocol protocol;

  /**
   * Every time OkHttp completes a read, we will spin loop for this amount of time. This can be used
   * to simulate work being done on the OkHttp caller threads in between reads.
   */
  @Parameter(3)
  public Duration workDuration;

  @Parameters(name = "{0}")
  public static Collection<Object[]> parameters() {
    final int runs = 2;
    final int response1mb = 1;
    final int response10mb = 10;
    final int response50mb = 50;
    final Duration noWork = Duration.ZERO;
    final Duration work20us = Duration.of(20, ChronoUnit.MICROS);
    final Duration work100us = Duration.of(100, ChronoUnit.MICROS);
    final Duration work200us = Duration.of(200, ChronoUnit.MICROS);

    List<Object[]> params = new ArrayList<>();
    int caseId = 1;

    params.addAll(params(caseId++, runs, response1mb, Protocol.HTTP_1_1, noWork));
    params.addAll(params(caseId++, runs, response10mb, Protocol.HTTP_1_1, noWork));
    params.addAll(params(caseId++, runs, response50mb, Protocol.HTTP_1_1, noWork));
    params.addAll(params(caseId++, runs, response50mb, Protocol.HTTP_1_1, work20us));
    params.addAll(params(caseId++, runs, response50mb, Protocol.HTTP_1_1, work100us));
    params.addAll(params(caseId++, runs, response50mb, Protocol.HTTP_1_1, work200us));
    params.addAll(params(caseId++, runs, response1mb, Protocol.HTTP_2, noWork));
    params.addAll(params(caseId++, runs, response10mb, Protocol.HTTP_2, noWork));
    params.addAll(params(caseId++, runs, response50mb, Protocol.HTTP_2, noWork));
    params.addAll(params(caseId++, runs, response50mb, Protocol.HTTP_2, work20us));
    params.addAll(params(caseId++, runs, response50mb, Protocol.HTTP_2, work100us));
    params.addAll(params(caseId++, runs, response50mb, Protocol.HTTP_2, work200us));

    return params;
  }

  private static Collection<Object[]> params(
      int caseId, int runs, int responseSizeMb, Protocol protocol, Duration workDuration) {
    List<Object[]> parameters = new ArrayList<>();
    for (int i = 1; i <= runs; i++) {
      String testName = String.format("TestCase#%02d - Run#%02d", caseId, i);
      parameters.add(new Object[] {testName, responseSizeMb, protocol, workDuration});
    }
    return parameters;
  }

  /**
   * Enables HTTPS on the test server. Separately, the test certificate is also specified in the
   * Network Security Config of the APK under test so that the client will trust it.
   */
  @Before
  public void setUpHttps() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getContext();

    final X509Certificate certificate;
    try (InputStream certIs = context.getResources().openRawResource(R.raw.localhost_cert)) {
      CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
      certificate = (X509Certificate) certificateFactory.generateCertificate(certIs);
    }

    final PrivateKey privateKey;
    try (InputStream keyIs = context.getResources().openRawResource(R.raw.localhost_key)) {
      PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyIs.readAllBytes());
      privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    }

    KeyPair keyPair = new KeyPair(certificate.getPublicKey(), privateKey);
    HeldCertificate heldCertificate = new HeldCertificate(keyPair, certificate);
    HandshakeCertificates handshakeCertificates =
        new HandshakeCertificates.Builder().heldCertificate(heldCertificate).build();

    if (protocol == Protocol.HTTP_1_1) {
      server.setProtocols(Arrays.asList(Protocol.HTTP_1_1));
    } else {
      // MockWebServer requires the list to contain Protocol.HTTP_1_1.
      server.setProtocols(Arrays.asList(protocol, Protocol.HTTP_1_1));
    }

    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
  }

  @Test
  @Ignore("Ignoring since the results are the same as for both call factory and interceptor.")
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
    BlackholeSinkWithWork sink = new BlackholeSinkWithWork();

    long readStartNs = System.nanoTime();
    long bytesRead = response.body().source().readAll(sink);
    long readEndNs = System.nanoTime();

    logResults(testName, sink.readsCount, response.protocol(), readStartNs, readEndNs);

    assertThat(response.code()).isEqualTo(200);
    assertThat(bytesRead).isEqualTo(responseSizeBytes);
    assertThat(response.protocol()).isEqualTo(protocol);
    assertThat(server.takeRequest().getHandshake()).isNotNull();
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

  private void logResults(
      String testName, int readsCount, Protocol protocol, long readStartNs, long readEndNs) {
    long readDurationMs = (readEndNs - readStartNs) / 1_000_000L;

    String workDurationString =
        workDuration.isPositive() ? String.format("%dus", workDuration.toNanos() / 1_000L) : "no";

    Log.e(
        "LargeReadBenchmarkTest",
        String.format(
            Locale.US,
            "%s - %s - %dMB response, %d reads, %s, %s work, read duration: %dms",
            testName,
            iterationName,
            responseSizeMb,
            readsCount,
            protocol,
            workDurationString,
            readDurationMs));
  }

  /**
   * A sink that does CPU intensive work on each read, which is intended to simulate response
   * parsing by a consumer application. On each read, it first discards all bytes written to it and
   * then occupies the CPU for {@link #workDuration}.
   */
  private final class BlackholeSinkWithWork implements Sink {

    private int readsCount = 0;

    @Override
    public void write(Buffer source, long byteCount) throws IOException {
      source.skip(byteCount);
      readsCount++;
      if (workDuration.isPositive()) {
        doWork();
      }
    }

    private void doWork() {
      long startNs = System.nanoTime();
      while (System.nanoTime() - startNs < workDuration.toNanos()) {
        Thread.onSpinWait();
      }
    }

    @Override
    public void flush() throws IOException {
      // Do nothing
    }

    @Override
    public Timeout timeout() {
      return Timeout.NONE;
    }

    @Override
    public void close() throws IOException {
      // Do nothing
    }
  }
}
