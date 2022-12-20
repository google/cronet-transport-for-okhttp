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
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.base.Strings;
import com.google.net.cronet.testing.CronetEngineTestAppRule;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
