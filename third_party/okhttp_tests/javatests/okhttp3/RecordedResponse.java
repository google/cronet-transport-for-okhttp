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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertAbout;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.Range;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.StringSubject;
import java.io.IOException;
import java.util.Arrays;
import javax.annotation.Nullable;

/** A received response or failure recorded by the response recorder. */
public final class RecordedResponse {
  private static final boolean ENABLE_REQUEST_AND_RESPONSE_TIMESTAMP_ASSERTS = false;

  public final Request request;
  @Nullable public final Response response;
  @Nullable public final WebSocket webSocket;
  @Nullable public final String body;
  @Nullable public final IOException failure;

  public RecordedResponse(
      Request request,
      @Nullable Response response,
      @Nullable WebSocket webSocket,
      @Nullable String body,
      @Nullable IOException failure) {
    this.request = request;
    this.response = response;
    this.webSocket = webSocket;
    this.body = body;
    this.failure = failure;
  }

  public RecordedResponse assertRequestHeader(String name, String... values) {
    assertThat(request.headers(name)).isEqualTo(Arrays.asList(values));
    return this;
  }

  public RecordedResponse assertCode(int expectedCode) {
    assertThat(response.code()).isEqualTo(expectedCode);
    return this;
  }

  public RecordedResponse assertSuccessful() {
    assertThat(response.isSuccessful()).isTrue();
    return this;
  }

  public RecordedResponse assertNotSuccessful() {
    assertThat(response.isSuccessful()).isFalse();
    return this;
  }

  public RecordedResponse assertHeader(String name, String... values) {
    assertThat(response.headers(name)).isEqualTo(Arrays.asList(values));
    return this;
  }

  public RecordedResponse assertHeaders(Headers headers) {
    assertThat(response.headers()).isEqualTo(headers);
    return this;
  }

  public RecordedResponse assertBody(String expectedBody) {
    assertThat(body).isEqualTo(expectedBody);
    return this;
  }

  /** Asserts that the current response was redirected and returns the prior response. */
  // TODO(danstahr): Not filled by the Cronet bridge yet
  public RecordedResponse priorResponse() {
    Response priorResponse = response.priorResponse();
    assertThat(priorResponse).isNotNull();
    assertThat(priorResponse.body()).isNull();
    return new RecordedResponse(priorResponse.request(), priorResponse, null, null, null);
  }

  /** Asserts that the current response used the network and returns the network response. */
  public RecordedResponse networkResponse() {
    Response networkResponse = response.networkResponse();
    assertThat(networkResponse).isNotNull();
    assertThat(networkResponse.body()).isNull();
    return new RecordedResponse(networkResponse.request(), networkResponse, null, null, null);
  }

  /** Asserts that the current response didn't use the network. */
  public RecordedResponse assertNoNetworkResponse() {
    assertThat(response.networkResponse()).isNull();
    return this;
  }

  /** Asserts that the current response didn't use the cache. */
  public RecordedResponse assertNoCacheResponse() {
    assertThat(response.cacheResponse()).isNull();
    return this;
  }

  /** Asserts that the current response used the cache and returns the cache response. */
  public RecordedResponse cacheResponse() {
    Response cacheResponse = response.cacheResponse();
    assertThat(cacheResponse).isNotNull();
    assertThat(cacheResponse.body()).isNull();
    return new RecordedResponse(cacheResponse.request(), cacheResponse, null, null, null);
  }

  public RecordedResponse assertFailure(Class<?>... allowedExceptionTypes) {
    assertWithMessage(
            "Expected exception type among "
                + Arrays.toString(allowedExceptionTypes)
                + ", got "
                + failure)
        .that(failure.getClass())
        .isIn(Arrays.asList(allowedExceptionTypes));

    return this;
  }

  public RecordedResponse assertFailure(String... messages) {
    assertWithMessage("No failure found, got " + response).that(failure).isNotNull();
    assertThat(failure).hasMessageThat().isIn(Arrays.asList(messages));
    return this;
  }

  public RecordedResponse assertFailureMatches(String... patterns) {
    assertThat(failure).isNotNull();
    ExtendedStringSubject.assertThat(failure.getMessage()).matchesAny(Arrays.asList(patterns));
    return this;
  }

  public RecordedResponse assertSentRequestAtMillis(long minimum, long maximum) {
    // TODO(danstahr): These are not set yet in Cronet interceptor / bridge
    if (ENABLE_REQUEST_AND_RESPONSE_TIMESTAMP_ASSERTS) {
      assertDateInRange(minimum, response.sentRequestAtMillis(), maximum);
    }
    return this;
  }

  public RecordedResponse assertReceivedResponseAtMillis(long minimum, long maximum) {
    // TODO(danstahr): These are not set yet in Cronet interceptor / bridge
    if (ENABLE_REQUEST_AND_RESPONSE_TIMESTAMP_ASSERTS) {
      assertDateInRange(minimum, response.receivedResponseAtMillis(), maximum);
    }
    return this;
  }

  private void assertDateInRange(long minimum, long actual, long maximum) {
    assertThat(actual).isIn(Range.closed(minimum, maximum));
  }

  public String getBody() {
    return body;
  }

  private static class ExtendedStringSubject extends StringSubject {

    @Nullable private final String actual;

    public static ExtendedStringSubject assertThat(@Nullable String s) {
      return assertAbout(ExtendedStringSubject::new).that(s);
    }

    protected ExtendedStringSubject(FailureMetadata metadata, @Nullable String string) {
      super(metadata, string);
      this.actual = string;
    }

    public void matchesAny(Iterable<String> regexes) {
      checkNotNull(regexes);
      if (actual == null) {
        failWithActual("expected a string that matches one of", regexes);
      }

      for (String regex : regexes) {
        if (actual.matches(regex)) {
          // Found one!
          return;
        }
      }

      failWithActual("expected to match one of", regexes);
    }
  }
}
