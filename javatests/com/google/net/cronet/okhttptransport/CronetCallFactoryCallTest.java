package com.google.net.cronet.okhttptransport;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.net.cronet.testing.CronetEngineTestAppRule;
import okhttp3.Call;
import okhttp3.CallTest;
import okhttp3.mockwebserver.MockResponse;
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
  // TODO(danstahr): implement for interceptor approach as well
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
}
