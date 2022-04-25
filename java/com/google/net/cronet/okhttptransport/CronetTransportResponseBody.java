package com.google.net.cronet.okhttptransport;

import androidx.annotation.Nullable;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.BufferedSource;

abstract class CronetTransportResponseBody extends ResponseBody {

  private final ResponseBody delegate;

  protected CronetTransportResponseBody(ResponseBody delegate) {
    this.delegate = delegate;
  }

  @Nullable
  @Override
  public final MediaType contentType() {
    return delegate.contentType();
  }

  @Override
  public final long contentLength() {
    return delegate.contentLength();
  }

  @Override
  public final BufferedSource source() {
    return delegate.source();
  }

  @Override
  public final void close() {
    delegate.close();
    customCloseHook();
  }

  abstract void customCloseHook();
}
