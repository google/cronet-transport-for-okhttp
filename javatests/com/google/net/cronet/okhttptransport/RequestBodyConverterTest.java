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
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.net.cronet.okhttptransport.RequestBodyConverterImpl.InMemoryRequestBodyConverter;
import com.google.net.cronet.okhttptransport.RequestBodyConverterImpl.StreamingRequestBodyConverter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;
import org.chromium.net.UploadDataProvider;
import org.chromium.net.UploadDataSink;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RequestBodyConverterTest {

  private static final int KB_56 = 56 * 1024;
  private static final int MB_2 = 2 * 1024 * 1024;
  private static final int NO_TIMEOUT = 0;

  @Rule public Timeout globalTimeout = Timeout.seconds(5);

  @Test
  public void testInMemory_knownLength() throws Exception {
    RequestBodyConverter converter = new InMemoryRequestBodyConverter();

    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content);
    UploadDataProvider provider = converter.convertRequestBody(requestBody, NO_TIMEOUT);

    assertThat(readAll(provider)).isEqualTo(content);
  }

  @Test
  public void testInMemory_knownLength_actualBodyTooShort() throws Exception {
    RequestBodyConverter converter = new InMemoryRequestBodyConverter();

    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content, KB_56 + 1);
    UploadDataProvider provider = converter.convertRequestBody(requestBody, NO_TIMEOUT);

    IOException exception = assertThrows(IOException.class, () -> readAll(provider));
    assertThat(exception)
        .hasMessageThat()
        .contains("Expected " + (KB_56 + 1) + " bytes but got " + KB_56);
  }

  @Test
  public void testInMemory_knownLength_actualBodyTooLong() throws Exception {
    RequestBodyConverter converter = new InMemoryRequestBodyConverter();

    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content, KB_56 - 1);
    UploadDataProvider provider = converter.convertRequestBody(requestBody, NO_TIMEOUT);

    IOException exception = assertThrows(IOException.class, () -> readAll(provider));
    assertThat(exception)
        .hasMessageThat()
        .contains("Expected " + (KB_56 - 1) + " bytes but got " + KB_56);
  }

  @Test
  public void testStreaming_unknownLength() throws Exception {
    RequestBodyConverter converter =
        new StreamingRequestBodyConverter(Executors.newSingleThreadExecutor());

    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content, -1);
    UploadDataProvider provider = converter.convertRequestBody(requestBody, NO_TIMEOUT);

    assertThat(readAll(provider)).isEqualTo(content);
  }

  @Test
  public void testStreaming_knownLength() throws Exception {
    RequestBodyConverter converter =
        new StreamingRequestBodyConverter(Executors.newSingleThreadExecutor());

    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content);
    UploadDataProvider provider = converter.convertRequestBody(requestBody, NO_TIMEOUT);

    assertThat(readAll(provider)).isEqualTo(content);
  }

  @Test
  public void testStreaming_knownLength_actualBodyTooShort() throws Exception {
    RequestBodyConverter converter =
        new StreamingRequestBodyConverter(Executors.newSingleThreadExecutor());

    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content, KB_56 + 1);
    UploadDataProvider provider = converter.convertRequestBody(requestBody, NO_TIMEOUT);

    IOException exception = assertThrows(IOException.class, () -> readAll(provider));
    assertThat(exception).hasMessageThat().contains("The source has been exhausted");
  }

  @Test
  public void testStreaming_knownLength_actualBodyTooLong() throws Exception {
    RequestBodyConverter converter =
        new StreamingRequestBodyConverter(Executors.newSingleThreadExecutor());

    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content, KB_56 - 1);
    UploadDataProvider provider = converter.convertRequestBody(requestBody, NO_TIMEOUT);

    IOException exception = assertThrows(IOException.class, () -> readAll(provider));
    assertThat(exception)
        .hasMessageThat()
        .contains("Expected " + (KB_56 - 1) + " bytes but got at least " + KB_56);
  }

  @Test
  public void testDelegating_long_handledByStreaming() throws Exception {
    RequestBodyConverterImpl converter =
        new RequestBodyConverterImpl(
            null, new StreamingRequestBodyConverter(Executors.newSingleThreadExecutor()));

    byte[] content = TestUtils.generateRandomBytesArray(MB_2);
    RequestBody requestBody = new TestRequestBody(content);
    UploadDataProvider provider = converter.convertRequestBody(requestBody, NO_TIMEOUT);

    assertThat(readAll(provider)).isEqualTo(content);
  }

  @Test
  public void testDelegating_short_handledByInMemory() throws Exception {
    RequestBodyConverterImpl converter =
        new RequestBodyConverterImpl(new InMemoryRequestBodyConverter(), null);

    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content);
    UploadDataProvider provider = converter.convertRequestBody(requestBody, NO_TIMEOUT);

    assertThat(readAll(provider)).isEqualTo(content);
  }

  @Test
  public void testDelegating_unknownLength_handledByStreaming() throws Exception {
    RequestBodyConverterImpl converter =
        new RequestBodyConverterImpl(
            null, new StreamingRequestBodyConverter(Executors.newSingleThreadExecutor()));

    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content, -1);
    UploadDataProvider provider = converter.convertRequestBody(requestBody, NO_TIMEOUT);

    assertThat(readAll(provider)).isEqualTo(content);
  }

  /**
   * Reads the entire body from the given {@link UploadDataProvider} and returns it as a byte array.
   */
  private static final byte[] readAll(UploadDataProvider uploadDataProvider) throws Exception {
    final Buffer buffer = new Buffer();
    final ByteBuffer byteBuffer = ByteBuffer.allocateDirect(8 * 1024);

    long length = uploadDataProvider.getLength();
    boolean isFinalChunk = false;

    // For chunked uploads (length == -1), read until the final chunk is received.
    // For non-chunked uploads (length != -1), read until buffer size reaches content length.
    while ((length == -1 && !isFinalChunk) || (length != -1 && buffer.size() < length)) {
      byteBuffer.clear();

      TestUploadDataSink sink = new TestUploadDataSink();
      uploadDataProvider.read(sink, byteBuffer);
      isFinalChunk = sink.waitForCallback();

      if (length != -1) {
        assertThat(isFinalChunk).isFalse(); // Should always be false by contract
        assertThat(byteBuffer.position()).isGreaterThan(0); // Should never be empty by contract
      }

      byteBuffer.flip();
      buffer.write(byteBuffer);
    }

    return buffer.readByteArray();
  }

  private static final class TestRequestBody extends RequestBody {

    private final byte[] content;
    private final long contentLength;

    TestRequestBody(byte[] content) {
      this(content, content.length);
    }

    TestRequestBody(byte[] content, long contentLength) {
      this.content = content;
      this.contentLength = contentLength;
    }

    @Override
    public MediaType contentType() {
      return null;
    }

    @Override
    public long contentLength() throws IOException {
      return contentLength;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
      sink.write(content);
    }
  }

  private static final class TestUploadDataSink extends UploadDataSink {

    private final SettableFuture<Boolean> result = SettableFuture.create();

    @Override
    public void onReadSucceeded(boolean finalChunk) {
      result.set(finalChunk);
    }

    @Override
    public void onReadError(Exception exception) {
      result.setException(exception);
    }

    /**
     * Waits for {@link #onReadSucceeded(boolean)} or {@link #onReadError(Exception)} to be called
     * and returns the value of {@code finalChunk} from {@code onReadSucceeded}.
     */
    private boolean waitForCallback() throws ExecutionException {
      return Uninterruptibles.getUninterruptibly(result);
    }

    @Override
    public void onRewindSucceeded() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void onRewindError(Exception exception) {
      throw new UnsupportedOperationException();
    }
  }
}
