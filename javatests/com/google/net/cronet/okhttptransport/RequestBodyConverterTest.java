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
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.base.Throwables;
import com.google.net.cronet.okhttptransport.OkHttpBridgeRequestCallback.Event;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;
import org.chromium.net.CronetException;
import org.chromium.net.UploadDataProvider;
import org.chromium.net.UploadDataSink;
import org.chromium.net.UrlResponseInfo;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RequestBodyConverterTest {

  private static final int KB_56 = 56 * 1024;
  private static final long NO_TIMEOUT = 0L;

  @Rule public Timeout globalTimeout = Timeout.seconds(5);

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<>();

  @After
  public void tearDown() {
    executor.shutdownNow();
  }

  @Test
  public void testUnknownLength() throws Exception {
    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content, -1);
    RequestBodyConverter provider = createProvider(requestBody);

    assertThat(readAll(provider)).isEqualTo(content);
  }

  @Test
  public void testUnknownLength_emptyBody() throws Exception {
    RequestBody requestBody = new TestRequestBody(new byte[0], -1);
    RequestBodyConverter provider = createProvider(requestBody);

    assertThat(readAll(provider)).isEmpty();
  }

  @Test
  public void testKnownLength() throws Exception {
    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content);
    RequestBodyConverter provider = createProvider(requestBody);

    assertThat(readAll(provider)).isEqualTo(content);
  }

  @Test
  public void testKnownLength_actualBodyTooShort() throws Exception {
    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content, KB_56 + 1);
    RequestBodyConverter provider = createProvider(requestBody);

    IOException exception = assertThrows(IOException.class, () -> readAll(provider));
    assertThat(exception).hasMessageThat().contains("The source has been exhausted");
  }

  @Test
  public void testKnownLength_actualBodyTooLong() throws Exception {
    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content, KB_56 - 1);
    RequestBodyConverter provider = createProvider(requestBody);

    IOException exception = assertThrows(IOException.class, () -> readAll(provider));
    assertThat(exception)
        .hasMessageThat()
        .contains("Expected " + (KB_56 - 1) + " bytes but got at least " + KB_56);
    assertThat(exception.getSuppressed()).isEmpty();
  }

  @Test
  public void testKnownLength_actualBodyTooLongOnChunkBoundary() throws Exception {
    byte[] content = TestUtils.generateRandomBytesArray(KB_56 + 1);
    RequestBody requestBody = new TestRequestBody(content, KB_56);
    RequestBodyConverter provider = createProvider(requestBody);

    IOException exception = assertThrows(IOException.class, () -> readAll(provider));
    assertThat(exception)
        .hasMessageThat()
        .contains("Expected " + KB_56 + " bytes but got at least " + (KB_56 + 1));
  }

  @Test
  public void testUnknownLength_closeSinkAfterWrite() throws Exception {
    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content, -1, true);
    RequestBodyConverter provider = createProvider(requestBody);

    assertThat(readAll(provider)).isEqualTo(content);
  }

  @Test
  public void testKnownLength_closeSinkAfterWrite() throws Exception {
    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content, content.length, true);
    RequestBodyConverter provider = createProvider(requestBody);

    assertThat(readAll(provider)).isEqualTo(content);
  }

  @Test
  public void testKnownLength_rewindAfterFullRead() throws Exception {
    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content);
    RequestBodyConverter provider = createProvider(requestBody);

    assertThat(readAll(provider)).isEqualTo(content);
    rewind(provider);
    assertThat(readAll(provider)).isEqualTo(content);
  }

  @Test
  public void testUnknownLength_rewindAfterFullRead() throws Exception {
    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content, -1);
    RequestBodyConverter provider = createProvider(requestBody);

    assertThat(readAll(provider)).isEqualTo(content);
    rewind(provider);
    assertThat(readAll(provider)).isEqualTo(content);
  }

  @Test
  public void testKnownLength_rewindAfterPartialRead() throws Exception {
    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content);
    RequestBodyConverter provider = createProvider(requestBody);

    rewindAfterPartialRead(provider);
    assertThat(readAll(provider)).isEqualTo(content);
  }

  @Test
  public void testUnknownLength_rewindAfterPartialRead() throws Exception {
    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content, -1);
    RequestBodyConverter provider = createProvider(requestBody);

    rewindAfterPartialRead(provider);
    assertThat(readAll(provider)).isEqualTo(content);
  }

  @Test
  public void testRewind_afterUploadFailure_resetsStateAndSucceeds() throws Exception {
    byte[] content = TestUtils.generateRandomBytesArray(16 * 1024);
    AtomicBoolean failFirstAttempt = new AtomicBoolean(true);
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return content.length;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            if (failFirstAttempt.getAndSet(false)) {
              throw new IOException("Transient read failure");
            }
            sink.write(content);
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);

    IOException exception = assertThrows(IOException.class, () -> readAll(provider));
    assertThat(exception).hasMessageThat().isEqualTo("Transient read failure");

    rewind(provider);
    assertThat(readAll(provider)).isEqualTo(content);
  }

  @Test
  public void testOneShot_rewindFails() throws Exception {
    byte[] content = TestUtils.generateRandomBytesArray(KB_56);
    RequestBody requestBody = new TestRequestBody(content, content.length, false, true);
    RequestBodyConverter provider = createProvider(requestBody);

    assertThat(readAll(provider)).isEqualTo(content);
    UnsupportedOperationException exception =
        assertThrows(UnsupportedOperationException.class, () -> rewind(provider));
    assertThat(exception).hasMessageThat().isEqualTo("Body is one-shot");
  }

  @Test
  public void testRewind_isOneShotThrows_propagatesDirectlyWithoutSignalingSink() throws Exception {
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 16L;
          }

          @Override
          public boolean isOneShot() {
            throw new IllegalStateException("isOneShot boom");
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            sink.write(new byte[16]);
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);
    TestUploadDataSink rewindSink = new TestUploadDataSink();
    provider.rewind(rewindSink);
    Event.UploadRewind rewindEvent = (Event.UploadRewind) events.take();

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> provider.handleUploadEvent(rewindEvent));
    assertThat(thrown).hasMessageThat().isEqualTo("isOneShot boom");
    assertThat(rewindSink.isDone()).isFalse();

    provider.onTerminalEvent();
    IOException rewindError = assertThrows(IOException.class, rewindSink::waitForRewindCallback);
    assertThat(rewindError).hasMessageThat().contains("active upload rewind");
  }

  @Test
  public void testSetActiveUploadEvent_rewindFailedOnTerminalEvent() throws Exception {
    RequestBodyConverter provider = createProvider(new TestRequestBody(new byte[16]));
    TestUploadDataSink rewindSink = new TestUploadDataSink();
    provider.rewind(rewindSink);
    Event.UploadRewind rewindEvent = (Event.UploadRewind) events.take();

    provider.setActiveUploadEvent(rewindEvent);
    assertThat(rewindSink.isDone()).isFalse();

    provider.onTerminalEvent();
    IOException rewindError = assertThrows(IOException.class, rewindSink::waitForRewindCallback);
    assertThat(rewindError).hasMessageThat().contains("active upload rewind");
    // Subsequent terminal notifications are no-ops.
    provider.onTerminalEvent();
  }

  @Test
  public void testSetActiveUploadEvent_concurrentUpload_throwsIllegalStateException()
      throws Exception {
    RequestBodyConverter provider = createProvider(new TestRequestBody(new byte[16]));
    TestUploadDataSink readSink = new TestUploadDataSink();
    TestUploadDataSink rewindSink = new TestUploadDataSink();
    provider.read(readSink, ByteBuffer.allocateDirect(16));
    provider.rewind(rewindSink);
    Event.UploadRead readEvent = (Event.UploadRead) events.take();
    Event.UploadRewind rewindEvent = (Event.UploadRewind) events.take();

    provider.setActiveUploadEvent(readEvent);
    assertThrows(IllegalStateException.class, () -> provider.setActiveUploadEvent(rewindEvent));
  }

  @Test
  public void testWriteBody_writeToThrowsIOException_propagatesDirectlyWithoutSignalingSink()
      throws Exception {
    IOException expected = new IOException("Write failed!");
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 1024L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            throw expected;
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);
    TestUploadDataSink readSink = new TestUploadDataSink();
    provider.read(readSink, ByteBuffer.allocateDirect(64));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();

    IOException thrown =
        assertThrows(IOException.class, () -> provider.handleUploadEvent(firstRead));
    assertThat(thrown).isSameInstanceAs(expected);
    assertThat(readSink.isDone()).isFalse();

    provider.onTerminalEvent();
    assertThrows(IOException.class, readSink::waitForCallback);
  }

  @Test
  public void testWriteBody_writeToThrowsOutOfMemoryError_propagatesDirectlyWithoutSignalingSink()
      throws Exception {
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 16L;
          }

          @Override
          public void writeTo(BufferedSink sink) {
            throw new OutOfMemoryError("Simulated OOM");
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);
    TestUploadDataSink readSink = new TestUploadDataSink();
    provider.read(readSink, ByteBuffer.allocateDirect(64));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();

    OutOfMemoryError thrown =
        assertThrows(OutOfMemoryError.class, () -> provider.handleUploadEvent(firstRead));
    assertThat(thrown).hasMessageThat().isEqualTo("Simulated OOM");
    assertThat(readSink.isDone()).isFalse();

    provider.onTerminalEvent();
    assertThrows(IOException.class, readSink::waitForCallback);
  }

  @Test
  public void testCallbacksRunOnCallerThread() throws Exception {
    byte[] content = TestUtils.generateRandomBytesArray(1024);
    Thread callerThread = Thread.currentThread();
    AtomicReference<Thread> writeThread = new AtomicReference<>();
    AtomicReference<Thread> isOneShotThread = new AtomicReference<>();
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return content.length;
          }

          @Override
          public boolean isOneShot() {
            isOneShotThread.set(Thread.currentThread());
            return false;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            writeThread.set(Thread.currentThread());
            sink.write(content);
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);

    TestUploadDataSink readSink = new TestUploadDataSink();
    ByteBuffer byteBuffer = ByteBuffer.allocateDirect(8 * 1024);
    executor.submit(() -> provider.read(readSink, byteBuffer)).get();

    // read() only enqueued an event; writeTo() has not run yet.
    assertThat(readSink.isDone()).isFalse();
    assertThat(writeThread.get()).isNull();

    Event.UploadRead readEvent = (Event.UploadRead) events.take();
    assertThat(provider.handleUploadEvent(readEvent)).isNull();

    assertThat(readSink.waitForCallback()).isFalse();
    assertThat(writeThread.get()).isSameInstanceAs(callerThread);
    assertThat(readSink.callbackThread.get()).isSameInstanceAs(callerThread);
    assertThat(callerThread.isInterrupted()).isFalse();

    byteBuffer.flip();
    byte[] actual = new byte[byteBuffer.remaining()];
    byteBuffer.get(actual);
    assertThat(actual).isEqualTo(content);

    TestUploadDataSink rewindSink = new TestUploadDataSink();
    executor.submit(() -> provider.rewind(rewindSink)).get();
    assertThat(isOneShotThread.get()).isNull();

    Event.UploadRewind rewindEvent = (Event.UploadRewind) events.take();
    assertThat(provider.handleUploadEvent(rewindEvent)).isNull();
    rewindSink.waitForRewindCallback();
    assertThat(isOneShotThread.get()).isSameInstanceAs(callerThread);
    assertThat(rewindSink.callbackThread.get()).isSameInstanceAs(callerThread);
    assertThat(callerThread.isInterrupted()).isFalse();
  }

  @Test
  public void testWriteTimeout() throws Exception {
    byte[] content = TestUtils.generateRandomBytesArray(16 * 1024);
    RequestBody requestBody = new TestRequestBody(content);
    RequestBodyConverter provider = createProvider(requestBody, 100);

    TestUploadDataSink firstSink = new TestUploadDataSink();
    provider.read(firstSink, ByteBuffer.allocateDirect(8 * 1024));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();

    // First 8 KiB chunk succeeds, then writeBody() times out waiting for Cronet to request the
    // second 8 KiB chunk.
    CronetTimeoutException thrown =
        assertThrows(CronetTimeoutException.class, () -> provider.handleUploadEvent(firstRead));
    assertThat(thrown.getSuppressed()).isEmpty();
    assertThat(firstSink.waitForCallback()).isFalse();
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
  }

  @Test
  public void testWriteTimeout_writeToSwallowsWriteAndCloseExceptions() throws Exception {
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 16 * 1024L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            sink.write(new byte[8 * 1024]);
            sink.flush();
            try {
              sink.write(new byte[8 * 1024]);
              sink.flush();
            } catch (Throwable ignored) {
              // Expected.
            }
            try {
              sink.close();
            } catch (Throwable ignored) {
              // Expected.
            }
          }
        };
    RequestBodyConverter provider = createProvider(requestBody, 100);
    TestUploadDataSink firstSink = new TestUploadDataSink();
    provider.read(firstSink, ByteBuffer.allocateDirect(8 * 1024));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();

    assertThrows(CronetTimeoutException.class, () -> provider.handleUploadEvent(firstRead));
    assertThat(firstSink.waitForCallback()).isFalse();
  }

  @Test
  public void
      testWriteTimeout_writeToSwallowsWriteExceptionWithoutClosing_rethrowsCronetTimeoutException()
          throws Exception {
    AtomicReference<BufferedSink> capturedBufferedSink = new AtomicReference<>();
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 16 * 1024L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            capturedBufferedSink.set(sink);
            sink.write(new byte[8 * 1024]);
            sink.flush();
            try {
              sink.write(new byte[8 * 1024]);
              sink.flush();
            } catch (Throwable ignored) {
              // Swallow CronetTimeoutException without calling sink.close(); bufferedSink remains
              // open with 8 KiB buffered.
            }
          }
        };
    RequestBodyConverter provider = createProvider(requestBody, 100);
    TestUploadDataSink firstSink = new TestUploadDataSink();
    provider.read(firstSink, ByteBuffer.allocateDirect(8 * 1024));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();

    assertThrows(CronetTimeoutException.class, () -> provider.handleUploadEvent(firstRead));
    assertThat(firstSink.waitForCallback()).isFalse();
    assertThat(capturedBufferedSink.get().isOpen()).isFalse();
  }

  @Test
  public void
      testWriteBody_writeFailsAndFinallyClosesQuietlyCatchingIOException_preservesPrimaryException()
          throws Exception {
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 16 * 1024L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            sink.write(new byte[8 * 1024]);
            sink.flush();
            try {
              sink.write(new byte[8 * 1024]);
              sink.flush();
            } finally {
              try {
                sink.close();
              } catch (IOException ignored) {
                // Standard closeQuietly pattern in finally: ignores IOException from close() so the
                // in-flight CronetTimeoutException is not masked.
              }
            }
          }
        };
    RequestBodyConverter provider = createProvider(requestBody, 100);
    TestUploadDataSink firstSink = new TestUploadDataSink();
    provider.read(firstSink, ByteBuffer.allocateDirect(8 * 1024));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();

    CronetTimeoutException thrown =
        assertThrows(CronetTimeoutException.class, () -> provider.handleUploadEvent(firstRead));
    assertThat(thrown.getSuppressed()).isEmpty();
    assertThat(firstSink.waitForCallback()).isFalse();
  }

  @Test
  public void
      testWriteBody_threadInterruptedWhileWaitingForRead_restoresInterruptFlagAndThrowsTimeout()
          throws Exception {
    CountDownLatch firstChunkComplete = new CountDownLatch(1);
    Thread callerThread = Thread.currentThread();
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 16 * 1024L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            sink.write(new byte[8 * 1024]);
            sink.flush();
            firstChunkComplete.countDown();
            sink.write(new byte[8 * 1024]);
            sink.flush();
          }
        };
    RequestBodyConverter provider = createProvider(requestBody, 60_000);
    TestUploadDataSink firstSink = new TestUploadDataSink();
    provider.read(firstSink, ByteBuffer.allocateDirect(8 * 1024));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();

    Future<Void> interrupter =
        executor.submit(
            () -> {
              assertThat(firstChunkComplete.await(5, SECONDS)).isTrue();
              assertThat(firstSink.waitForCallback()).isFalse();
              callerThread.interrupt();
              return null;
            });

    try {
      assertThrows(CronetTimeoutException.class, () -> provider.handleUploadEvent(firstRead));
      assertThat(Thread.interrupted()).isTrue();
    } finally {
      Thread.interrupted();
      interrupter.get();
    }
  }

  @Test
  public void testWriteBody_abortedByEarlyResponseBetweenChunks_evenIfWriteToSwallowsAndRetries()
      throws Exception {
    CountDownLatch firstChunkComplete = new CountDownLatch(1);
    Event.ResponseStarted earlyResponse = new Event.ResponseStarted(new TestResponseInfo(413));
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 24 * 1024L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            sink.write(new byte[8 * 1024]);
            sink.flush();
            firstChunkComplete.countDown();
            try {
              sink.write(new byte[8 * 1024]);
              sink.flush();
            } catch (IOException ignored) {
              // Swallow the abort exception and try writing/flushing/closing again.
            }
            try {
              sink.write(new byte[8 * 1024]);
              sink.flush();
            } catch (IOException ignored) {
              // Expected.
            }
            try {
              sink.close();
            } catch (IOException ignored) {
              // Expected.
            }
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);

    TestUploadDataSink firstSink = new TestUploadDataSink();
    provider.read(firstSink, ByteBuffer.allocateDirect(8 * 1024));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();

    Future<Void> responseEnqueued =
        executor.submit(
            () -> {
              assertThat(firstChunkComplete.await(5, SECONDS)).isTrue();
              assertThat(firstSink.waitForCallback()).isFalse();
              events.add(earlyResponse);
              return null;
            });

    Event returnedEvent = provider.handleUploadEvent(firstRead);
    assertThat(returnedEvent).isSameInstanceAs(earlyResponse);
    responseEnqueued.get();
  }

  @Test
  public void testWriteBody_abortedWhileHoldingOpenSink_doesNotSignalSink() throws Exception {
    Event.ResponseStarted earlyResponse = new Event.ResponseStarted(new TestResponseInfo(413));
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 16 * 1024L;
          }

          @Override
          public void writeTo(BufferedSink sink) {
            try {
              sink.write(new byte[8 * 1024]);
              sink.flush();
            } catch (IOException ignored) {
              // Swallow abort exception and return normally.
            }
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);
    TestUploadDataSink firstSink = new TestUploadDataSink();
    provider.read(firstSink, ByteBuffer.allocateDirect(8 * 1024));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();
    events.add(earlyResponse);

    Event returnedEvent = provider.handleUploadEvent(firstRead);
    assertThat(returnedEvent).isSameInstanceAs(earlyResponse);
    assertThat(firstSink.isDone()).isFalse();

    provider.onTerminalEvent();
    assertThrows(IOException.class, firstSink::waitForCallback);
  }

  @Test
  public void testWriteBody_concurrentUploadRead_throwsIllegalStateException() throws Exception {
    RequestBody requestBody = new TestRequestBody(new byte[16 * 1024]);
    RequestBodyConverter provider = createProvider(requestBody);
    TestUploadDataSink firstSink = new TestUploadDataSink();
    TestUploadDataSink secondSink = new TestUploadDataSink();
    provider.read(firstSink, ByteBuffer.allocateDirect(8 * 1024));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();
    // Enqueueing a second UploadRead before firstRead completes violates Cronet's single-flight
    // contract and must fail fast.
    provider.read(secondSink, ByteBuffer.allocateDirect(8 * 1024));

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> provider.handleUploadEvent(firstRead));
    assertThat(thrown).hasMessageThat().contains("Unexpected event during upload");
    assertThat(firstSink.isDone()).isFalse();
    assertThat(secondSink.isDone()).isFalse();
  }

  @Test
  public void testWriteBody_sinkThrowsNonIOExceptionAndUserSwallowsIt_stillRethrows()
      throws Exception {
    AtomicReference<Throwable> initialException = new AtomicReference<>();
    List<Throwable> subsequentExceptions = new ArrayList<>();
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 8 * 1024L;
          }

          @Override
          public void writeTo(BufferedSink sink) {
            try {
              sink.write(new byte[8 * 1024]);
              sink.flush();
            } catch (Throwable t) {
              // Swallow the non-IOException thrown by the sink.
              initialException.set(t);
            }
            try {
              sink.flush();
            } catch (Throwable t) {
              subsequentExceptions.add(t);
            }
            try {
              sink.close();
            } catch (Throwable t) {
              subsequentExceptions.add(t);
            }
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);
    TestUploadDataSink firstSink = new TestUploadDataSink();
    TestUploadDataSink secondSink = new TestUploadDataSink();
    provider.read(firstSink, ByteBuffer.allocateDirect(8 * 1024));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();
    provider.read(secondSink, ByteBuffer.allocateDirect(8 * 1024));

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> provider.handleUploadEvent(firstRead));
    assertThat(thrown).isSameInstanceAs(initialException.get());
    assertThat(thrown).hasMessageThat().contains("Unexpected event during upload");
    assertThat(subsequentExceptions).hasSize(2);
    for (Throwable subsequent : subsequentExceptions) {
      assertThat(subsequent).isInstanceOf(IOException.class);
      assertThat(subsequent).hasMessageThat().isEqualTo("Upload already failed");
      assertThat(subsequent).hasCauseThat().isSameInstanceAs(initialException.get());
    }
    assertThat(firstSink.isDone()).isFalse();
    assertThat(secondSink.isDone()).isFalse();
  }

  @Test
  public void
      testWriteBody_abortedBetweenLastChunkAndClose_reportsInterruptionInsteadOfCompletingUpload()
          throws Exception {
    Event.ResponseStarted earlyResponse = new Event.ResponseStarted(new TestResponseInfo(413));
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 8 * 1024L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            // Writes all 8 KiB matching contentLength; RequestBodySink holds firstRead uncommitted
            // in activeUploadEvent until close() runs.
            sink.write(new byte[8 * 1024]);
            sink.flush();
            // An interruption event arrives after the last chunk is written, before close().
            events.add(earlyResponse);
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);
    TestUploadDataSink firstSink = new TestUploadDataSink();
    provider.read(firstSink, ByteBuffer.allocateDirect(8 * 1024));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();

    assertThat(provider.handleUploadEvent(firstRead)).isSameInstanceAs(earlyResponse);
    assertThat(firstSink.isDone()).isFalse();

    provider.onTerminalEvent();
    assertThrows(IOException.class, firstSink::waitForCallback);
  }

  @Test
  public void testWriteBody_abortedDuringImplicitCloseOfSmallBufferedWrite_returnsAbortEvent()
      throws Exception {
    Event.ResponseStarted earlyResponse = new Event.ResponseStarted(new TestResponseInfo(413));
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 100L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            // 100 bytes fit inside RealBufferedSink's 8 KiB buffer, so RequestBodySink is not
            // called during writeTo() at all; the implicit bufferedSink.close() in writeBody()
            // flushes and catches the resulting InterruptedByCronetException.
            sink.write(new byte[100]);
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);
    TestUploadDataSink firstSink = new TestUploadDataSink();
    provider.read(firstSink, ByteBuffer.allocateDirect(8 * 1024));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();
    events.add(earlyResponse);

    assertThat(provider.handleUploadEvent(firstRead)).isSameInstanceAs(earlyResponse);
    assertThat(firstSink.isDone()).isFalse();

    provider.onTerminalEvent();
    assertThrows(IOException.class, firstSink::waitForCallback);
  }

  @Test
  public void
      testWriteBody_abortedWithQueuedNextReadAndEarlyResponse_doesNotCommitNextReadOrUnderflow()
          throws Exception {
    CountDownLatch firstChunkComplete = new CountDownLatch(1);
    CountDownLatch secondReadAndResponseQueued = new CountDownLatch(1);
    Event.ResponseStarted earlyResponse = new Event.ResponseStarted(new TestResponseInfo(413));
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 16 * 1024L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            sink.write(new byte[8 * 1024]);
            sink.flush();
            firstChunkComplete.countDown();
            try {
              assertThat(secondReadAndResponseQueued.await(5, SECONDS)).isTrue();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new AssertionError(e);
            }
            // Write fewer than the remaining 8 KiB bytes and return normally; close() must surface
            // the queued 413 response rather than committing secondSink and failing with underflow.
            sink.write(new byte[100]);
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);
    TestUploadDataSink firstSink = new TestUploadDataSink();
    TestUploadDataSink secondSink = new TestUploadDataSink();
    provider.read(firstSink, ByteBuffer.allocateDirect(8 * 1024));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();

    Future<Void> cronetQueued =
        executor.submit(
            () -> {
              assertThat(firstChunkComplete.await(5, SECONDS)).isTrue();
              assertThat(firstSink.waitForCallback()).isFalse();
              provider.read(secondSink, ByteBuffer.allocateDirect(8 * 1024));
              events.add(earlyResponse);
              secondReadAndResponseQueued.countDown();
              return null;
            });

    assertThat(provider.handleUploadEvent(firstRead)).isSameInstanceAs(earlyResponse);
    cronetQueued.get();
    assertThat(secondSink.isDone()).isFalse();

    provider.onTerminalEvent();
    assertThrows(IOException.class, secondSink::waitForCallback);
  }

  @Test
  public void testWriteBody_abortedByEarlyRedirect_returnsRedirectBeforeTrailingCanceled()
      throws Exception {
    Event.RedirectReceived earlyRedirect = new Event.RedirectReceived(new TestResponseInfo(303));
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 16 * 1024L;
          }

          @Override
          public void writeTo(BufferedSink sink) {
            try {
              sink.write(new byte[8 * 1024]);
              sink.flush();
            } catch (IOException ignored) {
              // Expected.
            }
            try {
              sink.write(new byte[8 * 1024]);
              sink.flush();
            } catch (IOException ignored) {
              // Expected.
            }
            try {
              sink.close();
            } catch (IOException ignored) {
              // Expected.
            }
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);
    TestUploadDataSink firstSink = new TestUploadDataSink();
    provider.read(firstSink, ByteBuffer.allocateDirect(8 * 1024));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();
    // If a trailing Canceled event arrives after RedirectReceived, writeBody() must still return
    // the first non-read event.
    events.add(earlyRedirect);
    events.add(Event.Canceled.INSTANCE);

    Event returnedEvent = provider.handleUploadEvent(firstRead);
    assertThat(returnedEvent).isSameInstanceAs(earlyRedirect);
    assertThat(firstSink.isDone()).isFalse();
  }

  @Test
  public void
      testWriteBody_abortedByEarlyResponse_writeToWrapsAbortIOException_propagatesWrappedException()
          throws Exception {
    Event.ResponseStarted earlyResponse = new Event.ResponseStarted(new TestResponseInfo(413));
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 16 * 1024L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            try {
              sink.write(new byte[8 * 1024]);
              sink.flush();
            } catch (IOException e) {
              throw new IOException("Wrapped abort exception", e);
            }
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);
    TestUploadDataSink firstSink = new TestUploadDataSink();
    provider.read(firstSink, ByteBuffer.allocateDirect(8 * 1024));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();
    events.add(earlyResponse);

    IOException thrown =
        assertThrows(IOException.class, () -> provider.handleUploadEvent(firstRead));
    assertThat(thrown).hasMessageThat().isEqualTo("Wrapped abort exception");
    assertThat(firstSink.isDone()).isFalse();

    provider.onTerminalEvent();
    assertThrows(IOException.class, firstSink::waitForCallback);
  }

  @Test
  public void testWriteBody_abortExceptionIncludesEventContextAndCause() throws Exception {
    CronetException cronetFailure = new CronetException("net::ERR_CONNECTION_RESET", null) {};
    class AbortCase {
      final Event abortEvent;
      final String expectedMessage;
      @Nullable final Throwable expectedCause;

      AbortCase(Event abortEvent, String expectedMessage, @Nullable Throwable expectedCause) {
        this.abortEvent = abortEvent;
        this.expectedMessage = expectedMessage;
        this.expectedCause = expectedCause;
      }
    }

    List<AbortCase> testCases =
        Arrays.asList(
            new AbortCase(
                new Event.ResponseStarted(new TestResponseInfo(413)),
                "Upload aborted because response started (HTTP 413)",
                null),
            new AbortCase(
                new Event.RedirectReceived(new TestResponseInfo(303)),
                "Upload aborted because redirect received (HTTP 303)",
                null),
            new AbortCase(
                new Event.UploadRewind(new TestUploadDataSink()),
                "Upload aborted because upload rewind was requested",
                null),
            new AbortCase(
                new Event.Failed(cronetFailure),
                "Upload aborted because request failed",
                cronetFailure),
            new AbortCase(
                Event.Canceled.INSTANCE, "Upload aborted because request was canceled", null));

    for (AbortCase testCase : testCases) {
      List<IOException> observedExceptions = new ArrayList<>();
      RequestBody requestBody =
          new RequestBody() {
            @Override
            @Nullable
            public MediaType contentType() {
              return null;
            }

            @Override
            public long contentLength() {
              return 16 * 1024L;
            }

            @Override
            public void writeTo(BufferedSink sink) {
              try {
                sink.write(new byte[8 * 1024]);
                sink.flush();
              } catch (IOException e) {
                observedExceptions.add(e);
              }
              try {
                sink.flush();
              } catch (IOException e) {
                observedExceptions.add(e);
              }
            }
          };
      RequestBodyConverter provider = createProvider(requestBody);
      TestUploadDataSink firstSink = new TestUploadDataSink();
      provider.read(firstSink, ByteBuffer.allocateDirect(8 * 1024));
      Event.UploadRead firstRead = (Event.UploadRead) events.take();
      events.add(testCase.abortEvent);

      assertThat(provider.handleUploadEvent(firstRead)).isSameInstanceAs(testCase.abortEvent);
      assertThat(observedExceptions).hasSize(2);
      assertThat(observedExceptions.get(0)).isNotSameInstanceAs(observedExceptions.get(1));
      for (IOException exception : observedExceptions) {
        assertThat(exception).hasMessageThat().isEqualTo(testCase.expectedMessage);
        assertThat(exception).hasCauseThat().isSameInstanceAs(testCase.expectedCause);
      }
    }
  }

  @Test
  public void testWriteBody_uncheckedErrorAfterAbort_isNotSwallowed() throws Exception {
    CountDownLatch firstChunkComplete = new CountDownLatch(1);
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 16 * 1024L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            sink.write(new byte[8 * 1024]);
            sink.flush();
            firstChunkComplete.countDown();
            try {
              sink.write(new byte[8 * 1024]);
              sink.flush();
            } finally {
              throw new OutOfMemoryError("OOM in finally");
            }
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);
    TestUploadDataSink firstSink = new TestUploadDataSink();
    provider.read(firstSink, ByteBuffer.allocateDirect(8 * 1024));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();

    Future<Void> cancelEnqueued =
        executor.submit(
            () -> {
              assertThat(firstChunkComplete.await(5, SECONDS)).isTrue();
              assertThat(firstSink.waitForCallback()).isFalse();
              events.add(Event.Canceled.INSTANCE);
              return null;
            });

    OutOfMemoryError error =
        assertThrows(OutOfMemoryError.class, () -> provider.handleUploadEvent(firstRead));
    assertThat(error).hasMessageThat().isEqualTo("OOM in finally");
    cancelEnqueued.get();
  }

  @Test
  public void testWriteTo_useBlockThrowsFileNotFoundOnFixedLengthBody_preservesPrimaryException()
      throws Exception {
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 1024L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            try (BufferedSink ignored = sink) {
              throw new FileNotFoundException("missing.bin");
            }
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);

    FileNotFoundException exception =
        assertThrows(FileNotFoundException.class, () -> readAll(provider));
    assertThat(exception).hasMessageThat().isEqualTo("missing.bin");
  }

  @Test
  public void
      testWriteTo_useBlockThrowsFileNotFoundAfterClosingUnknownLengthBody_rethrowsDirectly()
          throws Exception {
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return -1L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            try (BufferedSink ignored = sink) {
              // Commits 0-byte chunked body via onReadSucceeded(true).
            }
            events.add(new Event.ResponseStarted(new TestResponseInfo(200)));
            throw new FileNotFoundException("Corrupt input trailer");
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);
    TestUploadDataSink firstSink = new TestUploadDataSink();
    provider.read(firstSink, ByteBuffer.allocateDirect(8192));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();

    FileNotFoundException thrown =
        assertThrows(FileNotFoundException.class, () -> provider.handleUploadEvent(firstRead));
    assertThat(thrown).hasMessageThat().isEqualTo("Corrupt input trailer");
    assertThat(firstSink.waitForCallback()).isTrue();
  }

  @Test
  public void testWriteTo_javaTryWithResourcesCloseAfterFailureAndAbort_doesNotSelfSuppress()
      throws Exception {
    // 1. Overflow failure inside Java try-with-resources OutputStream close
    RequestBody overflowBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 4L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            try (OutputStream out = sink.outputStream()) {
              out.write(new byte[16 * 1024]);
            }
          }
        };
    RequestBodyConverter overflowProvider = createProvider(overflowBody);
    IOException overflowError =
        assertThrows(IOException.class, () -> readAll(overflowProvider));
    assertThat(overflowError).hasMessageThat().contains("Expected 4 bytes");

    // 2. Early-response abort inside Java try-with-resources OutputStream close
    Event.ResponseStarted earlyResponse = new Event.ResponseStarted(new TestResponseInfo(413));
    RequestBody abortBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 32 * 1024L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            try (OutputStream out = sink.outputStream()) {
              out.write(new byte[16 * 1024]);
            }
          }
        };
    RequestBodyConverter abortProvider = createProvider(abortBody);
    TestUploadDataSink firstSink = new TestUploadDataSink();
    abortProvider.read(firstSink, ByteBuffer.allocateDirect(8 * 1024));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();
    events.add(earlyResponse);

    assertThat(abortProvider.handleUploadEvent(firstRead)).isSameInstanceAs(earlyResponse);
    assertThat(firstSink.isDone()).isFalse();
  }

  @Test
  public void testKnownLength_actualBodyTooShort_writeToClosesSinkAndSwallowsException()
      throws Exception {
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
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
              // Simulate closeQuietly swallowing the body-too-short IOException.
            }
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);

    IOException exception = assertThrows(IOException.class, () -> readAll(provider));
    assertThat(exception).hasMessageThat().contains("The source has been exhausted");
  }

  @Test
  public void testKnownLength_actualBodyTooLong_writeToSwallowsException() throws Exception {
    AtomicReference<BufferedSink> capturedBufferedSink = new AtomicReference<>();
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 8191L;
          }

          @Override
          public void writeTo(BufferedSink sink) {
            capturedBufferedSink.set(sink);
            try {
              sink.write(new byte[16384]);
            } catch (IOException ignored) {
              // Swallow the body-too-long IOException without closing sink; bufferedSink still
              // holds the 16 KiB of unflushed bytes.
            }
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);

    IOException exception = assertThrows(IOException.class, () -> readAll(provider));
    assertThat(exception).hasMessageThat().contains("Expected 8191 bytes but got at least 16384");
    assertThat(capturedBufferedSink.get().isOpen()).isFalse();
  }

  @Test
  public void testKnownLength_actualBodyTooLong_writeToSwallowsAndRetriesWritesAndClose()
      throws Exception {
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 8191L;
          }

          @Override
          public void writeTo(BufferedSink sink) {
            try {
              sink.write(new byte[16384]);
            } catch (IOException ignored) {
              // Expected.
            }
            try {
              sink.write(new byte[100]);
              sink.flush();
            } catch (IOException ignored) {
              // Expected.
            }
            try {
              sink.close();
            } catch (IOException ignored) {
              // Expected.
            }
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);

    IOException exception = assertThrows(IOException.class, () -> readAll(provider));
    assertThat(exception).hasMessageThat().contains("Expected 8191 bytes but got at least 16384");
  }

  @Test
  public void testUploadFailureBetweenChunks_throwsImmediatelyWithoutWaitingForNextRead()
      throws Exception {
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return 16383L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            sink.write(new byte[8192]);
            sink.flush();
            // Overflows contentLength on the second chunk while activeUploadEvent == null and
            // Cronet never issues a second provider.read().
            sink.write(new byte[8192]);
            sink.flush();
          }
        };
    RequestBodyConverter provider = createProvider(requestBody, 60_000);
    TestUploadDataSink firstSink = new TestUploadDataSink();
    provider.read(firstSink, ByteBuffer.allocateDirect(8192));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();

    IOException exception =
        assertThrows(IOException.class, () -> provider.handleUploadEvent(firstRead));
    assertThat(exception).hasMessageThat().contains("Expected 16383 bytes but got at least 16384");
    assertThat(firstSink.waitForCallback()).isFalse();
  }

  @Test
  public void testWriteTo_throwsIOExceptionAfterClosingCompleteBody_rethrowsDirectly()
      throws Exception {
    byte[] content = new byte[1024];
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return content.length;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            sink.write(content);
            sink.close();
            throw new IOException("Post-close failure");
          }
        };
    RequestBodyConverter provider = createProvider(requestBody);
    TestUploadDataSink firstSink = new TestUploadDataSink();
    provider.read(firstSink, ByteBuffer.allocateDirect(8192));
    Event.UploadRead firstRead = (Event.UploadRead) events.take();

    IOException thrown =
        assertThrows(IOException.class, () -> provider.handleUploadEvent(firstRead));
    assertThat(thrown).hasMessageThat().isEqualTo("Post-close failure");
    assertThat(firstSink.waitForCallback()).isFalse();
  }

  @Test
  public void testWriteBody_writeToThrowsWithBufferedBytesOnChunkedBody_doesNotFlushCommitOrBlock()
      throws Exception {
    // 1. writeTo() buffers < 8 KiB while firstRead is active and throws: BlackHole must discard the
    // buffered bytes without flushing them to firstRead or committing onReadSucceeded(true).
    AtomicReference<BufferedSink> capturedBufferedSink1 = new AtomicReference<>();
    RequestBody body1 =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return -1L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            capturedBufferedSink1.set(sink);
            sink.write(new byte[100]);
            throw new FileNotFoundException("Disk read failure with buffered bytes");
          }
        };
    RequestBodyConverter provider1 = createProvider(body1, 60_000);
    TestUploadDataSink firstSink1 = new TestUploadDataSink();
    ByteBuffer firstBuffer1 = ByteBuffer.allocateDirect(8 * 1024);
    provider1.read(firstSink1, firstBuffer1);
    Event.UploadRead firstRead1 = (Event.UploadRead) events.take();

    FileNotFoundException thrown1 =
        assertThrows(FileNotFoundException.class, () -> provider1.handleUploadEvent(firstRead1));
    assertThat(thrown1).hasMessageThat().isEqualTo("Disk read failure with buffered bytes");
    assertThat(thrown1.getSuppressed()).isEmpty();
    assertThat(capturedBufferedSink1.get().isOpen()).isFalse();
    assertThat(firstBuffer1.position()).isEqualTo(0);
    assertThat(firstSink1.isDone()).isFalse();

    // 2. writeTo() flushes chunk 1 (leaving activeUploadEvent == null), buffers < 8 KiB for
    // chunk 2, and throws: BlackHole must prevent bufferedSink.close() from blocking in
    // obtainActiveRead().
    AtomicReference<BufferedSink> capturedBufferedSink2 = new AtomicReference<>();
    RequestBody body2 =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return -1L;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            capturedBufferedSink2.set(sink);
            sink.write(new byte[8 * 1024]);
            sink.flush();
            sink.write(new byte[100]);
            throw new FileNotFoundException("Disk read failure between chunks");
          }
        };
    RequestBodyConverter provider2 = createProvider(body2, 60_000);
    TestUploadDataSink firstSink2 = new TestUploadDataSink();
    provider2.read(firstSink2, ByteBuffer.allocateDirect(8 * 1024));
    Event.UploadRead firstRead2 = (Event.UploadRead) events.take();

    FileNotFoundException thrown2 =
        assertThrows(FileNotFoundException.class, () -> provider2.handleUploadEvent(firstRead2));
    assertThat(thrown2).hasMessageThat().isEqualTo("Disk read failure between chunks");
    assertThat(thrown2.getSuppressed()).isEmpty();
    assertThat(capturedBufferedSink2.get().isOpen()).isFalse();
    assertThat(firstSink2.waitForCallback()).isFalse();
  }

  @Test
  public void testWriteBody_multipleCloseCallsAreIdempotent_andWriteOrFlushAfterCloseThrows()
      throws Exception {
    byte[] content = TestUtils.generateRandomBytesArray(1024);
    RequestBody multiCloseBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return content.length;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            sink.write(content);
            sink.close();
            sink.close();
          }
        };
    assertThat(readAll(createProvider(multiCloseBody))).isEqualTo(content);

    RequestBody writeAfterCloseBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            return content.length;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            sink.write(content);
            sink.close();
            sink.writeByte(1);
          }
        };
    IllegalStateException writeAfterCloseError =
        assertThrows(
            IllegalStateException.class, () -> readAll(createProvider(writeAfterCloseBody)));
    assertThat(writeAfterCloseError).hasMessageThat().isEqualTo("closed");
  }

  @Test
  public void testContentLength_notCalledByProvider() throws Exception {
    byte[] content = TestUtils.generateRandomBytesArray(1024);
    RequestBody requestBody =
        new RequestBody() {
          @Override
          @Nullable
          public MediaType contentType() {
            return null;
          }

          @Override
          public long contentLength() {
            throw new AssertionError(
                "contentLength() should not be called by RequestBodyConverter");
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            sink.write(content);
          }
        };
    RequestBodyConverter provider =
        new RequestBodyConverter(requestBody, content.length, NO_TIMEOUT, events);

    assertThat(readAll(provider)).isEqualTo(content);
  }

  private RequestBodyConverter createProvider(RequestBody requestBody) throws IOException {
    return createProvider(requestBody, NO_TIMEOUT);
  }

  private RequestBodyConverter createProvider(RequestBody requestBody, long writeTimeoutMillis)
      throws IOException {
    return new RequestBodyConverter(
        requestBody, requestBody.contentLength(), writeTimeoutMillis, events);
  }

  private void rewind(RequestBodyConverter provider) throws Exception {
    Future<Void> rewindFuture =
        executor.submit(
            () -> {
              TestUploadDataSink sink = new TestUploadDataSink();
              provider.rewind(sink);
              sink.waitForRewindCallback();
              return null;
            });
    Event.UploadRewind event = (Event.UploadRewind) events.take();
    assertThat(provider.handleUploadEvent(event)).isNull();
    try {
      rewindFuture.get();
    } catch (ExecutionException e) {
      throw propagateCause(e);
    }
  }

  private void rewindAfterPartialRead(RequestBodyConverter provider) throws Exception {
    Future<Void> cronetFuture =
        executor.submit(
            () -> {
              TestUploadDataSink readSink = new TestUploadDataSink();
              provider.read(readSink, ByteBuffer.allocateDirect(8 * 1024));
              assertThat(readSink.waitForCallback()).isFalse();

              TestUploadDataSink rewindSink = new TestUploadDataSink();
              provider.rewind(rewindSink);
              rewindSink.waitForRewindCallback();
              return null;
            });

    Event.UploadRead firstRead = (Event.UploadRead) events.take();
    Event abortEvent = provider.handleUploadEvent(firstRead);
    assertThat(abortEvent).isInstanceOf(Event.UploadRewind.class);
    assertThat(provider.handleUploadEvent((Event.UploadRewind) abortEvent)).isNull();
    cronetFuture.get();
  }

  /**
   * Simulates Cronet reading the entire body on {@link #executor} while the calling thread drives
   * {@link RequestBodyConverter#handleUploadEvent}.
   */
  private byte[] readAll(RequestBodyConverter provider) throws Exception {
    CompletableFuture<Boolean> canceled = new CompletableFuture<>();
    Future<byte[]> cronetFuture =
        executor.submit(
            () -> {
              byte[] bytes = cronetReadLoop(provider, canceled);
              events.add(Event.Succeeded.INSTANCE);
              return bytes;
            });
    try {
      Event nextEvent = null;
      while (true) {
        Event event = nextEvent != null ? nextEvent : events.take();
        nextEvent = null;
        if (event instanceof Event.Upload) {
          nextEvent = provider.handleUploadEvent((Event.Upload) event);
        } else if (event instanceof Event.Terminal) {
          provider.onTerminalEvent();
          break;
        } else {
          break;
        }
      }
    } catch (Throwable t) {
      // Mirrors waitForResponseHeaders() calling urlRequest.cancel() and draining on upload
      // failure.
      canceled.completeExceptionally(new CancellationException("Request canceled"));
      try {
        cronetFuture.get();
      } catch (ExecutionException ignored) {
        // Expected.
      }
      while (true) {
        Event queued = events.poll();
        if (queued == null) {
          break;
        }
        if (queued instanceof Event.Upload) {
          provider.setActiveUploadEvent((Event.Upload) queued);
        }
      }
      provider.onTerminalEvent();
      Throwables.throwIfInstanceOf(t, Exception.class);
      Throwables.throwIfUnchecked(t);
      throw new AssertionError(t);
    }
    try {
      return cronetFuture.get();
    } catch (ExecutionException e) {
      throw propagateCause(e);
    }
  }

  /** Reads the entire body from the given {@link UploadDataProvider} and returns it as bytes. */
  private static byte[] cronetReadLoop(
      UploadDataProvider uploadDataProvider, CompletableFuture<Boolean> canceled) throws Exception {
    Buffer buffer = new Buffer();
    ByteBuffer byteBuffer = ByteBuffer.allocateDirect(8 * 1024);

    long length = uploadDataProvider.getLength();
    boolean isFinalChunk = false;

    // For chunked uploads (length == -1), read until the final chunk is received.
    // For non-chunked uploads (length != -1), read until buffer size reaches content length.
    while ((length == -1 && !isFinalChunk) || (length != -1 && buffer.size() < length)) {
      byteBuffer.clear();

      TestUploadDataSink sink = new TestUploadDataSink(canceled);
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

  private static RuntimeException propagateCause(ExecutionException e) throws Exception {
    Throwable cause = e.getCause();
    Throwables.throwIfInstanceOf(cause, Exception.class);
    Throwables.throwIfUnchecked(cause);
    throw new AssertionError(cause);
  }

  private static final class TestRequestBody extends RequestBody {

    private final byte[] content;
    private final long contentLength;
    private final boolean closeSinkAfterWrite;
    private final boolean isOneShot;

    TestRequestBody(byte[] content) {
      this(content, content.length, false, false);
    }

    TestRequestBody(byte[] content, long contentLength) {
      this(content, contentLength, false, false);
    }

    TestRequestBody(byte[] content, long contentLength, boolean closeSinkAfterWrite) {
      this(content, contentLength, closeSinkAfterWrite, false);
    }

    TestRequestBody(
        byte[] content, long contentLength, boolean closeSinkAfterWrite, boolean isOneShot) {
      this.content = content;
      this.contentLength = contentLength;
      this.closeSinkAfterWrite = closeSinkAfterWrite;
      this.isOneShot = isOneShot;
    }

    @Override
    @Nullable
    public MediaType contentType() {
      return null;
    }

    @Override
    public long contentLength() throws IOException {
      return contentLength;
    }

    @Override
    public boolean isOneShot() {
      return isOneShot;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
      sink.write(content);
      if (closeSinkAfterWrite) {
        sink.close();
      }
    }
  }

  private static final class TestResponseInfo extends UrlResponseInfo {
    private final int statusCode;

    TestResponseInfo(int statusCode) {
      this.statusCode = statusCode;
    }

    @Override
    public String getUrl() {
      return "https://example.com";
    }

    @Override
    public List<String> getUrlChain() {
      return Collections.singletonList("https://example.com");
    }

    @Override
    public int getHttpStatusCode() {
      return statusCode;
    }

    @Override
    public String getHttpStatusText() {
      return "";
    }

    @Override
    public List<Map.Entry<String, String>> getAllHeadersAsList() {
      return Collections.emptyList();
    }

    @Override
    public Map<String, List<String>> getAllHeaders() {
      return Collections.emptyMap();
    }

    @Override
    public boolean wasCached() {
      return false;
    }

    @Override
    public String getNegotiatedProtocol() {
      return "h2";
    }

    @Override
    public String getProxyServer() {
      return ":0";
    }

    @Override
    public long getReceivedByteCount() {
      return 0;
    }
  }

  private static final class TestUploadDataSink extends UploadDataSink {
    private final CompletableFuture<Boolean> canceled;
    private final CompletableFuture<Boolean> result = new CompletableFuture<>();
    private final CompletableFuture<Void> rewindResult = new CompletableFuture<>();
    private final AtomicReference<Thread> callbackThread = new AtomicReference<>();

    TestUploadDataSink() {
      this(new CompletableFuture<>());
    }

    TestUploadDataSink(CompletableFuture<Boolean> canceled) {
      this.canceled = canceled;
    }

    @Override
    public void onReadSucceeded(boolean finalChunk) {
      callbackThread.set(Thread.currentThread());
      result.complete(finalChunk);
      rewindResult.completeExceptionally(new AssertionError("Unexpected onReadSucceeded"));
    }

    @Override
    public void onReadError(Exception exception) {
      callbackThread.set(Thread.currentThread());
      result.completeExceptionally(exception);
      rewindResult.completeExceptionally(new AssertionError("Unexpected onReadError", exception));
    }

    boolean isDone() {
      return result.isDone() || rewindResult.isDone();
    }

    /** Waits for {@link #onReadSucceeded(boolean)} to be called and returns {@code finalChunk}. */
    boolean waitForCallback() throws Exception {
      try {
        return (Boolean) CompletableFuture.anyOf(result, canceled).get();
      } catch (ExecutionException e) {
        throw propagateCause(e);
      }
    }

    @Override
    public void onRewindSucceeded() {
      callbackThread.set(Thread.currentThread());
      rewindResult.complete(null);
      result.completeExceptionally(new AssertionError("Unexpected onRewindSucceeded"));
    }

    @Override
    public void onRewindError(Exception exception) {
      callbackThread.set(Thread.currentThread());
      rewindResult.completeExceptionally(exception);
      result.completeExceptionally(new AssertionError("Unexpected onRewindError", exception));
    }

    void waitForRewindCallback() throws Exception {
      try {
        rewindResult.get();
      } catch (ExecutionException e) {
        throw propagateCause(e);
      }
    }
  }
}
