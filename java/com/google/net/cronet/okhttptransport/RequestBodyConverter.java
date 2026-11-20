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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.min;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.net.cronet.okhttptransport.OkHttpBridgeRequestCallback.Event;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import javax.annotation.Nullable;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.Okio;
import okio.Sink;
import okio.Timeout;
import org.chromium.net.UploadDataProvider;
import org.chromium.net.UploadDataSink;

/**
 * An {@link UploadDataProvider} that streams OkHttp's {@link RequestBody} on the calling (user)
 * thread without holding the entire body in memory or spawning a background thread:
 *
 * <ol>
 *   <li>{@link #getLength}, {@link #read}, and {@link #rewind} hold no mutable state and only
 *       enqueue events onto {@code events}.
 *   <li>{@link #handleUploadEvent} executes exclusively on the user thread, confining all upload
 *       state and {@link UploadDataSink} completions to a single thread with zero locks.
 *   <li>Upload failures throw directly out of {@link #handleUploadEvent} so {@code
 *       waitForResponseHeaders} immediately cancels the {@code UrlRequest}, while non-read {@link
 *       Event}s (such as early HTTP responses) abort {@link #writeBody} and return the {@link
 *       Event} without cancelling {@code UrlRequest}.
 * </ol>
 */
final class RequestBodyConverter extends UploadDataProvider {
  private final RequestBody okHttpRequestBody;
  private final long contentLength;
  private final long writeTimeoutMillis;
  private final BlockingQueue<Event> events;

  @Nullable private Event.Upload activeUploadEvent = null;

  RequestBodyConverter(
      RequestBody okHttpRequestBody,
      long contentLength,
      long writeTimeoutMillis,
      BlockingQueue<Event> events) {
    checkArgument(writeTimeoutMillis >= 0);
    this.okHttpRequestBody = okHttpRequestBody;
    this.contentLength = contentLength;
    this.writeTimeoutMillis = writeTimeoutMillis == 0L ? Long.MAX_VALUE : writeTimeoutMillis;
    this.events = events;
  }

  @Override
  public long getLength() {
    return contentLength;
  }

  @Override
  public void read(UploadDataSink uploadDataSink, ByteBuffer byteBuffer) {
    events.add(new Event.UploadRead(uploadDataSink, byteBuffer));
  }

  @Override
  public void rewind(UploadDataSink uploadDataSink) {
    events.add(new Event.UploadRewind(uploadDataSink));
  }

  /**
   * Handles an {@link Event.Upload} on the calling thread by executing the corresponding {@link
   * RequestBody} operation ({@link RequestBody#writeTo} for {@link Event.UploadRead}, or {@link
   * RequestBody#isOneShot} for {@link Event.UploadRewind}) and completing its {@link
   * UploadDataSink}.
   *
   * <p>Unlike {@link #setActiveUploadEvent}, this method actively processes the upload and invokes
   * user {@link RequestBody} code; it must only be called while the request is proceeding normally
   * (not while canceling or draining).
   *
   * @return {@code null} if the upload event completed normally, or the non-{@link
   *     Event.UploadRead} {@link Event} that interrupted a read (such as {@link
   *     Event.ResponseStarted}, {@link Event.RedirectReceived}, {@link Event.UploadRewind}, {@link
   *     Event.Failed}, or {@link Event.Canceled}) so the caller can process it next.
   * @throws IOException if a client-side upload failure occurs during {@link Event.UploadRead} —
   *     specifically, if {@link RequestBody#writeTo} throws an {@link IOException}, writes fewer or
   *     more bytes than {@link #contentLength}, or waiting for Cronet's next read times out ({@link
   *     CronetTimeoutException}) — even if {@link RequestBody#writeTo} catches and swallows the
   *     sink's exception. Any other {@link Throwable} thrown by {@link RequestBody#writeTo}, {@link
   *     RequestBody#isOneShot}, or the sink also propagates directly (even if swallowed by {@link
   *     RequestBody#writeTo}).
   */
  @Nullable
  Event handleUploadEvent(Event.Upload event) throws IOException {
    if (event instanceof Event.UploadRead) {
      return writeBody((Event.UploadRead) event);
    } else if (event instanceof Event.UploadRewind) {
      rewindBody((Event.UploadRewind) event);
      return null;
    } else {
      throw new IllegalStateException("Unexpected upload event: " + event);
    }
  }

