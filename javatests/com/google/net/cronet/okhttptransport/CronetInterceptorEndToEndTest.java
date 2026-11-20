/*
 * Copyright 2022 Google LLC
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
import static java.lang.Math.min;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.google.net.cronet.testing.CronetEngineTestAppRule;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okio.BufferedSink;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CronetInterceptorEndToEndTest {

  private static final MediaType UTF_8_TEXT = MediaType.parse("text/plain; charset=UTF-8");

  // 2 MiB
  private static final byte[] BODY_CONTENT =
      Strings.repeat("*Long body part, 32 bytes each* ", 2048 * 1024 / 32)
          .getBytes(UTF_8);

  private static final Dispatcher ECHO_RESPONSE_DISPATCHER =
      new Dispatcher() {
        @Override
        public MockResponse dispatch(RecordedRequest request) {
          MockResponse mockResponse = new MockResponse();

          mockResponse
              .setResponseCode(200)
              .addHeader("x-request-url", request.getRequestUrl().toString())
              .addHeader("x-request-http-method", request.getMethod())
              .setBody(request.getBody());

          for (int i = 0; i < request.getHeaders().size(); i++) {
            mockResponse.addHeader(
                "x-request-" + request.getHeaders().name(i), request.getHeaders().value(i));
          }
          return mockResponse;
        }
      };

  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final CronetEngineTestAppRule engineRule = new CronetEngineTestAppRule();

  private CronetInterceptor underTest;

  @Before
  public void setUp() {
    underTest = CronetInterceptor.newBuilder(engineRule.getEngine()).build();
  }

  @Test
  public void testEndToEnd_post() throws Exception {
    server.setDispatcher(ECHO_RESPONSE_DISPATCHER);

    assertEchoRequest(
        new Request.Builder()
            .post(RequestBody.create(UTF_8_TEXT, BODY_CONTENT))
            .url(server.url("/google"))
            .build(),
        BODY_CONTENT,
        underTest);
  }

  @Test
  public void testEndToEnd_get() throws Exception {
    server.setDispatcher(ECHO_RESPONSE_DISPATCHER);

    assertEchoRequest(
        new Request.Builder().get().url(server.url("/google")).build(), new byte[0], underTest);
  }

  @Test
  public void testCancel() throws Exception {
    // Delay the server response so we have enough time to cancel.
    server.setDispatcher(withBodyDelay(ECHO_RESPONSE_DISPATCHER, 10, SECONDS));

    OkHttpClient client = new OkHttpClient.Builder().addInterceptor(underTest).build();

    Request request =
        new Request.Builder()
            .post(RequestBody.create(UTF_8_TEXT, BODY_CONTENT))
            .url(server.url("/google"))
            .build();

    Call call = client.newCall(request);

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> callbackThrowable = new AtomicReference<>();

    call.enqueue(
        new Callback() {
          @Override
          public void onFailure(Call call, IOException e) {
            callbackThrowable.set(e);
            latch.countDown();
          }

          @Override
          public void onResponse(Call call, Response response) {
            callbackThrowable.set(new AssertionError("The call shouldn't have succeeded!"));
            latch.countDown();
          }
        });

    call.cancel();
    latch.await();

    assertThat(callbackThrowable.get()).isInstanceOf(IOException.class);
    assertThat(callbackThrowable.get()).hasMessageThat().containsMatch("(?i)cancell?ed");
  }

  @Test
  public void testRedirectStrategy_noRedirects() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(303)
            .addHeader("foo", "bar")
            .addHeader("Location", "/greatplace"));

    underTest =
        CronetInterceptor.newBuilder(engineRule.getEngine())
            .setRedirectStrategy(RedirectStrategy.withoutRedirects())
            .build();

    OkHttpClient client = new OkHttpClient.Builder().addInterceptor(underTest).build();

    Request request =
        new Request.Builder()
            .post(RequestBody.create(UTF_8_TEXT, BODY_CONTENT))
            .url(server.url("/google"))
            .build();

    try (Response response = client.newCall(request).execute()) {
      assertThat(response.protocol()).isEqualTo(Protocol.HTTP_1_0);
      assertThat(response.code()).isEqualTo(303);

      assertThat(response.headers().toMultimap())
          .containsExactly(
              "foo", Arrays.asList("bar"),
              "content-length", Arrays.asList("0"),
              "location", Arrays.asList("/greatplace"));

      assertThat(response.body().string()).isEmpty();
    }
  }

  @Test
  public void testRedirectStrategy_withRedirects_success() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(301)
            .addHeader("Location: /b")
            .addHeader("Test", "Redirect from /a to /b")
            .setBody("/a has moved!"));
    server.enqueue(
        new MockResponse()
            .setResponseCode(302)
            .addHeader("Location: /c")
            .addHeader("Test", "Redirect from /b to /c")
            .setBody("/b has moved!"));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("foo", "bar")
            .setBody("Finally reached /c!"));

    OkHttpClient client = new OkHttpClient.Builder().addInterceptor(underTest).build();

    Request request =
        new Request.Builder()
            .post(RequestBody.create(UTF_8_TEXT, BODY_CONTENT))
            .url(server.url("/google"))
            .build();

    try (Response response = client.newCall(request).execute()) {
      assertThat(response.protocol()).isEqualTo(Protocol.HTTP_1_0);
      assertThat(response.code()).isEqualTo(200);

      assertThat(response.headers().toMultimap())
          .containsExactly(
              "content-length", Arrays.asList("19"),
              "foo", Arrays.asList("bar"));

      assertThat(response.body().string()).isEqualTo("Finally reached /c!");

      assertThat(response.request().url().encodedPath()).isEqualTo("/c");
      assertThat(response.priorResponse().request().url().encodedPath()).isEqualTo("/b");
      assertThat(response.priorResponse().priorResponse().request().url().encodedPath())
          .isEqualTo("/google");
    }
  }

  @Test
  public void testRedirectStrategy_withRedirects_cycle() throws Exception {
    for (int i = 0; i < 9; i++) {
      server.enqueue(
          new MockResponse()
              .setResponseCode(301)
              .addHeader("Location: /b")
              .addHeader("Test", "Redirect from /a to /b")
              .setBody("/a has moved!"));
      server.enqueue(
          new MockResponse()
              .setResponseCode(302)
              .addHeader("Location: /a")
              .addHeader("Test", "Redirect from /b to /a")
              .setBody("/b has moved!"));
    }

    OkHttpClient client = new OkHttpClient.Builder().addInterceptor(underTest).build();

    Request request =
        new Request.Builder()
            .post(RequestBody.create(UTF_8_TEXT, BODY_CONTENT))
            .url(server.url("/a"))
            .build();

    IOException e = assertThrows(IOException.class, () -> client.newCall(request).execute());

    assertThat(e).hasMessageThat().contains("Too many follow-up requests: 17");
  }

  @Test
  public void testRequestBodyCallbacks_runOnCallerThread() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location: /b"));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("Reached /b!"));

    Thread callerThread = Thread.currentThread();
    List<Thread> contentTypeThreads = new ArrayList<>();
    List<Thread> contentLengthThreads = new ArrayList<>();
    List<Thread> isOneShotThreads = new ArrayList<>();
    List<Thread> writeToThreads = new ArrayList<>();
    RequestBody delegateBody = RequestBody.create(UTF_8_TEXT, "Request Body");

    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            contentTypeThreads.add(Thread.currentThread());
            return delegateBody.contentType();
          }

          @Override
          public long contentLength() throws IOException {
            contentLengthThreads.add(Thread.currentThread());
            return delegateBody.contentLength();
          }

          @Override
          public boolean isOneShot() {
            isOneShotThreads.add(Thread.currentThread());
            return false;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            writeToThreads.add(Thread.currentThread());
            delegateBody.writeTo(sink);
          }
        };

    OkHttpClient client = new OkHttpClient.Builder().addInterceptor(underTest).build();
    Request request = new Request.Builder().post(requestBody).url(server.url("/a")).build();

    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(200);
      assertThat(response.body().string()).isEqualTo("Reached /b!");
    }

    assertThat(server.takeRequest().getBody().readUtf8()).isEqualTo("Request Body");
    assertThat(server.takeRequest().getBody().readUtf8()).isEqualTo("Request Body");

    assertThat(contentTypeThreads).isNotEmpty();
    assertThat(new LinkedHashSet<>(contentTypeThreads)).containsExactly(callerThread);
    assertThat(contentLengthThreads).containsExactly(callerThread);
    assertThat(isOneShotThreads).containsExactly(callerThread);
    assertThat(writeToThreads).containsExactly(callerThread, callerThread);
    assertThat(callerThread.isInterrupted()).isFalse();
  }

  @Test
  public void
      testEarlyErrorResponseDuringUpload_deliversErrorResponseEvenIfWriteToSwallowsExceptions()
          throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(413)
            .addHeader("Connection", "close")
            .setBody("Payload too large")
            .setSocketPolicy(SocketPolicy.DO_NOT_READ_REQUEST_BODY));

    RequestBody requestBody =
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return UTF_8_TEXT;
          }

          @Override
          public long contentLength() {
            return BODY_CONTENT.length;
          }

          @Override
          public void writeTo(BufferedSink sink) {
            try {
              int offset = 0;
              while (offset < BODY_CONTENT.length) {
                int chunk = min(8192, BODY_CONTENT.length - offset);
                sink.write(BODY_CONTENT, offset, chunk);
                sink.flush();
                offset += chunk;
              }
            } catch (IOException ignored) {
              // Swallow abort/socket IOException and attempt extra operations before returning.
            }
            try {
              sink.close();
            } catch (IOException ignored) {
              // Expected.
            }
          }
        };

    OkHttpClient client = new OkHttpClient.Builder().addInterceptor(underTest).build();
    Request request = new Request.Builder().post(requestBody).url(server.url("/upload")).build();

    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(413);
      assertThat(response.body().string()).isEqualTo("Payload too large");
    }
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
  }

  @Test
  public void testEarlyResponseBetweenUploadReads_responseCanStillBeRead() throws Exception {
    enableProtocol(Protocol.HTTP_2);

    CountDownLatch readyForEarlyResponse = new CountDownLatch(1);
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse peek() {
            return new MockResponse().setSocketPolicy(SocketPolicy.DO_NOT_READ_REQUEST_BODY);
          }

          @Override
          public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
            assertThat(readyForEarlyResponse.await(5, SECONDS)).isTrue();
            return new MockResponse()
                .setResponseCode(413)
                .setBody("Payload too large")
                .setSocketPolicy(SocketPolicy.DO_NOT_READ_REQUEST_BODY);
          }
        });

    AtomicReference<IOException> abortException = new AtomicReference<>();
    RequestBody requestBody =
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return UTF_8_TEXT;
          }

          @Override
          public long contentLength() {
            return 128 * 1024L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            // Each 16 KiB write fills one Cronet UploadDataProvider.read() buffer and commits it
            // via onReadSucceeded(false), leaving activeUploadEvent == null. After 4 writes
            // (64 KiB), the HTTP/2 stream's 65,535-byte flow-control send window is full (since
            // the server does not read the request body), so Cronet does not issue a 5th
            // UploadDataProvider.read().
            for (int i = 0; i < 4; i++) {
              sink.write(new byte[16 * 1024]);
              sink.flush();
            }
            readyForEarlyResponse.countDown();
            // This 5th write blocks in obtainActiveRead() with activeUploadEvent == null
            // (in-between upload reads) until the server's 413 response triggers
            // onResponseStarted().
            try {
              sink.write(new byte[16 * 1024]);
              sink.flush();
            } catch (IOException e) {
              abortException.set(e);
            }
            try {
              sink.close();
            } catch (IOException ignored) {
              // Expected.
            }
          }
        };

    OkHttpClient client = new OkHttpClient.Builder().addInterceptor(underTest).build();
    Request request = new Request.Builder().post(requestBody).url(server.url("/upload")).build();

    try (Response response = client.newCall(request).execute()) {
      assertThat(response.protocol()).isEqualTo(Protocol.HTTP_2);
      assertThat(response.code()).isEqualTo(413);
      assertThat(response.body().string()).isEqualTo("Payload too large");
    }
    assertThat(abortException.get())
        .hasMessageThat()
        .contains("Upload aborted because response started (HTTP 413)");
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
  }

  @Test
  public void testEarlyResponseInMiddleOfUploadRead_responseCanStillBeRead() throws Exception {
    enableProtocol(Protocol.HTTP_2);

    CountDownLatch writeToEntered = new CountDownLatch(1);
    CountDownLatch uploadDispatched = new CountDownLatch(1);
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse peek() {
            return new MockResponse().setSocketPolicy(SocketPolicy.DO_NOT_READ_REQUEST_BODY);
          }

          @Override
          public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
            if ("/upload".equals(request.getPath())) {
              assertThat(writeToEntered.await(5, SECONDS)).isTrue();
              uploadDispatched.countDown();
              return new MockResponse()
                  .setResponseCode(413)
                  .setBody("Payload too large")
                  .setSocketPolicy(SocketPolicy.DO_NOT_READ_REQUEST_BODY);
            } else if ("/barrier".equals(request.getPath())) {
              return new MockResponse()
                  .setResponseCode(200)
                  .setBody("ok")
                  .setBodyDelay(50, MILLISECONDS);
            } else {
              throw new IllegalStateException("Unexpected path: " + request.getPath());
            }
          }
        });

    OkHttpClient client = new OkHttpClient.Builder().addInterceptor(underTest).build();
    AtomicReference<IOException> abortException = new AtomicReference<>();
    RequestBody requestBody =
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return UTF_8_TEXT;
          }

          @Override
          public long contentLength() {
            return 64 * 1024L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            // Entering writeTo() means Cronet has called the first UploadDataProvider.read() and
            // activeUploadEvent is currently held open (uncommitted) waiting for bytes.
            writeToEntered.countDown();
            try {
              assertThat(uploadDispatched.await(5, SECONDS)).isTrue();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new AssertionError(e);
            }
            // Send a barrier request over the same HTTP/2 connection so that when it completes,
            // Cronet has already received the earlier 413 response frames for /upload and invoked
            // onResponseStarted() while the first upload read is still active.
            try (Response barrierResponse =
                client
                    .newCall(new Request.Builder().url(server.url("/barrier")).build())
                    .execute()) {
              assertThat(barrierResponse.code()).isEqualTo(200);
            }

            // Because Event.ResponseStarted is already queued while the first UploadRead is still
            // active, the very first write/flush must immediately throw
            // InterruptedByCronetException without completing the first UploadRead.
            try {
              sink.write(new byte[100]);
              sink.flush();
            } catch (IOException e) {
              abortException.set(e);
            }
            try {
              sink.close();
            } catch (IOException ignored) {
              // Expected.
            }
          }
        };

    Request request = new Request.Builder().post(requestBody).url(server.url("/upload")).build();

    try (Response response = client.newCall(request).execute()) {
      assertThat(response.protocol()).isEqualTo(Protocol.HTTP_2);
      assertThat(response.code()).isEqualTo(413);
      assertThat(response.body().string()).isEqualTo("Payload too large");
    }
    assertThat(abortException.get())
        .hasMessageThat()
        .contains("Upload aborted because response started (HTTP 413)");
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
  }

  @Test
  public void testWriteTimeout_stalledUploadFailsEvenIfWriteToSwallowsExceptions()
      throws Exception {
    CountDownLatch releaseServer = new CountDownLatch(1);
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse peek() {
            return new MockResponse().setSocketPolicy(SocketPolicy.DO_NOT_READ_REQUEST_BODY);
          }

          @Override
          public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
            releaseServer.await(10, SECONDS);
            return new MockResponse().setResponseCode(200);
          }
        });

    byte[] chunk = new byte[64 * 1024];
    long totalBytes = 64L * 1024 * 1024;
    RequestBody requestBody =
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return UTF_8_TEXT;
          }

          @Override
          public long contentLength() {
            return totalBytes;
          }

          @Override
          public void writeTo(BufferedSink sink) {
            long written = 0L;
            while (written < totalBytes) {
              try {
                sink.write(chunk);
                sink.flush();
                written += chunk.length;
              } catch (Throwable ignored) {
                break;
              }
            }
            try {
              sink.close();
            } catch (Throwable ignored) {
              // Expected.
            }
          }
        };

    OkHttpClient client =
        new OkHttpClient.Builder()
            .writeTimeout(200, MILLISECONDS)
            .addInterceptor(underTest)
            .build();
    Request request = new Request.Builder().post(requestBody).url(server.url("/upload")).build();

    try {
      assertThrows(CronetTimeoutException.class, () -> client.newCall(request).execute());
      assertThat(Thread.currentThread().isInterrupted()).isFalse();
    } finally {
      releaseServer.countDown();
    }
  }

  @Test
  public void
      testUploadErrorDuringStalledUpload_cancelsUrlRequestImmediatelyWithoutWaitingForNextRead()
          throws Exception {
    CountDownLatch releaseServer = new CountDownLatch(1);
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse peek() {
            return new MockResponse().setSocketPolicy(SocketPolicy.DO_NOT_READ_REQUEST_BODY);
          }

          @Override
          public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
            releaseServer.await(10, SECONDS);
            return new MockResponse().setResponseCode(200);
          }
        });

    FileNotFoundException expectedFailure = new FileNotFoundException("Disk read failure mid-upload");
    RequestBody requestBody =
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return UTF_8_TEXT;
          }

          @Override
          public long contentLength() {
            return 64L * 1024 * 1024;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            sink.write(new byte[16 * 1024]);
            sink.flush();
            throw expectedFailure;
          }
        };

    OkHttpClient client =
        new OkHttpClient.Builder()
            .writeTimeout(60, SECONDS)
            .readTimeout(60, SECONDS)
            .addInterceptor(underTest)
            .build();
    Request request = new Request.Builder().post(requestBody).url(server.url("/upload")).build();

    try {
      FileNotFoundException thrown =
          assertThrows(FileNotFoundException.class, () -> client.newCall(request).execute());
      assertThat(thrown).isSameInstanceAs(expectedFailure);
      assertThat(Thread.currentThread().isInterrupted()).isFalse();
    } finally {
      releaseServer.countDown();
    }
  }

  @Test
  public void testRequestBodySwallowsLengthMismatchException_stillFailsCall() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("Unexpected success"));

    RequestBody requestBody =
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return UTF_8_TEXT;
          }

          @Override
          public long contentLength() {
            return 16 * 1024L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            sink.write(new byte[8 * 1024]);
            try {
              sink.close();
            } catch (IOException ignored) {
              // Swallow body-too-short exception and return normally.
            }
          }
        };

    OkHttpClient client = new OkHttpClient.Builder().addInterceptor(underTest).build();
    Request request = new Request.Builder().post(requestBody).url(server.url("/upload")).build();

    IOException e = assertThrows(IOException.class, () -> client.newCall(request).execute());
    assertThat(e).hasMessageThat().contains("The source has been exhausted");
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
  }

  @Test
  public void testRequestBodyThrowsRuntimeException_cancelsUrlRequestAndPropagates()
      throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("Unexpected success"));

    RequestBody requestBody =
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return UTF_8_TEXT;
          }

          @Override
          public long contentLength() {
            return 16 * 1024L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            sink.write(new byte[8 * 1024]);
            sink.flush();
            throw new IllegalStateException("writeTo boom");
          }
        };

    OkHttpClient client = new OkHttpClient.Builder().addInterceptor(underTest).build();
    Request request = new Request.Builder().post(requestBody).url(server.url("/upload")).build();

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> client.newCall(request).execute());
    assertThat(thrown).hasMessageThat().isEqualTo("writeTo boom");
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
  }

  private void enableProtocol(Protocol protocol) throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getContext();
    final X509Certificate certificate;
    try (InputStream certIs = context.getResources().openRawResource(R.raw.localhost_cert)) {
      certificate =
          (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(certIs);
    }
    final PrivateKey privateKey;
    try (InputStream keyIs = context.getResources().openRawResource(R.raw.localhost_key)) {
      PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(ByteStreams.toByteArray(keyIs));
      privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    }
    HeldCertificate heldCertificate =
        new HeldCertificate(new KeyPair(certificate.getPublicKey(), privateKey), certificate);
    HandshakeCertificates handshakeCertificates =
        new HandshakeCertificates.Builder().heldCertificate(heldCertificate).build();
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.setProtocols(Arrays.asList(protocol, Protocol.HTTP_1_1));
  }

  private static void assertEchoRequest(
      Request request, byte[] expectedBody, CronetInterceptor interceptorUnderTest)
      throws IOException {
    OkHttpClient client = new OkHttpClient.Builder().addInterceptor(interceptorUnderTest).build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.protocol()).isEqualTo(Protocol.HTTP_1_0);
      assertThat(response.code()).isEqualTo(200);

      for (String requestHeaderName : request.headers().names()) {
        assertThat(response.headers("x-request-" + requestHeaderName))
            .isEqualTo(request.headers(requestHeaderName));
      }
      assertThat(response.headers("x-request-http-method")).containsExactly(request.method());
      assertThat(response.headers("x-request-url")).containsExactly(request.url().toString());

      assertThat(response.body().bytes()).isEqualTo(expectedBody);
    }
  }

  private static Dispatcher withBodyDelay(Dispatcher delegate, long delay, TimeUnit timeUnit) {
    return new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
        return delegate.dispatch(request).setBodyDelay(delay, timeUnit);
      }
    };
  }
}
