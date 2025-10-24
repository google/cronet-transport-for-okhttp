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

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.gms.tasks.Task;
import com.google.common.base.Stopwatch;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class OkHttpActivity extends Activity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.activity_main);
  }

  @SuppressLint("SetTextI18n")
  public void onRequestButtonClicked(View view) {
    OkHttpClient client = getCastedApplication().getHttpClient();

    AtomicInteger totalSize = new AtomicInteger();
    AtomicInteger finishedRequests = new AtomicInteger();
    AtomicBoolean failed = new AtomicBoolean();

    Stopwatch stopwatch = Stopwatch.createUnstarted();

    Callback callback =
        new Callback() {
          @Override
          public void onFailure(Call call, IOException e) {
            if (failed.getAndSet(true)) {
              return;
            }
            runOnUiThread(
                () ->
                    ((TextView) findViewById(R.id.displayMessage))
                        .setText("Failed : " + e.getMessage()));
          }

          @Override
          public void onResponse(Call call, Response response) throws IOException {
            if (failed.get()) {
              return;
            }

            int total = totalSize.addAndGet(response.body().bytes().length);
            int finished = finishedRequests.incrementAndGet();
            if (finished == ImageRepository.numberOfImages()) {
              stopwatch.stop();
              runOnUiThread(
                  () ->
                      ((TextView) findViewById(R.id.displayMessage))
                          .setText(
                              String.format(
                                  Locale.ENGLISH,
                                  "%s fetched %d bytes in %s millis",
                                  client,
                                  total,
                                  stopwatch.elapsed(MILLISECONDS))));
            }
          }
        };

    stopwatch.start();
    for (int i = 0; i < ImageRepository.numberOfImages(); i++) {
      Request request = new Request.Builder().url(ImageRepository.getImage(i)).build();
      client.newCall(request).enqueue(callback);
    }
  }

  public void onInitButtonClicked(View v) {
    Task<OkHttpClient> task = getCastedApplication().invokeAsyncHttpClientInit();
    task.addOnCompleteListener(
        lambdaTask -> {
          String message;

          if (lambdaTask.isSuccessful()) {
            message = "Successfully initialized Cronet transport";
          } else {
            message = "An error has occurred: " + lambdaTask.getException().getMessage();
          }

          runOnUiThread(
              () -> Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show());
        });
  }

  @SuppressWarnings("unchecked") // correct by definition
  private CronetTransportApplication getCastedApplication() {
    return (CronetTransportApplication) getApplication();
  }
}