  /**
   * Streams {@link #okHttpRequestBody} via {@link RequestBody#writeTo} on the calling thread,
   * starting with {@code firstRead} and dequeuing subsequent {@link Event.UploadRead} buffers from
   * {@link #events} until the upload finishes or is interrupted.
   */
  @Nullable
  private Event writeBody(Event.UploadRead firstRead) throws IOException {
    setActiveUploadEvent(firstRead);
    RequestBodySink sink = new RequestBodySink();
    SinkState finalState = null;
    try {
      // Note: the code below may result in bufferedSink.close() being called multiple times; this
      // is benign, RealBufferedSink will safely short-circuit after the first call.
      try (BufferedSink bufferedSink = Okio.buffer(sink)) {
        try {
          okHttpRequestBody.writeTo(bufferedSink);
          // The user (RequestBody#writeTo) is done writing the request body.
          // At this point, the user might or might not have closed the sink.
          // Assume the sink might be already closed, so flush() cannot be called.

          // If the sink is still Open, close it now to flush any bytes remaining in the buffer and
          // signal Cronet that we're done writing the request body. Otherwise, Cronet will be stuck
          // waiting for more data to come, which will never come. This will result in a timeout.
          if (sink.state instanceof SinkState.Open) {
            bufferedSink.close();
          }
        } finally {
          finalState = sink.state;
          // BufferedSink.close() may call write() and/or close() on the underlying RequestBodySink.
          // If the sink is already interrupted this will throw. Avoid this by turning the sink into
          // a no-op before close() is called.
          sink.state = SinkState.BlackHole.INSTANCE;
        }
      }
    } catch (InterruptedByCronetException ignored) {
      // The purpose of InterruptedByCronetException is to tell the user to stop
      // writing when they call write() or close(). We don't want that exception
      // to bubble up as the request may not have failed; the user may still be
      // able to read the response, which may be a redirect or an HTTP error
      // response.
      //
      // We could also make this exception type public and ask the user to
      // swallow this exception themselves in their writeTo() implementation,
      // but in practice it's unlikely most users will do that, as this is a
      // subtle edge case that is specific to okhttp_cronet_transport (standard
      // OkHttp will attempt to keep going with the upload instead, which is
      // worse).
      //
      // Note if the user has not called close(), we call close() for them (see
      // above) and swallow any InterruptedByCronetException that may be thrown from
      // that implicit close() call. This means the user has no way to know that
      // the upload did not finish. This is probably fine because the server
      // will almost certainly reply with some kind of error (or redirect)
      // anyway; it would be very surprising for a server to return an OK
      // response before the upload finishes. Users can always protect against
      // this by calling close() themselves instead of letting us do it.
    }

    if (finalState == SinkState.Closed.INSTANCE) {
      return null;
    } else if (finalState instanceof SinkState.InterruptedByCronet) {
      return ((SinkState.InterruptedByCronet) finalState).event;
    } else if (finalState instanceof SinkState.InterruptedByClient) {
      throw ((SinkState.InterruptedByClient) finalState).rethrow();
    } else {
      throw new IllegalStateException("Unexpected final sink state: " + finalState);
    }
  }

  private void rewindBody(Event.UploadRewind rewind) {
    setActiveUploadEvent(rewind);
    boolean isOneShot = okHttpRequestBody.isOneShot();
    activeUploadEvent = null;
    if (isOneShot) {
      rewind.sink.onRewindError(new UnsupportedOperationException("Body is one-shot"));
    } else {
      // No need to do anything else here, because this method is only called after writeBody() is
      // done already (either normally, or through an InterruptedByCronetException caused by the
      // UploadRewind event). On the next UploadRead event, writeBody() will be called again and
      // will call RequestBody.writeTo() again, which is how rewinding works in OkHttp.
      rewind.sink.onRewindSucceeded();
    }
  }

