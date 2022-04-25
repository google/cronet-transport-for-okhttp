/*
 * Copyright (C) 2013 Square, Inc.
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
package okhttp3;

import static com.google.common.truth.Truth.assertThat;
import static java.lang.Math.min;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.base.Throwables;
import com.google.common.collect.Range;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.GzipSink;
import okio.Okio;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

public abstract class CallTest {
  @Rule public final TestRule timeout = Timeout.seconds(30);
  @Rule public final MockWebServer server = new MockWebServer();

  RecordingCallback callback = new RecordingCallback();

  protected Call.Factory underTest;

  @Before
  public void setUp() {
    underTest = createUnderTest();
  }

  protected abstract Call.Factory createUnderTest();

  protected abstract Call.Factory createUnderTestWithReadTimeout(int timeoutMillis);

  protected abstract Call.Factory createUnderTestWithCallTimeout(int timeoutMillis);

  @Test
  public void get() throws Exception {
    server.enqueue(
        new MockResponse()
            .setBody("abc")
            .clearHeaders()
            .addHeader("content-type: text/plain")
            .addHeader("content-length", "3"));

    long sentAt = System.currentTimeMillis();
    RecordedResponse recordedResponse = executeSynchronously("/", "User-Agent", "SyncApiTest");
    long receivedAt = System.currentTimeMillis();

    recordedResponse
        .assertCode(200)
        .assertSuccessful()
        .assertHeaders(
            new Headers.Builder()
                .add("content-type", "text/plain")
                .add("content-length", "3")
                .build())
        .assertBody("abc")
        .assertSentRequestAtMillis(sentAt, receivedAt)
        .assertReceivedResponseAtMillis(sentAt, receivedAt);

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("GET");
    assertThat(recordedRequest.getHeader("User-Agent")).isEqualTo("SyncApiTest");
    assertThat(recordedRequest.getBody().size()).isEqualTo(0);
    assertThat(recordedRequest.getHeader("Content-Length")).isNull();
  }

  @Test
  public void buildRequestUsingHttpUrl() throws Exception {
    server.enqueue(new MockResponse());
    executeSynchronously("/").assertSuccessful();
  }

  @Test
  public void invalidScheme() throws Exception {
    Request.Builder requestBuilder = new Request.Builder();
    try {
      requestBuilder.url("ftp://hostname/path");
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("Expected URL scheme 'http' or 'https' but was 'ftp'");
    }
  }

  @Test
  public void invalidPort() throws Exception {
    Request.Builder requestBuilder = new Request.Builder();
    try {
      requestBuilder.url("http://localhost:65536/");
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Invalid URL port: \"65536\"");
    }
  }

  @Test
  public void getReturns500() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500));
    executeSynchronously("/").assertCode(500).assertNotSuccessful();
  }

  @Test
  @Ignore(
      "Cronet does not support adding multiple headers of the same key, see crbug.com/432719 for"
          + " more details.")
  public void repeatedHeaderNames() throws Exception {

    server.enqueue(new MockResponse().addHeader("B", "123").addHeader("B", "234"));

    executeSynchronously("/", "A", "345", "A", "456")
        .assertCode(200)
        .assertHeader("B", "123", "234");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getHeaders().values("A")).containsExactly("345", "456").inOrder();
  }

  @Test
  public void getWithRequestBody() throws Exception {
    server.enqueue(new MockResponse());

    try {
      new Request.Builder().method("GET", RequestBody.create(MediaType.get("text/plain"), "abc"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void head() throws Exception {
    server.enqueue(new MockResponse().addHeader("Content-Type: text/plain"));

    Request request =
        new Request.Builder()
            .url(server.url("/"))
            .head()
            .header("User-Agent", "SyncApiTest")
            .build();

    executeSynchronously(request).assertCode(200).assertHeader("Content-Type", "text/plain");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("HEAD");
    assertThat(recordedRequest.getHeader("User-Agent")).isEqualTo("SyncApiTest");
    assertThat(recordedRequest.getBody().size()).isEqualTo(0);
    assertThat(recordedRequest.getHeader("Content-Length")).isNull();
  }

  @Test
  public void headResponseContentLengthIsIgnored() throws Exception {
    server.enqueue(new MockResponse().clearHeaders().addHeader("Content-Length", "100"));
    server.enqueue(new MockResponse().setBody("abc"));

    Request headRequest = new Request.Builder().url(server.url("/")).head().build();
    Response response = underTest.newCall(headRequest).execute();
    assertThat(response.code()).isEqualTo(200);
    assertArrayEquals(new byte[0], response.body().bytes());

    Request getRequest = new Request.Builder().url(server.url("/")).build();
    executeSynchronously(getRequest).assertCode(200).assertBody("abc");

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  @Test
  public void headResponseContentEncodingIsIgnored() throws Exception {
    server.enqueue(new MockResponse().clearHeaders().addHeader("Content-Encoding", "chunked"));
    server.enqueue(new MockResponse().setBody("abc"));

    Request headRequest = new Request.Builder().url(server.url("/")).head().build();
    executeSynchronously(headRequest)
        .assertCode(200)
        .assertHeader("Content-Encoding", "chunked")
        .assertBody("");

    Request getRequest = new Request.Builder().url(server.url("/")).build();
    executeSynchronously(getRequest).assertCode(200).assertBody("abc");

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  @Test
  public void post() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request =
        new Request.Builder()
            .url(server.url("/"))
            .post(RequestBody.create(MediaType.get("text/plain"), "def"))
            .build();

    executeSynchronously(request).assertCode(200).assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("POST");
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("def");
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3");
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo("text/plain; charset=utf-8");
  }

  @Test
  public void postZeroLength() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request =
        new Request.Builder()
            .url(server.url("/"))
            .method("POST", RequestBody.create(null, new byte[0]))
            .build();

    executeSynchronously(request).assertCode(200).assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("POST");
    assertThat(recordedRequest.getBody().size()).isEqualTo(0);
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("0");
    assertThat(recordedRequest.getHeader("Content-Type")).isNull();
  }

  @Test
  public void delete() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder().url(server.url("/")).delete().build();

    executeSynchronously(request).assertCode(200).assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("DELETE");
    assertThat(recordedRequest.getBody().size()).isEqualTo(0);
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("0");
    assertThat(recordedRequest.getHeader("Content-Type")).isNull();
  }

  @Test
  public void deleteWithRequestBody() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request =
        new Request.Builder()
            .url(server.url("/"))
            .method("DELETE", RequestBody.create(MediaType.get("text/plain"), "def"))
            .build();

    executeSynchronously(request).assertCode(200).assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("DELETE");
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("def");
  }

  @Test
  public void put() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request =
        new Request.Builder()
            .url(server.url("/"))
            .put(RequestBody.create(MediaType.get("text/plain"), "def"))
            .build();

    executeSynchronously(request).assertCode(200).assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("PUT");
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("def");
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3");
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo("text/plain; charset=utf-8");
  }

  @Test
  public void patch() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request =
        new Request.Builder()
            .url(server.url("/"))
            .patch(RequestBody.create(MediaType.get("text/plain"), "def"))
            .build();

    executeSynchronously(request).assertCode(200).assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("PATCH");
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("def");
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3");
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo("text/plain; charset=utf-8");
  }

  @Test
  public void customMethodWithBody() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request =
        new Request.Builder()
            .url(server.url("/"))
            .method("CUSTOM", RequestBody.create(MediaType.get("text/plain"), "def"))
            .build();

    executeSynchronously(request).assertCode(200).assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("CUSTOM");
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("def");
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3");
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo("text/plain; charset=utf-8");
  }

  @Test
  public void unspecifiedRequestBodyContentTypeGetsDefault() throws Exception {
    server.enqueue(new MockResponse());

    Request request =
        new Request.Builder()
            .url(server.url("/"))
            .method("POST", RequestBody.create(null, "abc"))
            .build();

    executeSynchronously(request).assertCode(200);

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo("application/octet-stream");
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3");
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("abc");
  }

  @Test
  public void illegalToExecuteTwice() throws Exception {
    server.enqueue(new MockResponse().setBody("abc").addHeader("Content-Type: text/plain"));

    Request request =
        new Request.Builder().url(server.url("/")).header("User-Agent", "SyncApiTest").build();

    Call call = underTest.newCall(request);
    Response response = call.execute();
    response.body().close();

    try {
      call.execute();
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().isEqualTo("Already Executed");
    }

    try {
      call.enqueue(callback);
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().isEqualTo("Already Executed");
    }

    assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo("SyncApiTest");
  }

  @Test
  public void illegalToExecuteTwiceasync() throws Exception {
    server.enqueue(new MockResponse().setBody("abc").addHeader("Content-Type: text/plain"));

    Request request =
        new Request.Builder().url(server.url("/")).header("User-Agent", "SyncApiTest").build();

    Call call = underTest.newCall(request);
    call.enqueue(callback);

    try {
      call.execute();
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().isEqualTo("Already Executed");
    }

    try {
      call.enqueue(callback);
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().isEqualTo("Already Executed");
    }

    assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo("SyncApiTest");

    callback.await(request.url()).assertSuccessful();
  }

  @Test
  public void legalToExecuteTwiceCloning() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));

    Request request = new Request.Builder().url(server.url("/")).build();

    Call call = underTest.newCall(request);
    Response response1 = call.execute();

    Call cloned = call.clone();
    Response response2 = cloned.execute();

    assertThat(response1.body().string()).isEqualTo("abc");
    assertThat(response2.body().string()).isEqualTo("def");
  }

  @Test
  public void legalToExecuteTwiceCloningasync() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));

    Request request = new Request.Builder().url(server.url("/")).build();

    Call call = underTest.newCall(request);
    call.enqueue(callback);

    Call cloned = call.clone();
    cloned.enqueue(callback);

    RecordedResponse firstResponse = callback.await(request.url()).assertSuccessful();
    RecordedResponse secondResponse = callback.await(request.url()).assertSuccessful();

    Set<String> bodies = new LinkedHashSet<>();
    bodies.add(firstResponse.getBody());
    bodies.add(secondResponse.getBody());

    assertThat(bodies).contains("abc");
    assertThat(bodies).contains("def");
  }

  @Test
  public void getasync() throws Exception {
    server.enqueue(new MockResponse().setBody("abc").addHeader("Content-Type: text/plain"));

    Request request =
        new Request.Builder().url(server.url("/")).header("User-Agent", "AsyncApiTest").build();
    underTest.newCall(request).enqueue(callback);

    callback
        .await(request.url())
        .assertCode(200)
        .assertHeader("Content-Type", "text/plain")
        .assertBody("abc");

    assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo("AsyncApiTest");
  }

  @Test
  public void connectionPooling() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));
    server.enqueue(new MockResponse().setBody("ghi"));

    executeSynchronously("/a").assertBody("abc");
    executeSynchronously("/b").assertBody("def");
    executeSynchronously("/c").assertBody("ghi");

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(2);
  }

  @Test
  public void connectionPoolingasync() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));
    server.enqueue(new MockResponse().setBody("ghi"));

    underTest.newCall(new Request.Builder().url(server.url("/a")).build()).enqueue(callback);
    callback.await(server.url("/a")).assertBody("abc");

    underTest.newCall(new Request.Builder().url(server.url("/b")).build()).enqueue(callback);
    callback.await(server.url("/b")).assertBody("def");

    underTest.newCall(new Request.Builder().url(server.url("/c")).build()).enqueue(callback);
    callback.await(server.url("/c")).assertBody("ghi");

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(2);
  }

  @Test
  public void connectionReuseWhenResponseBodyConsumedasync() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));

    Request request = new Request.Builder().url(server.url("/a")).build();
    underTest
        .newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                throw new AssertionError();
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                assertThat(response.body().string()).isEqualTo("abc");

                // This request will share a connection with 'A' cause it's all done.
                underTest
                    .newCall(new Request.Builder().url(server.url("/b")).build())
                    .enqueue(callback);
              }
            });

    callback.await(server.url("/b")).assertCode(200).assertBody("def");
    // New connection.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    // Connection reuse!
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  @Test
  public void timeoutsUpdatedOnReusedConnections() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def").throttleBody(1, 750, MILLISECONDS));

    // First request: time out after 1000ms.
    underTest = createUnderTestWithReadTimeout(1000);

    executeSynchronously("/a").assertBody("abc");

    // Second request: time out after 250ms.
    underTest = createUnderTestWithReadTimeout(250);
    Request request = new Request.Builder().url(server.url("/b")).build();
    Response response = underTest.newCall(request).execute();
    BufferedSource bodySource = response.body().source();
    assertThat(bodySource.readByte()).isEqualTo((byte) 'd');

    // The second byte of this request will be delayed by 750ms so we should time out after 250ms.
    long startNanos = System.nanoTime();
    try {
      bodySource.readByte();
      fail();
    } catch (IOException expected) {
      // Timed out as expected.
      long elapsedNanos = System.nanoTime() - startNanos;
      long elapsedMillis = NANOSECONDS.toMillis(elapsedNanos);
      assertThat(elapsedMillis).isLessThan(500);
    } finally {
      bodySource.close();
    }
  }

  @Test
  public void reusedSinksGetIndependentTimeoutInstances() throws Exception {
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    // Call 1: set a deadline on the request body.
    RequestBody requestBody1 =
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return MediaType.get("text/plain");
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            sink.writeUtf8("abc");
            sink.timeout().deadline(5, SECONDS);
          }
        };
    Request request1 =
        new Request.Builder().url(server.url("/")).method("POST", requestBody1).build();
    try (Response response1 = underTest.newCall(request1).execute()) {
      assertThat(response1.code()).isEqualTo(200);
    }

    // Call 2: check for the absence of a deadline on the request body.
    RequestBody requestBody2 =
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return MediaType.get("text/plain");
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            assertThat(sink.timeout().hasDeadline()).isFalse();
            sink.writeUtf8("def");
          }
        };
    Request request2 =
        new Request.Builder().url(server.url("/")).method("POST", requestBody2).build();
    Response response2 = underTest.newCall(request2).execute();
    assertThat(response2.code()).isEqualTo(200);

    // Use sequence numbers to confirm the connection was pooled.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  @Test
  public void reusedSourcesGetIndependentTimeoutInstances() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));

    // Call 1: set a deadline on the response body.
    Request request1 = new Request.Builder().url(server.url("/")).build();
    Response response1 = underTest.newCall(request1).execute();
    BufferedSource body1 = response1.body().source();
    assertThat(body1.readUtf8()).isEqualTo("abc");
    body1.timeout().deadline(5, SECONDS);

    // Call 2: check for the absence of a deadline on the request body.
    Request request2 = new Request.Builder().url(server.url("/")).build();
    Response response2 = underTest.newCall(request2).execute();
    BufferedSource body2 = response2.body().source();
    assertThat(body2.readUtf8()).isEqualTo("def");
    assertThat(body2.timeout().hasDeadline()).isFalse();

    // Use sequence numbers to confirm the connection was pooled.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  @Test
  public void callTimeout() throws Exception {
    underTest = createUnderTestWithCallTimeout(1000);
    server.enqueue(new MockResponse().setBody("Hello there").setBodyDelay(10, SECONDS));

    Request request = new Request.Builder().url(server.url("/")).get().build();

    RecordedResponse response = executeSynchronously(request);

    response.assertFailureMatches(".*canceled.*");
  }

  @Test
  public void callTimeout_async() throws Exception {
    underTest = createUnderTestWithCallTimeout(1000);
    server.enqueue(new MockResponse().setBody("Hello there").setBodyDelay(10, SECONDS));

    Request request = new Request.Builder().url(server.url("/")).get().build();

    underTest.newCall(request).enqueue(callback);

    RecordedResponse response = callback.await(server.url("/"));
    response.assertFailureMatches(".*canceled.*");
  }

  @Test
  public void postasync() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request =
        new Request.Builder()
            .url(server.url("/"))
            .post(RequestBody.create(MediaType.get("text/plain"), "def"))
            .build();
    underTest.newCall(request).enqueue(callback);

    callback.await(request.url()).assertCode(200).assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("def");
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3");
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo("text/plain; charset=utf-8");
  }

  @Test
  @Ignore("Rewinds are not available pre-OkHttp 4")
  public void postBodyRetransmittedOnFailureRecovery() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    server.enqueue(new MockResponse().setBody("def"));

    // Seed the connection pool so we have something that can fail.
    Request request1 = new Request.Builder().url(server.url("/")).build();
    Response response1 = underTest.newCall(request1).execute();
    assertThat(response1.body().string()).isEqualTo("abc");

    Request request2 =
        new Request.Builder()
            .url(server.url("/"))
            .post(RequestBody.create(MediaType.get("text/plain"), "body!"))
            .build();
    Response response2 = underTest.newCall(request2).execute();
    assertThat(response2.body().string()).isEqualTo("def");

    RecordedRequest get = server.takeRequest();
    assertThat(get.getSequenceNumber()).isEqualTo(0);

    RecordedRequest post1 = server.takeRequest();
    assertThat(post1.getBody().readUtf8()).isEqualTo("body!");
    assertThat(post1.getSequenceNumber()).isEqualTo(1);

    RecordedRequest post2 = server.takeRequest();
    assertThat(post2.getBody().readUtf8()).isEqualTo("body!");
    assertThat(post2.getSequenceNumber()).isEqualTo(0);
  }

  @Test
  public void redirect() throws Exception {
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
    server.enqueue(new MockResponse().setBody("C"));

    executeSynchronously("/a").assertCode(200).assertBody("C");
    // TODO(danstahr): Prior responses are not filled yet.
    /*
    .priorResponse()
    .assertCode(302)
    .assertHeader("Test", "Redirect from /b to /c")
    .priorResponse()
    .assertCode(301)
    .assertHeader("Test", "Redirect from /a to /b");*/
  }

  @Test
  public void postRedirectsToGet() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
            .addHeader("Location: /page2")
            .setBody("This page has moved!"));
    server.enqueue(new MockResponse().setBody("Page 2"));

    Response response =
        underTest
            .newCall(
                new Request.Builder()
                    .url(server.url("/page1"))
                    .post(RequestBody.create(MediaType.get("text/plain"), "Request Body"))
                    .build())
            .execute();
    assertThat(response.body().string()).isEqualTo("Page 2");

    RecordedRequest page1 = server.takeRequest();
    assertThat(page1.getRequestLine()).isEqualTo("POST /page1 HTTP/1.1");
    assertThat(page1.getBody().readUtf8()).isEqualTo("Request Body");

    RecordedRequest page2 = server.takeRequest();
    assertThat(page2.getRequestLine()).isEqualTo("GET /page2 HTTP/1.1");
  }

  @Test
  @Ignore("Cronet doesn't support retries natively.")
  public void canRetryNormalRequestBody() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(503)
            .setHeader("Retry-After", "0")
            .setBody("please retry"));
    server.enqueue(new MockResponse().setBody("thank you for retrying"));

    Request request =
        new Request.Builder()
            .url(server.url("/"))
            .post(
                new RequestBody() {
                  int attempt = 0;

                  @Override
                  @Nullable
                  public MediaType contentType() {
                    return null;
                  }

                  @Override
                  public void writeTo(BufferedSink sink) throws IOException {
                    sink.writeUtf8("attempt " + attempt++);
                  }
                })
            .build();
    Response response = underTest.newCall(request).execute();
    assertThat(response.code()).isEqualTo(200);
    assertThat(response.body().string()).isEqualTo("thank you for retrying");

    assertThat(server.takeRequest().getBody().readUtf8()).isEqualTo("attempt 0");
    assertThat(server.takeRequest().getBody().readUtf8()).isEqualTo("attempt 1");
    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test
  public void cannotRetryRequestBody() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(503)
            .setHeader("Retry-After", "0")
            .setBody("please retry"));
    server.enqueue(new MockResponse().setBody("thank you for retrying"));

    Request request =
        new Request.Builder()
            .url(server.url("/"))
            .post(
                new RequestBody() {
                  int attempt = 0;

                  @Override
                  @Nullable
                  public MediaType contentType() {
                    return null;
                  }

                  @Override
                  public void writeTo(BufferedSink sink) throws IOException {
                    sink.writeUtf8("attempt " + attempt++);
                  }
                })
            .build();
    Response response = underTest.newCall(request).execute();
    assertThat(response.code()).isEqualTo(503);
    assertThat(response.body().string()).isEqualTo("please retry");

    assertThat(server.takeRequest().getBody().readUtf8()).isEqualTo("attempt 0");
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  @Ignore("Rewinds are not available pre-OkHttp 4")
  public void propfindRedirectsToPropfindAndMaintainsRequestBody() throws Exception {
    // given
    server.enqueue(
        new MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
            .addHeader("Location: /page2")
            .setBody("This page has moved!"));
    server.enqueue(new MockResponse().setBody("Page 2"));

    // when
    Response response =
        underTest
            .newCall(
                new Request.Builder()
                    .url(server.url("/page1"))
                    .method(
                        "PROPFIND", RequestBody.create(MediaType.get("text/plain"), "Request Body"))
                    .build())
            .execute();

    // then
    assertThat(response.body().string()).isEqualTo("Page 2");

    RecordedRequest page1 = server.takeRequest();
    assertThat(page1.getRequestLine()).isEqualTo("PROPFIND /page1 HTTP/1.1");
    assertThat(page1.getBody().readUtf8()).isEqualTo("Request Body");

    RecordedRequest page2 = server.takeRequest();
    assertThat(page2.getRequestLine()).isEqualTo("PROPFIND /page2 HTTP/1.1");
    assertThat(page2.getBody().readUtf8()).isEqualTo("Request Body");
  }

  @Test
  public void redirectasync() throws Exception {
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
    server.enqueue(new MockResponse().setBody("C"));

    Request request = new Request.Builder().url(server.url("/a")).build();
    underTest.newCall(request).enqueue(callback);

    callback.await(server.url("/a")).assertCode(200).assertBody("C");
    // TODO(danstahr): Prior responses are not filled yet.
    /*
    .priorResponse()
    .assertCode(302)
    .assertHeader("Test", "Redirect from /b to /c")
    .priorResponse()
    .assertCode(301)
    .assertHeader("Test", "Redirect from /a to /b");*/
  }

  @Test
  public void follow16Redirects() throws Exception {
    for (int i = 0; i < 16; i++) {
      server.enqueue(
          new MockResponse()
              .setResponseCode(301)
              .addHeader("Location: /" + (i + 1))
              .setBody("Redirecting to /" + (i + 1)));
    }
    server.enqueue(new MockResponse().setBody("Success!"));

    executeSynchronously("/0").assertCode(200).assertBody("Success!");
  }

  @Test
  public void follow16Redirectsasync() throws Exception {
    for (int i = 0; i < 16; i++) {
      server.enqueue(
          new MockResponse()
              .setResponseCode(301)
              .addHeader("Location: /" + (i + 1))
              .setBody("Redirecting to /" + (i + 1)));
    }
    server.enqueue(new MockResponse().setBody("Success!"));

    Request request = new Request.Builder().url(server.url("/0")).build();
    underTest.newCall(request).enqueue(callback);
    callback.await(server.url("/0")).assertCode(200).assertBody("Success!");
  }

  @Test
  public void doesNotFollow17Redirects() throws Exception {
    for (int i = 0; i < 17; i++) {
      server.enqueue(
          new MockResponse()
              .setResponseCode(301)
              .addHeader("Location: /" + (i + 1))
              .setBody("Redirecting to /" + (i + 1)));
    }

    try {
      underTest.newCall(new Request.Builder().url(server.url("/0")).build()).execute();
      fail();
    } catch (IOException expected) {
      assertThat(expected).hasMessageThat().contains("Too many follow-up requests: 17");
    }
  }

  @Test
  public void doesNotFollow17Redirectsasync() throws Exception {
    for (int i = 0; i < 17; i++) {
      server.enqueue(
          new MockResponse()
              .setResponseCode(301)
              .addHeader("Location: /" + (i + 1))
              .setBody("Redirecting to /" + (i + 1)));
    }

    Request request = new Request.Builder().url(server.url("/0")).build();
    underTest.newCall(request).enqueue(callback);
    callback.await(server.url("/0")).assertFailureMatches(".*Too many follow-up requests: 17.*");
  }

  @Test
  public void http204WithBodyDisallowed() throws IOException {
    server.enqueue(
        new MockResponse().setResponseCode(204).setBody("I'm not even supposed to be here today."));

    executeSynchronously("/").assertFailure("HTTP 204 had non-zero Content-Length: 39");
  }

  @Test
  public void http205WithBodyDisallowed() throws IOException {
    server.enqueue(
        new MockResponse().setResponseCode(205).setBody("I'm not even supposed to be here today."));

    executeSynchronously("/").assertFailure("HTTP 205 had non-zero Content-Length: 39");
  }

  @Test
  public void httpWithExcessiveHeaders() throws IOException {
    String longLine = "HTTP/1.1 200 " + stringFill('O', 256 * 1024) + "K";

    server.enqueue(
        new MockResponse().setStatus(longLine).setBody("I'm not even supposed to be here today."));

    executeSynchronously("/").assertFailureMatches(".*ERR_RESPONSE_HEADERS_TOO_BIG.*");
  }

  private String stringFill(char fillChar, int length) {
    char[] value = new char[length];
    Arrays.fill(value, fillChar);
    return new String(value);
  }

  @Test
  public void canceledBeforeExecute() throws Exception {
    Call call = underTest.newCall(new Request.Builder().url(server.url("/a")).build());
    call.cancel();

    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }
    assertThat(server.getRequestCount()).isEqualTo(0);
  }

  @Test
  public void cancelDuringConnect() throws Exception {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.STALL_SOCKET_AT_START));

    long cancelDelayMillis = 1500;
    Call call =
        underTest.newCall(
            new Request.Builder().url(server.url("/").newBuilder().scheme("http").build()).build());
    cancelLater(call, cancelDelayMillis);

    long startNanos = System.nanoTime();
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }
    long elapsedNanos = System.nanoTime() - startNanos;

    // For Cronet interceptor the signal propagates with a delay, relax the tolerance
    int tolerance = 600;
    assertThat(NANOSECONDS.toMillis(elapsedNanos))
        .isIn(Range.closed(cancelDelayMillis - tolerance, cancelDelayMillis + tolerance));
  }

  @Test
  public void cancelBeforeResponseBodyIsRead() throws Exception {
    server.enqueue(new MockResponse().setBody("def").throttleBody(1, 750, MILLISECONDS));

    final Call call = underTest.newCall(new Request.Builder().url(server.url("/a")).build());
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<Response> result = executor.submit(call::execute);

    Thread.sleep(100); // wait for it to go in flight.

    call.cancel();
    try {
      result.get().body().bytes();
      fail();
    } catch (IOException expected) {
    }
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  public void cancelBeforePostRequestBodyIsSent() throws Exception {
    server.enqueue(new MockResponse().throttleBody(1, 750, MILLISECONDS));

    final Call call = underTest.newCall(
        new Request.Builder().post(RequestBody.create(MediaType.get("text/plain"), "def"))
            .url(server.url("/a")).build());
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<Response> result = executor.submit(call::execute);

    Thread.sleep(100); // wait for it to go in flight.

    call.cancel();
    ExecutionException exception = assertThrows(ExecutionException.class, result::get);

    assertThat(exception).hasCauseThat().hasMessageThat().contains("canceled");

    // The request is canceled before being fully read by the server. Such requests aren't counted.
    assertThat(server.getRequestCount()).isEqualTo(0);
  }

  @Test
  public void cancelInFlightBeforeResponseReadThrowsIOE() throws Exception {
    Request request = new Request.Builder().url(server.url("/a")).build();
    final Call call = underTest.newCall(request);

    server.setDispatcher(
        new okhttp3.mockwebserver.Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            call.cancel();
            // The cancellation signal isn't propagated immediately if we're using an interceptor.
            // Wait a bit to make sure it propagates.
            try {
              Thread.sleep(1000);
            } catch (InterruptedException e) {
              throw new AssertionError(e);
            }
            return new MockResponse().setBody("A");
          }
        });

    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }
  }

  /**
   * There's a race condition where the cancel may apply after the stream has already been
   * processed.
   */
  @Test
  public void canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce() throws Exception {
    server.enqueue(new MockResponse().setBody("A"));

    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<String> bodyRef = new AtomicReference<>();
    final AtomicBoolean failureRef = new AtomicBoolean();

    Request request = new Request.Builder().url(server.url("/a")).build();
    final Call call = underTest.newCall(request);
    call.enqueue(
        new Callback() {
          @Override
          public void onFailure(Call call, IOException e) {
            failureRef.set(true);
            latch.countDown();
          }

          @Override
          public void onResponse(Call call, Response response) throws IOException {
            call.cancel();
            try {
              bodyRef.set(response.body().string());
            } catch (IOException e) { // It is ok if this broke the stream.
              bodyRef.set("A");
              throw e; // We expect to not loop into onFailure in this case.
            } finally {
              latch.countDown();
            }
          }
        });

    latch.await();
    assertThat(bodyRef.get()).isEqualTo("A");
    assertThat(failureRef.get()).isFalse();
  }

  @Test
  @Ignore(
      "Need to figure out a consistent strategy for encodings that are automatically processed "
          + "by Cronet")
  public void gzip() throws Exception {
    Buffer gzippedBody = gzipped("abcabcabc");

    server.enqueue(new MockResponse().setBody(gzippedBody).addHeader("Content-Encoding: gzip"));

    // Confirm that the user request doesn't have Accept-Encoding, and the user
    // response doesn't have a Content-Encoding or Content-Length.
    RecordedResponse userResponse = executeSynchronously("/");
    userResponse
        .assertCode(200)
        .assertRequestHeader("Accept-Encoding")
        .assertHeader("Content-Encoding")
        .assertHeader("Content-Length")
        .assertBody("abcabcabc");
  }

  @Test
  @Ignore(
      "Need to figure out a consistent strategy for encodings that are automatically processed "
          + "by Cronet")
  public void rangeHeaderPreventsAutomaticGzip() throws Exception {
    Buffer gzippedBody = gzipped("abcabcabc");

    // Enqueue a gzipped response. Our request isn't expecting it, but that's okay.
    server.enqueue(
        new MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_PARTIAL)
            .setBody(gzippedBody)
            .addHeader("Content-Encoding: gzip")
            .addHeader("Content-Range: bytes 0-" + (gzippedBody.size() - 1)));

    // Make a range request.
    Request request =
        new Request.Builder().url(server.url("/")).header("Range", "bytes=0-").build();
    Call call = underTest.newCall(request);

    // The response is not decompressed.
    Response response = call.execute();
    assertThat(response.header("Content-Encoding")).isEqualTo("gzip");
    assertThat(response.body().source().readByteString()).isEqualTo(gzippedBody.snapshot());

    // The request did not offer gzip support.
    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getHeader("Accept-Encoding")).isNull();
  }

  @Test
  public void asyncResponseCanBeConsumedLater() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));

    Request request =
        new Request.Builder().url(server.url("/")).header("User-Agent", "SyncApiTest").build();

    final BlockingQueue<Response> responseRef = new SynchronousQueue<>();
    underTest
        .newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                throw new AssertionError();
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try {
                  responseRef.put(response);
                } catch (InterruptedException e) {
                  throw new AssertionError();
                }
              }
            });

    Response response = responseRef.take();
    assertThat(response.code()).isEqualTo(200);
    assertThat(response.body().string()).isEqualTo("abc");

    // Make another request just to confirm that that connection can be reused...
    executeSynchronously("/").assertBody("def");
    // New connection.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    // Connection reused.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);

    // ... even before we close the response body!
    response.body().close();
  }

  @Test
  public void userAgentIsIncludedByDefault() throws Exception {
    server.enqueue(new MockResponse());

    executeSynchronously("/");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getHeader("User-Agent"))
        .matches("com.google.net.cronet.testing.testapp.*Cronet.*");
  }

  /** We forbid non-ASCII characters in outgoing request headers, but accept UTF-8. */
  @Test
  @Ignore("Headers handling in OkHttp and Cronet is different")
  public void responseHeaderParsingIsLenient() throws Exception {
    Headers headers =
        new Headers.Builder()
            .add("Content-Length", "0")
            .addLenient("a\tb: c\u007fd")
            .addLenient(": ef")
            .addLenient("\ud83c\udf69: \u2615\ufe0f")
            .build();
    server.enqueue(new MockResponse().setHeaders(headers));

    executeSynchronously("/")
        .assertHeader("a\tb", "c\u007fd")
        .assertHeader("\ud83c\udf69", "\u2615\ufe0f")
        .assertHeader("", "ef");
  }

  @Test
  public void serverSendsInvalidResponseHeaders() throws Exception {
    server.enqueue(new MockResponse().setStatus("HTP/1.1 200 OK"));

    executeSynchronously("/").assertFailureMatches(".*ERR_INVALID_HTTP_RESPONSE.*");
  }

  @Test
  @Ignore("Error handling in OkHttp and Cronet is different")
  public void serverSendsInvalidCodeTooLarge() throws Exception {
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 2147483648 OK"));

    executeSynchronously("/").assertFailure("Unexpected status line: HTTP/1.1 2147483648 OK");
  }

  @Test
  @Ignore("Error handling in OkHttp and Cronet is different")
  public void serverSendsInvalidCodeNotANumber() throws Exception {
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 00a OK"));

    executeSynchronously("/").assertFailure("Unexpected status line: HTTP/1.1 00a OK");
  }

  @Test
  @Ignore("Error handling in OkHttp and Cronet is different")
  public void serverSendsUnnecessaryWhitespace() throws Exception {
    server.enqueue(new MockResponse().setStatus(" HTTP/1.1 200 OK"));

    executeSynchronously("/").assertFailure("Unexpected status line:  HTTP/1.1 200 OK");
  }

  @Test
  public void requestHeaderNameWithSpaceForbidden() throws Exception {
    try {
      new Request.Builder().addHeader("a b", "c");
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("Unexpected char 0x20 at 1 in header name: a b");
    }
  }

  @Test
  public void requestHeaderNameWithTabForbidden() throws Exception {
    try {
      new Request.Builder().addHeader("a\tb", "c");
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("Unexpected char 0x09 at 1 in header name: a\tb");
    }
  }

  @Test
  @Ignore("Headers handling in OkHttp and Cronet is different")
  public void responseHeaderNameWithSpacePermitted() throws Exception {
    server.enqueue(
        new MockResponse()
            .clearHeaders()
            .addHeader("content-length: 0")
            .addHeaderLenient("a b", "c"));

    Call call = underTest.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    assertThat(response.header("a b")).isEqualTo("c");
  }

  @Test
  @Ignore("Headers handling in OkHttp and Cronet is different")
  public void responseHeaderNameWithTabPermitted() throws Exception {
    server.enqueue(
        new MockResponse()
            .clearHeaders()
            .addHeader("content-length: 0")
            .addHeaderLenient("a\tb", "c"));

    Call call = underTest.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    assertThat(response.headers.toMultimap()).containsEntry("a\tb", Arrays.asList("c"));
  }

  @Test
  public void connectFails() throws Exception {
    server.shutdown();

    executeSynchronously("/").assertFailure(IOException.class);
  }

  @Ignore("This may fail in DNS lookup, which we don't have timeouts for.")
  @Test
  public void invalidHost() throws Exception {
    Request request = new Request.Builder().url(HttpUrl.get("http://1234.1.1.1/")).build();

    executeSynchronously(request).assertFailure(UnknownHostException.class);
  }

  @Test
  public void uploadBodySmallChunkedEncoding() throws Exception {
    upload(true, 1048576, 256);
    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getBodySize()).isEqualTo(1048576);
    assertThat(recordedRequest.getChunkSizes()).isNotEmpty();
  }

  @Test
  public void uploadBodyLargeChunkedEncoding() throws Exception {
    upload(true, 1048576, 65536);
    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getBodySize()).isEqualTo(1048576);
    assertThat(recordedRequest.getChunkSizes()).isNotEmpty();
  }

  @Test
  public void uploadBodySmallFixedLength() throws Exception {
    upload(false, 1048576, 256);
    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getBodySize()).isEqualTo(1048576);
    assertThat(recordedRequest.getChunkSizes()).isEmpty();
  }

  @Test
  public void uploadBodyLargeFixedLength() throws Exception {
    upload(false, 1048576, 65536);
    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getBodySize()).isEqualTo(1048576);
    assertThat(recordedRequest.getChunkSizes()).isEmpty();
  }

  @Test
  public void emptyResponseBody() throws Exception {
    server.enqueue(new MockResponse().addHeader("abc", "def"));
    executeSynchronously("/").assertCode(200).assertHeader("abc", "def").assertBody("");
  }

  @Test
  public void postWithFileNotFound() throws Exception {
    final AtomicInteger called = new AtomicInteger(0);

    RequestBody body =
        new RequestBody() {
          @Nullable
          @Override
          public MediaType contentType() {
            return MediaType.get("application/octet-stream");
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            called.incrementAndGet();
            throw new FileNotFoundException("The file is nowhere to be seen");
          }
        };

    Request request = new Request.Builder().url(server.url("/")).post(body).build();

    RecordedResponse recordedResponse = executeSynchronously(request);
    assertThat(recordedResponse.failure).isNotNull();
    assertThat(Throwables.getRootCause(recordedResponse.failure))
        .isInstanceOf(FileNotFoundException.class);
    assertThat(Throwables.getRootCause(recordedResponse.failure))
        .hasMessageThat()
        .isEqualTo("The file is nowhere to be seen");

    assertThat(called.get()).isEqualTo(1L);
  }

  @Test
  public void requestBodyThrowsUnrelatedToNetwork() throws Exception {
    server.enqueue(new MockResponse());

    Request request =
        new Request.Builder()
            .url(server.url("/"))
            .post(
                new RequestBody() {
                  @Override
                  @Nullable
                  public MediaType contentType() {
                    return null;
                  }

                  @Override
                  public void writeTo(BufferedSink sink) throws IOException {
                    throw new IOException("boom");
                  }
                })
            .build();

    RecordedResponse recordedResponse = executeSynchronously(request);
    assertThat(recordedResponse.failure).isNotNull();
    assertThat(Throwables.getRootCause(recordedResponse.failure))
        .hasMessageThat()
        .isEqualTo("boom");
  }

  @Test
  public void requestBodyThrowsOutOfMemory() throws Exception {
    server.enqueue(new MockResponse());

    Request request =
        new Request.Builder()
            .url(server.url("/"))
            .post(
                new RequestBody() {
                  @Override
                  @Nullable
                  public MediaType contentType() {
                    return null;
                  }

                  @Override
                  public void writeTo(BufferedSink sink) throws IOException {
                    byte[] reallyBigArray = new byte[Integer.MAX_VALUE];
                    sink.write(reallyBigArray);
                  }
                })
            .build();

    RecordedResponse recordedResponse = executeSynchronously(request);
    assertThat(recordedResponse.failure).isNotNull();
    assertThat(Throwables.getRootCause(recordedResponse.failure))
        .isInstanceOf(OutOfMemoryError.class);
  }

  void upload(final boolean chunked, final int size, final int writeSize) throws Exception {
    server.enqueue(new MockResponse());
    executeSynchronously(
        new Request.Builder()
            .url(server.url("/"))
            .post(requestBody(chunked, size, writeSize))
            .build());
  }

  RequestBody requestBody(final boolean chunked, final long size, final int writeSize) {
    final byte[] buffer = new byte[writeSize];
    Arrays.fill(buffer, (byte) 'x');

    return new RequestBody() {
      @Override
      public MediaType contentType() {
        return MediaType.get("text/plain; charset=utf-8");
      }

      @Override
      public long contentLength() throws IOException {
        return chunked ? -1L : size;
      }

      @Override
      public void writeTo(BufferedSink sink) throws IOException {
        for (int count = 0; count < size; count += writeSize) {
          sink.write(buffer, 0, (int) min(size - count, writeSize));
        }
      }
    };
  }

  void makeFailingCall() {
    RequestBody requestBody =
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() throws IOException {
            return 1;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            throw new IOException("write body fail!");
          }
        };
    Call call =
        underTest.newCall(new Request.Builder().url(server.url("/")).post(requestBody).build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("write body fail!");
    }
  }

  protected RecordedResponse executeSynchronously(String path, String... headers)
      throws IOException {
    Request.Builder builder = new Request.Builder();
    builder.url(server.url(path));
    for (int i = 0, size = headers.length; i < size; i += 2) {
      builder.addHeader(headers[i], headers[i + 1]);
    }
    return executeSynchronously(builder.build());
  }

  protected RecordedResponse executeSynchronously(Request request) throws IOException {
    Call call = underTest.newCall(request);
    try {
      Response response = call.execute();
      String bodyString = response.body().string();
      return new RecordedResponse(request, response, null, bodyString, null);
    } catch (IOException e) {
      return new RecordedResponse(request, null, null, null, e);
    }
  }

  Buffer gzipped(String data) throws IOException {
    Buffer result = new Buffer();
    BufferedSink sink = Okio.buffer(new GzipSink(result));
    sink.writeUtf8(data);
    sink.close();
    return result;
  }

  Thread cancelLater(final Call call, final long delay) {
    Thread thread =
        new Thread("canceler") {
          @Override
          public void run() {
            try {
              Thread.sleep(delay);
            } catch (InterruptedException e) {
              throw new AssertionError();
            }
            call.cancel();
          }
        };
    thread.start();
    return thread;
  }
}
