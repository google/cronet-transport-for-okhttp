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

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.common.base.Verify;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.net.cronet.okhttptransport.UploadBodyDataBroker.ReadResult;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import okhttp3.RequestBody;
import okio.BufferedSink;
import okio.Okio;
import org.chromium.net.UploadDataProvider;
import org.chromium.net.UploadDataSink;

/**
 * An {@link UploadDataProvider} that bridges OkHttp's {@link RequestBody} to Cronet without holding
 * the entire request body in memory.
 *
 * <ol>
 *   <li>{@link RequestBody#writeTo(BufferedSink)} is invoked on the body, but the sink doesn't
 *       accept any data
 *   <li>A call to {@link UploadDataProvider#read(UploadDataSink, ByteBuffer)} unblocks the sink,
 *       which accepts a part of the body (size depends on the buffer's capacity), then blocks
 *       again. Buffer is sent to Cronet.
 * </ol>
 *
 * This is repeated until the entire body has been read.
 */
final class RequestBodyConverter extends UploadDataProvider {
  private final RequestBody okHttpRequestBody;
  private UploadBodyDataBroker broker = new UploadBodyDataBroker();
  private final ListeningExecutorService readTaskExecutor;
  private final long writeTimeoutMillis;

  /** The future for the task that reads the OkHttp request body in the background. */
  private ListenableFuture<?> readTaskFuture;

  /** The number of bytes we read from the OkHttp body thus far. */
  private long totalBytesReadFromOkHttp;

  RequestBodyConverter(
      RequestBody okHttpRequestBody,
      ExecutorService readTaskExecutor,
      int writeTimeoutMillis) {
    this.okHttpRequestBody = okHttpRequestBody;
    if (readTaskExecutor instanceof ListeningExecutorService) {
      this.readTaskExecutor = (ListeningExecutorService) readTaskExecutor;
    } else {
      this.readTaskExecutor = MoreExecutors.listeningDecorator(readTaskExecutor);
    }

    // So that we don't have to special case infinity. Int.MAX_VALUE is ~infinity for all
    // practical use cases.
    this.writeTimeoutMillis = writeTimeoutMillis == 0 ? Integer.MAX_VALUE : writeTimeoutMillis;
  }

  @Override
  public long getLength() throws IOException {
    return okHttpRequestBody.contentLength();
  }

  @Override
  public void read(UploadDataSink uploadDataSink, ByteBuffer byteBuffer) throws IOException {
    ensureReadTaskStarted();

    if (getLength() == -1) {
      readUnknownBodyLength(uploadDataSink, byteBuffer);
    } else {
      readKnownBodyLength(uploadDataSink, byteBuffer);
    }
  }

  private void readKnownBodyLength(UploadDataSink uploadDataSink, ByteBuffer byteBuffer)
      throws IOException {
    try {
      UploadBodyDataBroker.ReadResult readResult = readFromOkHttp(byteBuffer);

      if (totalBytesReadFromOkHttp > getLength()) {
        throw prepareBodyTooLongException(getLength(), totalBytesReadFromOkHttp);
      }

      if (totalBytesReadFromOkHttp < getLength()) {
        switch (readResult) {
          case SUCCESS:
            uploadDataSink.onReadSucceeded(false);
            break;
          case END_OF_BODY:
            throw new IOException("The source has been exhausted but we expected more data!");
        }
        return;
      }
      // Else we're handling what's supposed to be the last chunk
      handleLastBodyRead(uploadDataSink, byteBuffer);

    } catch (TimeoutException | ExecutionException e) {
      readTaskFuture.cancel(true);
      uploadDataSink.onReadError(new IOException(e));
    }
  }

  /**
   * The last body read is special for fixed length bodies - if Cronet receives exactly the right
   * amount of data it won't ask for more, even if there is more data in the stream. As a result,
   * when we read the advertised number of bytes, we need to make sure that the stream is indeed
   * finished.
   */
  private void handleLastBodyRead(UploadDataSink uploadDataSink, ByteBuffer filledByteBuffer)
      throws IOException, TimeoutException, ExecutionException {
    // We reuse the same buffer for the END_OF_DATA read (it should be non-destructive and if
    // it overwrites what's in there we don't mind as that's an error anyway). We just need
    // to make sure we restore the original position afterwards. We don't use mark() / reset()
    // as the mark position can be invalidated by limit manipulation.
    int bufferPosition = filledByteBuffer.position();
    filledByteBuffer.position(0);

    UploadBodyDataBroker.ReadResult readResult = readFromOkHttp(filledByteBuffer);

    if (!readResult.equals(ReadResult.END_OF_BODY)) {
      throw prepareBodyTooLongException(getLength(), totalBytesReadFromOkHttp);
    }

    Verify.verify(
        filledByteBuffer.position() == 0,
        "END_OF_BODY reads shouldn't write anything to the buffer");

    // revert the position change
    filledByteBuffer.position(bufferPosition);

    uploadDataSink.onReadSucceeded(false);
  }