  /**
   * Records an unanswered {@link Event.Upload} without executing any {@link RequestBody} code or
   * completing its {@link UploadDataSink}, so that {@link #onTerminalEvent} will later fail it with
   * {@link UploadDataSink#onReadError} or {@link UploadDataSink#onRewindError}.
   *
   * <p>Unlike {@link #handleUploadEvent}, this method is intended for cancellation/draining paths
   * where the request is already terminating and user {@link RequestBody} methods must not be
   * invoked, while Cronet's contract still requires any outstanding {@link UploadDataSink} to be
   * answered before resources can be safely freed.
   */
  void setActiveUploadEvent(Event.Upload event) {
    // The API contract of UploadDataProvider guarantees that `read()` and `rewind()` cannot be
    // called if a previous read or rewind has not been completed yet.
    checkState(
        activeUploadEvent == null,
        "Unexpected concurrent upload event: %s and %s",
        activeUploadEvent,
        event);
    activeUploadEvent = event;
  }

  /**
   * Fails any unanswered {@link Event.Upload} once the request has reached a terminal callback
   * ({@link Event.Succeeded}, {@link Event.Failed}, or {@link Event.Canceled}) so Cronet can clean
   * up the upload stream.
   *
   * <p>One may wonder why we do not simply call {@code onReadError} as soon as the sink enters
   * {@code InterruptedByCronet}. The reason is because Cronet will (sadly) immediately fail the
   * entire {@code UrlRequest} as soon as we call {@code onReadError}, which prevents the response
   * from being read. To work around this limitation, we defer the error call until after we are
   * completely done with the request, at which point it cannot do any harm.
   */
  void onTerminalEvent() {
    Event.Upload event = activeUploadEvent;
    if (event == null) {
      return;
    }
    activeUploadEvent = null;
    if (event instanceof Event.UploadRead) {
      event.sink.onReadError(new IOException("Request terminated with an active upload read"));
    } else if (event instanceof Event.UploadRewind) {
      event.sink.onRewindError(new IOException("Request terminated with an active upload rewind"));
    } else {
      throw new IllegalStateException("Unexpected active upload event: " + event);
    }
  }

  /**
   * Lifecycle state of a single {@link #writeBody} attempt.
   *
   * <p>State is tracked explicitly on {@link RequestBodySink} rather than inferred from exceptions
   * escaping {@link RequestBody#writeTo}, because user {@code writeTo} implementations may catch
   * and swallow exceptions thrown by the sink (or the user may even be calling {@link Sink} methods
   * from a different thread than the one we called {@link RequestBody#writeTo} on).
   */
  private abstract static class SinkState {
    private SinkState() {}

    /**
     * Initial state while {@link RequestBody#writeTo} is streaming bytes into Cronet {@link
     * Event.UploadRead} buffers and the final chunk has not yet been committed.
     */
    static final class Open extends SinkState {
      static final Open INSTANCE = new Open();

      private Open() {}
    }

    /**
     * Entered in {@link RequestBodySink#close} once the full request body (matching {@link
     * #contentLength}, if known) has been written and committed via {@link
     * UploadDataSink#onReadSucceeded}. {@link #writeBody} returns {@code null} so {@code
     * waitForResponseHeaders} proceeds to await response headers.
     */
    static final class Closed extends SinkState {
      static final Closed INSTANCE = new Closed();

      private Closed() {}
    }

    /**
     * Post-{@link #writeBody} cleanup state where all {@link Sink} operations become no-ops
     * (discarding any buffered bytes) so the {@link BufferedSink} wrapper can be closed
     * unconditionally without throwing or transmitting partial data.
     */
    static final class BlackHole extends SinkState {
      static final BlackHole INSTANCE = new BlackHole();

      private BlackHole() {}
    }

    /**
     * Cronet interrupted the upload before it finished: entered when {@link
     * RequestBodySink#obtainActiveRead} dequeues a non-{@link Event.UploadRead} event from {@link
     * #events} (such as an early server {@link Event.ResponseStarted}, {@link
     * Event.RedirectReceived}, {@link Event.UploadRewind}, {@link Event.Failed}, or {@link
     * Event.Canceled}).
     *
     * <p>Unlike {@link InterruptedByClient}, no client-side upload error occurred: {@link
     * RequestBodySink#obtainActiveRead} throws {@link InterruptedByCronetException} only to unwind
     * {@code writeTo}, and {@link #writeBody} catches {@link InterruptedByCronetException} and
     * returns {@link #event} to {@code waitForResponseHeaders} <i>without</i> cancelling {@code
     * UrlRequest} so early HTTP responses, redirects, Rewinds, or Cronet request
     * failures/cancellations are processed normally by the caller's event loop.
     */
    static final class InterruptedByCronet extends SinkState {
      final Event event;

