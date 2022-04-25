package com.google.samples.cronet.okhttptransport;

import static com.google.common.base.Preconditions.checkNotNull;

import android.app.Application;
import com.google.android.gms.tasks.Task;
import okhttp3.OkHttpClient;

public final class CronetTransportApplication extends Application {
  final HttpClientHolder httpClientHolder = new HttpClientHolder();

  @Override
  public void onCreate() {
    super.onCreate();
    httpClientHolder.fastSynchronousInit();
  }

  OkHttpClient getHttpClient() {
    return checkNotNull(httpClientHolder.getHttpClient());
  }

  Task<OkHttpClient> invokeAsyncHttpClientInit() {
    return httpClientHolder.slowAsynchronousInit(getApplicationContext());
  }
}