  private void readUnknownBodyLength(UploadDataSink uploadDataSink, ByteBuffer byteBuffer) {
    try {
      UploadBodyDataBroker.ReadResult readResult = readFromOkHttp(byteBuffer);
      uploadDataSink.onReadSucceeded(readResult.equals(ReadResult.END_OF_BODY));
    } catch (TimeoutException | ExecutionException e) {
      readTaskFuture.cancel(true);
      uploadDataSink.onReadError(new IOException(e));
    }
  }

  /**
   * Starts a background task to write OkHttp's request body to Cronet.
   *
   * <p>Calling this method will start a background task in which OkHttp will start writing the
   * request body to Cronet.
   *
   * <p>This method is idempotent. Calling it more than once has no effect. It is crucial, however,
   * to ensure it is called at least once - otherwise the request body will never be written to
   * Cronet.
   *
   * <p>This method is not thread-safe and is not expected to be called concurrently.
   */
  private void ensureReadTaskStarted() {
    // We don't expect concurrent calls so a simple flag is sufficient
    if (readTaskFuture == null) {
      final UploadBodyDataBroker currentBroker = broker;
      readTaskFuture =
          readTaskExecutor.submit(
              (Callable<Void>)
                  () -> {
                    BufferedSink bufferedSink = Okio.buffer(currentBroker);
                    okHttpRequestBody.writeTo(bufferedSink);
                    // The user (RequestBody#writeTo) is done writing the request body.
                    // At this point, the user might or might not have closed the sink.
                    // Assume the sink might be already closed, so flush() cannot be called.

                    // Close the sink. Upon the first call, close() will flush any
                    // bytes remaining in the buffer, and will signal Cronet that we're done
                    // writing the request body. Subsequent calls are no-ops. It is crucial to
                    // call close() at least once. Otherwise, Cronet will be stuck waiting for
                    // more data to come, which will never come. This will result in a timeout.
                    bufferedSink.close();

                    return null;
                  });

      Futures.addCallback(
          readTaskFuture,
          new FutureCallback<Object>() {
            @Override
            public void onSuccess(Object result) {}

            @Override
            public void onFailure(Throwable t) {
              currentBroker.setBackgroundReadError(t);
            }
          },
          MoreExecutors.directExecutor());
    }
  }

  private ReadResult readFromOkHttp(ByteBuffer byteBuffer)
      throws TimeoutException, ExecutionException {
    int positionBeforeRead = byteBuffer.position();
    UploadBodyDataBroker.ReadResult readResult =
        Uninterruptibles.getUninterruptibly(
            broker.enqueueBodyRead(byteBuffer), writeTimeoutMillis, MILLISECONDS);
    int bytesRead = byteBuffer.position() - positionBeforeRead;
    totalBytesReadFromOkHttp += bytesRead;
    return readResult;
  }

  private static IOException prepareBodyTooLongException(
      long expectedLength, long minActualLength) {
    return new IOException(
        "Expected " + expectedLength + " bytes but got at least " + minActualLength);
  }

  @Override
  public void rewind(UploadDataSink uploadDataSink) {
    if (okHttpRequestBody.isOneShot()) {
      uploadDataSink.onRewindError(new UnsupportedOperationException("Body is one-shot"));
      return;
    }

    // Interrupt the okHttpRequestBody.writeTo() call, if any. This hopefully compels the user's
    // RequestBody.writeTo() implementation to stop producing data that's going nowhere.
    //
    // Note: there may be subtle differences in behaviour with OkHttp here. If the thread gets
    // interrupted in UploadBodyDataBroker.write() while waiting for the next read() call from
    // Cronet (getPendingCronetRead()) then write() will throw, which is similar to what
    // OkHttp would do (although the exception type may differ). On the other hand, if the
    // thread is interrupted while UploadBodyDataBroker is waiting for the next write() call,
    // then it's the user code (okHttpRequestBody.writeTo()) that gets interrupted, which is
    // something that would not happen in standard OkHttp. This approach is a bit more efficient
    // because it stops user code from producing a pointless chunk that's going nowhere, at the
    // cost of a small deviation from standard OkHttp behavior.
    if (readTaskFuture != null) {
      readTaskFuture.cancel(true);
      readTaskFuture = null;
    }
    totalBytesReadFromOkHttp = 0;
    // Swap out the broker so that any ongoing writes or errors (especially interruption errors)
    // are thrown away and do not affect the post-rewind state.
    broker = new UploadBodyDataBroker();

    uploadDataSink.onRewindSucceeded();
  }
}
