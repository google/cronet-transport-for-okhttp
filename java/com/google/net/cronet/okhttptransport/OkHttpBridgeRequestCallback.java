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
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.common.util.concurrent.Uninterruptibles;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.Source;
import okio.Timeout;
import org.chromium.net.CronetException;
import org.chromium.net.UploadDataProvider;
import org.chromium.net.UploadDataSink;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;

/**
 * An implementation of Cronet's callback. This is the heart of the bridge and deals with most of
 * the async-sync paradigm translation.
 *
 * <p>Translating the UrlResponseInfo is relatively straightforward as the entire object is
 * available immediately and is relatively small, so it can easily fit in memory.
 *
 * <p>Translating the body is a bit more tricky because of the mismatch between OkHttp and Cronet
 * designs. We invoke Cronet's read and wait for the result using synchronization primitives (see
 * BodySource implementation). The implementation is assuming that there's always at most one read()
 * request in flight (which is safe to assume), and relies on reasonable fairness of thread
 * scheduling, especially when handling cancellations.
 */
class OkHttpBridgeRequestCallback extends UrlRequest.Callback {

  /** Events posted from Cronet callbacks to the user thread. */
  abstract static class Event {
    private Event() {}

    /**
     * Terminal {@link UrlRequest.Callback} events after which Cronet emits no further callbacks.
     */
    abstract static class Terminal extends Event {
      private Terminal() {}
    }

    /**
     * {@link UploadDataProvider} callbacks that require a matching completion call on {@link
     * #sink}.
     */
    abstract static class Upload extends Event {
      final UploadDataSink sink;

      private Upload(UploadDataSink sink) {
        this.sink = sink;
      }
    }

    static final class UploadRead extends Upload {
      final ByteBuffer buffer;

      UploadRead(UploadDataSink sink, ByteBuffer buffer) {
        super(sink);
        this.buffer = buffer;
      }
    }

    static final class UploadRewind extends Upload {
      UploadRewind(UploadDataSink sink) {
        super(sink);
      }
    }

    static final class ResponseStarted extends Event {
      final UrlResponseInfo info;

      ResponseStarted(UrlResponseInfo info) {
        this.info = info;
      }
    }

    static final class RedirectReceived extends Event {
      final UrlResponseInfo info;

      RedirectReceived(UrlResponseInfo info) {
        this.info = info;
      }
    }

    static final class ReadCompleted extends Event {
      static final ReadCompleted INSTANCE = new ReadCompleted();

      private ReadCompleted() {}
    }

    static final class Succeeded extends Terminal {
      static final Succeeded INSTANCE = new Succeeded();

      private Succeeded() {}
    }

    static final class Failed extends Terminal {
      final CronetException exception;

      Failed(CronetException exception) {
        this.exception = exception;
      }
    }

    static final class Canceled extends Terminal {
      static final Canceled INSTANCE = new Canceled();

      private Canceled() {}
    }
  }

  static final class ResponseHeaders {
    final UrlResponseInfo info;
    final Source bodySource;

    ResponseHeaders(UrlResponseInfo info, Source bodySource) {
      this.info = info;
      this.bodySource = bodySource;
    }
  }

  /**
   * The byte buffer capacity for reading Cronet response bodies. Each response callback will
   * allocate its own buffer of this size once the response starts being processed.
   */
  // If you change this value, make sure to reflect the change in ReadIntegrityTest.
  private static final int CRONET_BYTE_BUFFER_CAPACITY = 32 * 1024;

  /** Signal whether the request is finished and the response has been fully read. */
  private boolean finished = false;

  /** Signal whether the request was canceled. */
  private boolean canceled = false;

  /**
   * An internal, blocking, thread-safe queue passing events between Cronet callbacks and the user
   * thread.
   */
  private final BlockingQueue<Event> events =
      new LinkedBlockingQueue<Event>() {
        @Override
        public Event take() throws InterruptedException {
          Event event = super.take();
          onEventDequeued(event);
          return event;
        }

        @Override
        @Nullable
        public Event poll() {
          Event event = super.poll();
          if (event != null) {
            onEventDequeued(event);
          }
          return event;
        }

        @Override
        @Nullable
        public Event poll(long timeout, TimeUnit unit) throws InterruptedException {
          Event event = super.poll(timeout, unit);
          if (event != null) {
            onEventDequeued(event);
          }
          return event;
        }
      };