      InterruptedByCronet(Event event) {
        this.event = event;
      }
    }

    /**
     * Client-side upload error: entered when a {@link Sink} method throws any {@link Throwable}
     * (other than {@link InterruptedByCronetException}), writes fewer or more bytes than {@link
     * #contentLength}, or times out waiting for Cronet's next read ({@link
     * CronetTimeoutException}).
     *
     * <p>Client-side failures are not recoverable: if we report a read error to Cronet, it will
     * immediately fail the entire request. Therefore, {@link #writeBody} rethrows {@link #cause}
     * (even if {@code writeTo} swallowed the sink's exception) so {@code waitForResponseHeaders}
     * immediately cancels {@code UrlRequest} and propagates {@link #cause} to the caller.
     */
    static final class InterruptedByClient extends SinkState {
      @FunctionalInterface
      interface Rethrower {
        RuntimeException rethrow() throws IOException;
      }

      final Throwable cause;
      private final Rethrower rethrower;

      InterruptedByClient(Throwable cause, Rethrower rethrower) {
        this.cause = cause;
        this.rethrower = rethrower;
      }

      RuntimeException rethrow() throws IOException {
        throw rethrower.rethrow();
      }
    }
  }

  @FunctionalInterface
  private interface SinkOperation {
    void run() throws IOException;
  }

  private final class RequestBodySink implements Sink {
    private long totalBytesWritten = 0;
    SinkState state = SinkState.Open.INSTANCE;

    private void runSinkOperation(SinkOperation operation) throws IOException {
      SinkState current = state;
      if (current == SinkState.Open.INSTANCE) {
        try {
          operation.run();
        } catch (InterruptedByCronetException e) {
          state = new SinkState.InterruptedByCronet(e.event);
          throw e;
        } catch (Throwable t) {
          state =
              new SinkState.InterruptedByClient(
                  t,
                  () -> {
                    throw t;
                  });
          throw t;
        }
      } else if (current == SinkState.Closed.INSTANCE) {
        throw new IllegalStateException("closed");
      } else if (current == SinkState.BlackHole.INSTANCE) {
        // No-op.
      } else if (current instanceof SinkState.InterruptedByCronet) {
        throw new InterruptedByCronetException(((SinkState.InterruptedByCronet) current).event);
      } else if (current instanceof SinkState.InterruptedByClient) {
        throw new IOException(
            "Upload already failed", ((SinkState.InterruptedByClient) current).cause);
      } else {
        throw new IllegalStateException("Unexpected sink state: " + current);
      }
    }

    @Override
    public void write(Buffer source, long byteCount) throws IOException {
      if (state == SinkState.BlackHole.INSTANCE) {
        source.skip(byteCount);
        return;
      }
      runSinkOperation(
          () -> {
            if (contentLength != -1L && byteCount > contentLength - totalBytesWritten) {
              throw new IOException(
                  "Expected "
                      + contentLength
                      + " bytes but got at least "
                      + (totalBytesWritten + byteCount));
            }

            long bytesRemaining = byteCount;
            while (bytesRemaining > 0L) {
              Event.UploadRead read = obtainActiveRead();
              ByteBuffer buffer = read.buffer;
              int originalLimit = buffer.limit();
              int bytesToCopy = (int) min((long) buffer.remaining(), bytesRemaining);
              buffer.limit(buffer.position() + bytesToCopy);
              int bytesRead;
              try {
                bytesRead = source.read(buffer);
              } finally {
                buffer.limit(originalLimit);
              }
              checkState(bytesRead > 0, "Source exhausted unexpectedly");
              bytesRemaining -= bytesRead;
              totalBytesWritten += bytesRead;

              // For fixed-length bodies, hold the final read uncommitted when totalBytesWritten
              // reaches contentLength until close() confirms the body has no extra bytes.
              if (contentLength == -1L || totalBytesWritten < contentLength) {
                activeUploadEvent = null;
                read.sink.onReadSucceeded(false);
              }
            }
          });
    }

