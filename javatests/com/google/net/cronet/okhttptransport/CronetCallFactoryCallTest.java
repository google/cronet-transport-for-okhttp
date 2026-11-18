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

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.net.cronet.testing.CronetEngineTestAppRule;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import okhttp3.Call;
import okhttp3.CallTest;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CronetCallFactoryCallTest extends CallTest {

  @Rule public final CronetEngineTestAppRule engineRule = new CronetEngineTestAppRule();

  @Override
  protected Call.Factory createUnderTest() {
    return CronetCallFactory.newBuilder(engineRule.getEngine()).build();
  }

  @Override
  protected Call.Factory createUnderTestWithReadTimeout(int timeoutMillis) {
    return CronetCallFactory.newBuilder(engineRule.getEngine())
        .setReadTimeoutMillis(timeoutMillis)
        .build();
  }

  @Override
  protected Call.Factory createUnderTestWithCallTimeout(int timeoutMillis) {
    return CronetCallFactory.newBuilder(engineRule.getEngine())
        .setCallTimeoutMillis(timeoutMillis)
        .build();
  }

  @Test
  // TODO: implement for interceptor approach as well
  public void setFollowRedirectsFalse() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location: /b").setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));

    underTest =
        CronetCallFactory.newBuilder(engineRule.getEngine())
            .setReadTimeoutMillis(1000)
            .setRedirectStrategy(RedirectStrategy.withoutRedirects())
            .build();
    executeSynchronously("/a")
        // Cronet doesn't allow reading the body of a redirect response
        .assertBody("")
        .assertCode(302);
  }

  @Test
  public void enqueueFailureStopsCallTimeout() throws Exception {
    // The connection is reset by the server immediately after being accepted, causing the request
    // to fail asynchronously well before the call timeout elapses.
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

    underTest =
        CronetCallFactory.newBuilder(engineRule.getEngine()).setCallTimeoutMillis(5_000).build();

    Request request = new Request.Builder().url(server.url("/")).build();
    Call call = underTest.newCall(request);

    CountDownLatch failureLatch = new CountDownLatch(1);
    call.enqueue(
        new Callback() {
          @Override
          public void onFailure(Call call, IOException e) {
            failureLatch.countDown();
          }

          @Override
          public void onResponse(Call call, Response response) {
            throw new AssertionError("The request shouldn't have succeeded!");
          }
        });

    assertThat(failureLatch.await(30, SECONDS)).isTrue();
    assertThat(call.isCanceled()).isFalse();

    // The call has already failed, so its timeout must have been stopped. Wait for longer than the
    // call timeout to give a leaked AsyncTimeout a chance to fire and spuriously cancel the call.
    Thread.sleep(6_000);
    assertThat(call.isCanceled()).isFalse();
  }
}