  private void onEventDequeued(Event event) {
    if (event instanceof Event.Terminal) {
      finished = true;
    }
    if (event instanceof Event.Canceled) {
      canceled = true;
    }
  }

  /** The converter handling request body uploads on the user thread, if the request has a body. */
  @Nullable private RequestBodyConverter requestBodyConverter = null;

  /** The read timeout as specified by OkHttp. * */
  private final long readTimeoutMillis;

  /** The previous responses as reported to {@link #onRedirectReceived}, from oldest to newest. * */
  private final List<UrlResponseInfo> urlResponseInfoChain = new ArrayList<>();

  private final RedirectStrategy redirectStrategy;

  /** The request being processed. */
  private volatile UrlRequest request;

  OkHttpBridgeRequestCallback(long readTimeoutMillis, RedirectStrategy redirectStrategy) {
    checkArgument(readTimeoutMillis >= 0);

    // So that we don't have to special case infinity. Int.MAX_VALUE is ~infinity for all practical
    // use cases.
    if (readTimeoutMillis == 0) {
      this.readTimeoutMillis = Integer.MAX_VALUE;
    } else {
      this.readTimeoutMillis = readTimeoutMillis;
    }
    this.redirectStrategy = redirectStrategy;
  }

  UploadDataProvider attachRequestBody(
      RequestBody body, long contentLength, long writeTimeoutMillis) {
    requestBodyConverter =
        new RequestBodyConverter(body, contentLength, writeTimeoutMillis, events);
    return requestBodyConverter;
  }

  /**
   * Drives any request body upload on the calling thread and blocks until the response headers are
   * available (or the request fails/is canceled), returning the {@link UrlResponseInfo} along with
   * the response body {@link Source}.
   */
  ResponseHeaders waitForResponseHeaders(UrlRequest urlRequest) throws IOException {
    request = urlRequest;
    boolean responseStarted = false;
    try {
      Event nextEvent = null;
      while (true) {
        Event event = nextEvent != null ? nextEvent : takeEventUninterruptibly();
        nextEvent = null;
        if (event instanceof Event.Upload) {
          nextEvent = checkNotNull(requestBodyConverter).handleUploadEvent((Event.Upload) event);
        } else if (event instanceof Event.ResponseStarted) {
          responseStarted = true;
          return new ResponseHeaders(((Event.ResponseStarted) event).info, new CronetBodySource());
        } else if (event instanceof Event.RedirectReceived) {
          if (!redirectStrategy.followRedirects()) {
            // Note: This might not match the content length headers but we have no way of accessing
            // the actual body with current Cronet's APIs (see RedirectStrategy).
            return new ResponseHeaders(((Event.RedirectReceived) event).info, new Buffer());
          }
          throw new ProtocolException(
              "Too many follow-up requests: "
                  + (redirectStrategy.numberOfRedirectsToFollow() + 1));
        } else if (event instanceof Event.Failed) {
          throw new IOException(((Event.Failed) event).exception);
        } else if (event instanceof Event.Canceled) {
          throw new IOException("The request was canceled!");
        } else {
          throw new IllegalStateException("Unexpected event before response headers: " + event);
        }
      }
    } finally {
      if (!responseStarted) {
        // Make sure the request is cancelled if anything unexpected occurs, especially if the user
        // throws during upload in handleUploadEvent(). (There is no point in letting the request
        // continue if upload fails because Cronet does not support continuing on a failed upload -
        // it will immediately fail the request anyway. Cancelling allows us to fail faster: the
        // alternative would be to wait for the next UploadRead event and fail that, which could
        // take an unbounded amount of time.)
        cancelAndDrain(urlRequest);
      }
    }
  }

  private void cancelAndDrain(UrlRequest urlRequest) {
    try {
      if (!finished) {
        urlRequest.cancel();
        drainUntilTerminalEvent();
      }
    } finally {
      if (requestBodyConverter != null) {
        requestBodyConverter.onTerminalEvent();
      }
    }
  }