    @Override
    public void flush() throws IOException {
      runSinkOperation(() -> {});
    }

    @Override
    public Timeout timeout() {
      return Timeout.NONE;
    }

    @Override
    public void close() throws IOException {
      if (state == SinkState.Closed.INSTANCE) {
        return;
      }
      runSinkOperation(
          () -> {
            if (contentLength != -1L && totalBytesWritten < contentLength) {
              throw new IOException("The source has been exhausted but we expected more data!");
            }

            Event.UploadRead read = obtainActiveRead();
            activeUploadEvent = null;
            read.sink.onReadSucceeded(contentLength == -1L);
            state = SinkState.Closed.INSTANCE;
          });
    }

    private Event.UploadRead obtainActiveRead() throws IOException {
      Event.Upload current = activeUploadEvent;
      final Event.UploadRead read;
      if (current == null) {
        read = pollNextRead();
      } else if (current instanceof Event.UploadRead) {
        read = (Event.UploadRead) current;
      } else {
        // If a rewind event occurs during the upload phase of the request, we immediately unwind
        // and process the rewind event; it is not supposed to sit in `activeUploadEvent`.
        throw new IllegalStateException(
            "Unexpected active upload event during writeBody: " + current);
      }

      // Between obtainActiveRead() calls we run user code (i.e. RequestBody.writeTo); therefore,
      // a potentially unbounded amount of time could have elapsed since we last looked at the event
      // queue. During this time, it is quite possible that we may have received an UploadRead event
      // from Cronet, followed by an interruption event. We could just let that interruption
      // event sit in the queue until the next call to obtainActiveRead(), but if we do that we may
      // end up letting the user do pointless work on an upload that is actually aborted. So, as an
      // optimization, we check for this case now so that we can report the interruption sooner.
      // This also ensures that, if the interruption is received between the last chunk and close(),
      // close() will correctly report the interruption instead of pretending the upload succeeded.
      Event queued = events.poll();
      if (queued != null) {
        throw new InterruptedByCronetException(queued);
      }
      return read;
    }

    private Event.UploadRead pollNextRead() throws IOException {
      Event event;
      try {
        event = events.poll(writeTimeoutMillis, MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        event = null;
      }
      if (event == null) {
        throw new CronetTimeoutException();
      }
      // We expect to only receive UploadRead events until we have uploaded the entire request body.
      // If we get anything else, it can mean something went wrong, or the server started responding
      // before the upload finished. Note in the latter case, standard OkHttp behavior is to keep
      // uploading anyway, even though the server likely stopped listening. This is arguably a flaw
      // in OkHttp, as it can lead to confusing outcomes (e.g. deadlock while trying to upload as
      // the server is not consuming bytes anymore). Here we diverge from OkHttp behavior and throw
      // an "aborted" exception which is clearer and also allows the user to potentially read the
      // response. We may want to revisit this if this difference in behavior causes breakage for
      // users.
      if (!(event instanceof Event.UploadRead)) {
        throw new InterruptedByCronetException(event);
      }
      Event.UploadRead uploadRead = (Event.UploadRead) event;
      setActiveUploadEvent(uploadRead);
      return uploadRead;
    }
  }

  private static final class InterruptedByCronetException extends IOException {
    final Event event;

    InterruptedByCronetException(Event event) {
      super(
          formatMessage(event),
          event instanceof Event.Failed ? ((Event.Failed) event).exception : null);
      this.event = event;
    }

    private static String formatMessage(Event event) {
      if (event instanceof Event.ResponseStarted) {
        return "Upload aborted because response started (HTTP "
            + ((Event.ResponseStarted) event).info.getHttpStatusCode()
            + ")";
      } else if (event instanceof Event.RedirectReceived) {
        return "Upload aborted because redirect received (HTTP "
            + ((Event.RedirectReceived) event).info.getHttpStatusCode()
            + ")";
      } else if (event instanceof Event.UploadRewind) {
        return "Upload aborted because upload rewind was requested";
      } else if (event instanceof Event.Failed) {
        return "Upload aborted because request failed";
      } else if (event instanceof Event.Canceled) {
        return "Upload aborted because request was canceled";
      } else {
        throw new IllegalStateException("Unexpected event during upload: " + event);
      }
    }
  }
}
