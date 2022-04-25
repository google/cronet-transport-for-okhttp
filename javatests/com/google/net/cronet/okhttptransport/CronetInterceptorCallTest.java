package com.google.net.cronet.okhttptransport;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.net.cronet.testing.CronetEngineTestAppRule;
import okhttp3.Call;
import okhttp3.CallTest;
import okhttp3.OkHttpClient;
import org.junit.Rule;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CronetInterceptorCallTest extends CallTest {

  @Rule public final CronetEngineTestAppRule engineRule = new CronetEngineTestAppRule();

  @Override
  protected Call.Factory createUnderTest() {
    return new OkHttpClient.Builder()
        .addInterceptor(CronetInterceptor.newBuilder(engineRule.getEngine()).build())
        .build();
  }

  @Override
  protected Call.Factory createUnderTestWithReadTimeout(int timeoutMillis) {
    return new OkHttpClient.Builder()
        .readTimeout(timeoutMillis, MILLISECONDS)
        .addInterceptor(CronetInterceptor.newBuilder(engineRule.getEngine()).build())
        .build();
  }

  @Override
  protected Call.Factory createUnderTestWithCallTimeout(int timeoutMillis) {
    return new OkHttpClient.Builder()
        .callTimeout(timeoutMillis, MILLISECONDS)
        .addInterceptor(CronetInterceptor.newBuilder(engineRule.getEngine()).build())
        .build();
  }
}