  private void drainUntilTerminalEvent() {
    while (!finished) {
      Event event = takeEventUninterruptibly();
      if (event instanceof Event.Upload) {
        // Record any unanswered upload callback so it is failed on termination.
        checkNotNull(requestBodyConverter).setActiveUploadEvent((Event.Upload) event);
      }
    }
  }

  @Nullable
  private Event pollEvent(long timeoutMillis) {
    try {
      return events.poll(timeoutMillis, MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private Event takeEventUninterruptibly() {
    return Uninterruptibles.takeUninterruptibly(events);
  }

  List<UrlResponseInfo> getUrlResponseInfoChain() {
    return Collections.unmodifiableList(urlResponseInfoChain);
  }

  @Override
  public void onRedirectReceived(
      UrlRequest urlRequest, UrlResponseInfo urlResponseInfo, String nextUrl) {
    if (!redirectStrategy.followRedirects()) {
      events.add(new Event.RedirectReceived(urlResponseInfo));
      return;
    }

    urlResponseInfoChain.add(urlResponseInfo);
    if (urlResponseInfo.getUrlChain().size() <= redirectStrategy.numberOfRedirectsToFollow()) {
      urlRequest.followRedirect();
      return;
    }

    // Cap reached: surface RedirectReceived so waitForResponseHeaders() throws ProtocolException
    // and its finally block cancels and drains urlRequest via cancelAndDrain().
    events.add(new Event.RedirectReceived(urlResponseInfo));
  }

  @Override
  public void onResponseStarted(UrlRequest urlRequest, UrlResponseInfo urlResponseInfo) {
    events.add(new Event.ResponseStarted(urlResponseInfo));
  }

  @Override
  public void onReadCompleted(
      UrlRequest urlRequest, UrlResponseInfo urlResponseInfo, ByteBuffer byteBuffer) {
    events.add(Event.ReadCompleted.INSTANCE);
  }

  @Override
  public void onSucceeded(UrlRequest urlRequest, UrlResponseInfo urlResponseInfo) {
    events.add(Event.Succeeded.INSTANCE);
  }

  @Override
  public void onFailed(UrlRequest urlRequest, UrlResponseInfo urlResponseInfo, CronetException e) {
    events.add(new Event.Failed(e));
  }

  @Override
  public void onCanceled(UrlRequest urlRequest, UrlResponseInfo responseInfo) {
    events.add(Event.Canceled.INSTANCE);
  }

  private class CronetBodySource implements Source {

    /** This buffer is used for reading data from the network and for writing it downstream. */
    private ByteBuffer buffer = ByteBuffer.allocateDirect(CRONET_BYTE_BUFFER_CAPACITY);

    /** Whether the close() method has been called. */
    private volatile boolean closed = false;

    @Override
    public long read(Buffer sink, long byteCount) throws IOException {
      if (canceled) {
        throw new IOException("The request was canceled!");
      }

      // Using IAE instead of NPE (checkNotNull) for okio.RealBufferedSource consistency
      checkArgument(sink != null, "sink == null");
      checkArgument(byteCount >= 0, "byteCount < 0: %s", byteCount);
      checkState(!closed, "closed");

      if (finished) {
        return -1;
      }

      // If the caller requested 0 bytes, then we don't need to read from the network. Technically,
      // reading 0 bytes doesn't make sense, but we'll still support this case.
      if (byteCount == 0) {
        return 0;
      }

      // Attempt to read from the network to fill the empty buffer.
      //
      // When we enter Source#read() method and the buffer.position() is 0, then we definitely know
      // that the buffer is empty and we need to read from the network.
      //
      // Technically, in the general case, buffer.position() == 0 check is ambiguous, and could mean
      // one of the two things:
      // 1. After buffer.clear() was called – the buffer is empty.
      // 2. After buffer.flip() was called – the buffer was just written to, but not yet read from.
      //
      // However, in the context of this method, buffer.position() == 0 check is unambiguous.
      // Because as soon as we read from the network, we write downstream which always increases the
      // buffer.position(). Thus, the next time we enter this method, the buffer.position() will
      // either be > 0 if there's still data in the buffer, so we can't read from the network yet.
      // Or it will be 0 if the buffer was emptied in one read, so we need to read from the network.
      if (buffer.position() == 0) {
        if (fillBuffer()) {
          buffer.flip(); // Flip the buffer so that it can be used for writing downstream.
          checkState(buffer.hasRemaining(), "Buffer should have remaining bytes after flip");
        } else {
          return -1; // End of stream
        }
      }

      // Now that the buffer is non-empty, write as much data as possible downstream.
      final int bytesWritten = copyByteBufferToOkioBuffer(buffer, sink, byteCount);
      checkState(bytesWritten > 0, "Bytes written should be positive");

      // In case the buffer became empty again, clear it so that it can be used for reading from the
      // network next time Source#read() is called.
      if (!buffer.hasRemaining()) {
        buffer.clear();
      }

      return bytesWritten;
    }

    /**
     * Reads data from the network to fill the buffer.
     *
     * <p>Note we always request Cronet to read up to the entire buffer capacity. This will normally
     * be larger than the caller's sink buffer size, as (at the time of writing) OkIo only reads in
     * hardcoded fixed 8 KiB "segments". This is a performance optimization - it would be simpler to
     * read up to the caller's buffer size, but 8 KiB is too small for efficient Cronet operation
     * due to large per-read overhead. Instead, we read larger buffers which are then split and
     * handed off chunk by chunk to the caller. See
     * https://github.com/google/cronet-transport-for-okhttp/issues/47.
     *
     * <p>This method blocks until the read is completed or a timeout occurs.
     *
     * @return Whether any bytes were read. Returns true if the UrlRequest.read() was followed by
     *     UrlRequest.Callback.onReadCompleted(). Returns false for any other outcome: onCanceled(),
     *     onFailed(), onSucceeded() callbacks, or timeout.
     */
    private boolean fillBuffer() throws IOException {
      checkState(buffer.position() == 0, "Buffer position is not 0");
      checkState(buffer.limit() == buffer.capacity(), "Buffer limit is not capacity");

      UrlRequest localRequest = checkNotNull(request);
      localRequest.read(buffer);

      boolean readCompleted = false;
      try {
        while (true) {
          // If pollEvent returns null, either events.poll() was interrupted or it timed out.
          Event event = pollEvent(readTimeoutMillis);
          if (event == null) {
            throw new CronetTimeoutException();
          }
          if (event instanceof Event.Failed) {
            throw new IOException(((Event.Failed) event).exception);
          } else if (event instanceof Event.Succeeded) {
            return false;
          } else if (event instanceof Event.Canceled) {
            throw new IOException("The request was canceled!");
          } else if (event instanceof Event.ReadCompleted) {
            readCompleted = true;
            return true;
          } else {
            throw new IllegalStateException("Unexpected event while reading body: " + event);
          }
        }
      } finally {
        if (!readCompleted) {
          // We null the buffer in final statuses to allow fast GC of the buffer even if the
          // callback is still in use.
          buffer = null;
          cancelAndDrain(localRequest);
        }
      }
    }

    /**
     * Copies the data from a {@link ByteBuffer} to a {@link Buffer}. Equivalent to calling {@link
     * Buffer#write(ByteBuffer)}, but only copies up to `byteCount` bytes.
     *
     * @param from The ByteBuffer to read from.
     * @param to The Buffer to write to.
     * @param byteCount The maximum number of bytes to copy.
     * @return the number of bytes written to the buffer.
     */
    private static int copyByteBufferToOkioBuffer(ByteBuffer from, Buffer to, long byteCount)
        throws IOException {
      final int bytesWritten;

      if (from.remaining() <= byteCount) {
        bytesWritten = to.write(from);
      } else {
        final int originalLimit = from.limit();
        try {
          // Sadly, Buffer#write() does not take a byteCount when writing to a ByteBuffer. We work
          // around this limitation by temporarily adjusting the limit.
          from.limit(from.position() + (int) byteCount);
          bytesWritten = to.write(from);
        } finally {
          // Restore the original limit to preserve buffer state
          from.limit(originalLimit);
        }
      }

      return bytesWritten;
    }

    @Override
    public Timeout timeout() {
      // TODO: This should likely respect the OkHttp timeout somehow
      return Timeout.NONE;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      cancelAndDrain(checkNotNull(request));
    }
  }
}
