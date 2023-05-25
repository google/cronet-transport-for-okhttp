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

package com.google.samples.cronet.okhttptransport;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.content.Context;
import android.util.Log;
import com.google.android.gms.net.CronetProviderInstaller;
import com.google.android.gms.tasks.Task;
import com.google.net.cronet.okhttptransport.CronetInterceptor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import okhttp3.OkHttpClient;
import org.chromium.net.CronetEngine;
import org.chromium.net.CronetProvider;

final class HttpClientHolder {
  private static final String TAG = "HttpClientHolder";

  private final AtomicReference<OkHttpClient> client = new AtomicReference<>();
  private final AtomicBoolean syncInitInvoked = new AtomicBoolean();
  private final AtomicBoolean asyncInitInvoked = new AtomicBoolean();
  private volatile Task<OkHttpClient> asyncInitTask = null;

  @Nullable
  OkHttpClient getHttpClient() {
    return client.get();
  }

  /**
   * Initializes the vanilla OkHttp client. This should generally be done as soon as practical in
   * the application's lifecycle to ensure that an HTTP client is always available.
   */
  void fastSynchronousInit() {
    if (syncInitInvoked.getAndSet(true)) {
      // Already invoked
      return;
    }
    client.set(createOkHttpBuilder().build());
  }

  /**
   * Attempts to initialize the Cronet transport layer and an OkHttp client using it.
   *
   * <p>While it's not necessary to invoke this method early on, it's desirable to begin the
   * initialization early in the application's lifecycle process to make the most of performance
   * benefits of the transport layer.
   */
  Task<OkHttpClient> slowAsynchronousInit(Context context) {
    if (asyncInitInvoked.getAndSet(true)) {
      // Already invoked
      return asyncInitTask;
    }
    asyncInitTask =
        CronetProviderInstaller.installProvider(context)
            .continueWith(
                task -> {
                  // Call to propagate any errors from the first invocation. Don't attempt to
                  // recover from the error as it involves installing updates or other UX-disturbing
                  // activities with a lot of friction. We're better off just using plain OkHttp
                  // in such a case.
                  task.getResult();

                  for (CronetProvider provider : CronetProvider.getAllProviders(context)) {
                    // We're not interested in using the fallback, we're better off sticking with
                    // the default OkHttp client in that case.
                    if (!provider.isEnabled()
                        || provider.getName().equals(CronetProvider.PROVIDER_NAME_FALLBACK)) {
                      continue;
                    }
                    return setupCronetEngine(provider.createBuilder()).build();
                  }
                  throw new IllegalStateException("No enabled Cronet providers found!");
                })
            .continueWith(
                task -> {
                  CronetEngine engine = task.getResult();

                  CronetInterceptor cronetInterceptor =
                      CronetInterceptor.newBuilder(engine).build();

                  return createOkHttpBuilder().addInterceptor(cronetInterceptor).build();
                })
            .addOnCompleteListener(
                task -> {
                  if (task.isSuccessful()) {
                    client.set(task.getResult());
                  } else if (!syncInitInvoked.get()) {
                    Log.i(
                        TAG,
                        "Async HTTP engine initialization finished unsuccessfully but sync init "
                            + "wasn't finished yet! Prefer to perform the fast sync init early on "
                            + "to have a HTTP client available at all times.");
                    fastSynchronousInit();
                  } // Else we just use the vanilla OkHttp client we already have.
                });
    return asyncInitTask;
  }

  /**
   * Customizes the Cronet engine parameters in the provided builder.
   *
   * <p>The application should alter this method to match its needs. For demonstration purposes we
   * set the user agent and enable Brotli.
   *
   * @return the received parameter for chaining
   */
  private CronetEngine.Builder setupCronetEngine(CronetEngine.Builder engineBuilder) {
    return engineBuilder.enableBrotli(true).setUserAgent("Cronet OkHttp Transport Sample");
  }

  /**
   * Creates and returns a new customized OkHttp client builder.
   *
   * <p>The application should alter this method to match its needs. For demonstration purposes we
   * set the call timeout and add a simple logging interceptor.
   */
  private OkHttpClient.Builder createOkHttpBuilder() {
    // Set up your OkHttp parameters here
    return new OkHttpClient.Builder()
        .callTimeout(30, SECONDS)
        .addInterceptor(
            chain -> {
              android.util.Log.i(TAG, chain.request().url().toString());
              return chain.proceed(chain.request());
            });
  }
}
