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
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.base.Strings;
import java.io.IOException;
import java.util.concurrent.Executors;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;
import okio.ByteString;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RequestBodyConverterTest {
  private static final MediaType UTF_8_TEXT = MediaType.parse("text/plain; charset=UTF-8");
  private static final String BODY_CONTENT =
      Strings.repeat("Lorem ipsum dolor sit amet, consectetur adipiscing elit.", 1000);
  // 2 MiB
  private static final String VERY_LONG_BODY_CONTENT =
      Strings.repeat("*Long body part, 32 bytes each* ", 2048 * 1024 / 32);
  private static final RequestBody KNOWN_LENGTH_REQUEST_BODY =
      RequestBody.create(UTF_8_TEXT, ByteString.encodeString(BODY_CONTENT, UTF_8));
  private static final RequestBody VERY_LONG_REQUEST_BODY =
      RequestBody.create(UTF_8_TEXT, ByteString.encodeString(VERY_LONG_BODY_CONTENT, UTF_8));
  private static final int NO_TIMEOUT = 0;

  private static final RequestBody UNKNOWN_LENGTH_REQUEST_BODY =
      new ArbitraryContentLengthRequestBody() {
        @Override
        public long contentLength() {
          return -1;
        }
      };

  @Rule public Timeout globalTimeout = Timeout.seconds(5);

  @Test
  public void testInMemory_knownLength() throws Exception {
    RequestBodyConverter underTest = new RequestBodyConverterImpl.InMemoryRequestBodyConverter();
    RequestBodyTestReader testReader =
        new RequestBodyTestReader(
            underTest.convertRequestBody(KNOWN_LENGTH_REQUEST_BODY, NO_TIMEOUT));

    assertThat(new String(testReader.readAll().getBody(), UTF_8)).isEqualTo(BODY_CONTENT);
  }

  @Test
  public void testInMemory_knownLength_actualBodyTooShort() throws Exception {
    RequestBody requestBody =
        new ArbitraryContentLengthRequestBody() {
          @Override
          public long contentLength() throws IOException {
            return KNOWN_LENGTH_REQUEST_BODY.contentLength() + 1;
          }
        };

    RequestBodyConverter underTest = new RequestBodyConverterImpl.InMemoryRequestBodyConverter();
    RequestBodyTestReader testReader =
        new RequestBodyTestReader(underTest.convertRequestBody(requestBody, NO_TIMEOUT));

    IOException exception =
        assertThrows(IOException.class, () -> new String(testReader.readAll().getBody(), UTF_8));
    assertThat(exception)
        .hasMessageThat()
        .contains(
            "Expected "
                + requestBody.contentLength()
                + " bytes but got "
                + KNOWN_LENGTH_REQUEST_BODY.contentLength());
  }

  @Test
  public void testInMemory_knownLength_actualBodyTooLong() throws Exception {
    RequestBody requestBody =
        new ArbitraryContentLengthRequestBody() {
          @Override
          public long contentLength() throws IOException {
            return KNOWN_LENGTH_REQUEST_BODY.contentLength() - 1;
          }
        };

    RequestBodyConverter underTest = new RequestBodyConverterImpl.InMemoryRequestBodyConverter();
    RequestBodyTestReader testReader =
        new RequestBodyTestReader(underTest.convertRequestBody(requestBody, NO_TIMEOUT));

    IOException exception =
        assertThrows(IOException.class, () -> new String(testReader.readAll().getBody(), UTF_8));
    assertThat(exception)
        .hasMessageThat()
        .contains(
            "Expected "
                + requestBody.contentLength()
                + " bytes but got "
                + KNOWN_LENGTH_REQUEST_BODY.contentLength());
  }

  @Test
  public void testStreaming_unknownLength() throws Exception {
    RequestBodyConverter underTest =
        new RequestBodyConverterImpl.StreamingRequestBodyConverter(
            Executors.newSingleThreadExecutor());
    RequestBodyTestReader testReader =
        new RequestBodyTestReader(
            underTest.convertRequestBody(UNKNOWN_LENGTH_REQUEST_BODY, NO_TIMEOUT));

    assertThat(new String(testReader.readAll().getBody(), UTF_8)).isEqualTo(BODY_CONTENT);
  }

  @Test
  public void testStreaming_knownLength() throws Exception {
    RequestBodyConverter underTest =
        new RequestBodyConverterImpl.StreamingRequestBodyConverter(
            Executors.newSingleThreadExecutor());
    RequestBodyTestReader testReader =
        new RequestBodyTestReader(
            underTest.convertRequestBody(KNOWN_LENGTH_REQUEST_BODY, NO_TIMEOUT));

    assertThat(new String(testReader.readAll().getBody(), UTF_8)).isEqualTo(BODY_CONTENT);
  }

  @Test
  public void testStreaming_knownLength_actualBodyTooShort() throws Exception {
    RequestBody requestBody =
        new ArbitraryContentLengthRequestBody() {
          @Override
          public long contentLength() throws IOException {
            return KNOWN_LENGTH_REQUEST_BODY.contentLength() + 1;
          }
        };

    RequestBodyConverter underTest =
        new RequestBodyConverterImpl.StreamingRequestBodyConverter(
            Executors.newSingleThreadExecutor());
    RequestBodyTestReader testReader =
        new RequestBodyTestReader(underTest.convertRequestBody(requestBody, NO_TIMEOUT));

    IOException exception =
        assertThrows(IOException.class, () -> new String(testReader.readAll().getBody(), UTF_8));
    assertThat(exception).hasMessageThat().contains("The source has been exhausted");
  }

  @Test
  public void testStreaming_knownLength_actualBodyTooLong() throws Exception {
    RequestBody requestBody =
        new ArbitraryContentLengthRequestBody() {
          @Override
          public long contentLength() throws IOException {
            return KNOWN_LENGTH_REQUEST_BODY.contentLength() - 1;
          }
        };

    RequestBodyConverter underTest =
        new RequestBodyConverterImpl.StreamingRequestBodyConverter(
            Executors.newSingleThreadExecutor());
    RequestBodyTestReader testReader =
        new RequestBodyTestReader(underTest.convertRequestBody(requestBody, NO_TIMEOUT));

    IOException exception =
        assertThrows(IOException.class, () -> new String(testReader.readAll().getBody(), UTF_8));
    assertThat(exception)
        .hasMessageThat()
        .contains(
            "Expected "
                + requestBody.contentLength()
                + " bytes but got at least "
                + KNOWN_LENGTH_REQUEST_BODY.contentLength());
  }

  @Test
  public void testDelegating_long_handledByStreaming() throws Exception {
    RequestBodyConverterImpl underTest =
        new RequestBodyConverterImpl(
            null,
            new RequestBodyConverterImpl.StreamingRequestBodyConverter(
                Executors.newSingleThreadExecutor()));

    RequestBodyTestReader testReader =
        new RequestBodyTestReader(underTest.convertRequestBody(VERY_LONG_REQUEST_BODY, NO_TIMEOUT));

    assertThat(new String(testReader.readAll().getBody(), UTF_8)).isEqualTo(VERY_LONG_BODY_CONTENT);
  }

  @Test
  public void testDelegating_short_handledByInMemory() throws Exception {
    RequestBodyConverterImpl underTest =
        new RequestBodyConverterImpl(
            new RequestBodyConverterImpl.InMemoryRequestBodyConverter(), null);

    RequestBodyTestReader testReader =
        new RequestBodyTestReader(
            underTest.convertRequestBody(KNOWN_LENGTH_REQUEST_BODY, NO_TIMEOUT));

    assertThat(new String(testReader.readAll().getBody(), UTF_8)).isEqualTo(BODY_CONTENT);
  }

  @Test
  public void testDelegating_unknownLength_handledByStreaming() throws Exception {
    RequestBodyConverterImpl underTest =
        new RequestBodyConverterImpl(
            null,
            new RequestBodyConverterImpl.StreamingRequestBodyConverter(
                Executors.newSingleThreadExecutor()));

    RequestBodyTestReader testReader =
        new RequestBodyTestReader(
            underTest.convertRequestBody(UNKNOWN_LENGTH_REQUEST_BODY, NO_TIMEOUT));

    assertThat(new String(testReader.readAll().getBody(), UTF_8)).isEqualTo(BODY_CONTENT);
  }

  private abstract static class ArbitraryContentLengthRequestBody extends RequestBody {
    @Override
    public abstract long contentLength() throws IOException;

    @Override
    public MediaType contentType() {
      return UTF_8_TEXT;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
      sink.writeString(BODY_CONTENT, UTF_8);
    }
  }
}
