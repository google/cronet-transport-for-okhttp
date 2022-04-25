package com.google.net.cronet.okhttptransport;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.when;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;
import com.google.common.util.concurrent.Futures;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okio.Buffer;
import okio.Source;
import org.chromium.net.UrlResponseInfo;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidJUnit4.class)
public class ResponseConverterTest {

  private static final Request GOOGLE_COM_REQUEST =
      new Request.Builder().get().url("http://www.google.com").build();

  private static final String GOOGLE_COM_BODY = "Hello from Google!";

  private static final MediaType GOOGLE_COM_MEDIA_TYPE =
      MediaType.parse("text/html; charset=UTF-8");

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  // TODO(danstahr): Separate the getters to an interface and make OkHttpBridgeRequestCallback final
  @Mock private OkHttpBridgeRequestCallback mockRequestCallback;

  private final ResponseConverter underTest = new ResponseConverter();

  @Test
  public void testAllFields_success() throws Exception {
    when(mockRequestCallback.getUrlResponseInfo())
        .thenReturn(Futures.immediateFuture(new GoogleComResponseInfo()));
    when(mockRequestCallback.getBodySource())
        .thenReturn(Futures.immediateFuture(createGoogleComBodySource()));

    Response actualResponse = underTest.toResponse(GOOGLE_COM_REQUEST, mockRequestCallback);

    assertThat(actualResponse.code()).isEqualTo(200);
    assertThat(actualResponse.message()).isEmpty();

    assertThat(actualResponse.protocol()).isEqualTo(Protocol.HTTP_2);

    assertThat(actualResponse.cacheControl().isPrivate()).isTrue();
    assertThat(actualResponse.cacheControl().maxAgeSeconds()).isEqualTo(0);

    assertThat(actualResponse.header("x-random-header")).isEqualTo("FooBar");

    assertThat(actualResponse.body()).isNotNull();
    assertThat(actualResponse.body().contentLength()).isEqualTo(GOOGLE_COM_BODY.length());
    assertThat(actualResponse.body().contentType()).isEqualTo(GOOGLE_COM_MEDIA_TYPE);
    assertThat(actualResponse.body().string()).isEqualTo(GOOGLE_COM_BODY);
  }

  @Test
  public void testMultipleHeaders_lastWinsForBodyParams() throws Exception {
    String contentTypeHeader = "text/plain; charset=UTF-8";
    UrlResponseInfo responseInfo =
        new GoogleComResponseInfo() {
          @Override
          void customizeHeadersMultimap(ListMultimap<String, String> multimap) {
            multimap.put("content-length", "-1");
            multimap.put("content-type", contentTypeHeader);
          }
        };

    when(mockRequestCallback.getUrlResponseInfo())
        .thenReturn(Futures.immediateFuture(responseInfo));
    when(mockRequestCallback.getBodySource())
        .thenReturn(Futures.immediateFuture(createGoogleComBodySource()));

    Response actualResponse = underTest.toResponse(GOOGLE_COM_REQUEST, mockRequestCallback);

    assertThat(actualResponse.body().contentLength()).isEqualTo(-1);
    assertThat(actualResponse.body().contentType()).isEqualTo(MediaType.parse(contentTypeHeader));

    assertThat(actualResponse.body().string()).isEqualTo(GOOGLE_COM_BODY);
  }

  @Test
  public void testInvalidBodyLength_notPresent() throws Exception {
    UrlResponseInfo responseInfo =
        new GoogleComResponseInfo() {
          @Override
          void customizeHeadersMultimap(ListMultimap<String, String> multimap) {
            multimap.removeAll("content-length");
          }
        };

    when(mockRequestCallback.getUrlResponseInfo())
        .thenReturn(Futures.immediateFuture(responseInfo));
    when(mockRequestCallback.getBodySource())
        .thenReturn(Futures.immediateFuture(createGoogleComBodySource()));

    Response actualResponse = underTest.toResponse(GOOGLE_COM_REQUEST, mockRequestCallback);

    assertThat(actualResponse.body().contentLength()).isEqualTo(-1);

    assertThat(actualResponse.body().string()).isEqualTo(GOOGLE_COM_BODY);
  }

  @Test
  public void testInvalidBodyLength_invalidValue() throws Exception {
    UrlResponseInfo responseInfo =
        new GoogleComResponseInfo() {
          @Override
          void customizeHeadersMultimap(ListMultimap<String, String> multimap) {
            multimap.removeAll("content-length");
            multimap.put("content-length", "null");
          }
        };

    when(mockRequestCallback.getUrlResponseInfo())
        .thenReturn(Futures.immediateFuture(responseInfo));
    when(mockRequestCallback.getBodySource())
        .thenReturn(Futures.immediateFuture(createGoogleComBodySource()));

    Response actualResponse = underTest.toResponse(GOOGLE_COM_REQUEST, mockRequestCallback);

    assertThat(actualResponse.body().contentLength()).isEqualTo(-1);

    assertThat(actualResponse.body().string()).isEqualTo(GOOGLE_COM_BODY);
  }

  @Test
  public void testContentType_missing() throws Exception {
    UrlResponseInfo responseInfo =
        new GoogleComResponseInfo() {
          @Override
          void customizeHeadersMultimap(ListMultimap<String, String> multimap) {
            multimap.removeAll("content-type");
          }
        };

    when(mockRequestCallback.getUrlResponseInfo())
        .thenReturn(Futures.immediateFuture(responseInfo));
    when(mockRequestCallback.getBodySource())
        .thenReturn(Futures.immediateFuture(createGoogleComBodySource()));

    Response actualResponse = underTest.toResponse(GOOGLE_COM_REQUEST, mockRequestCallback);
    assertThat(actualResponse.body().contentType()).isNull();
    assertThat(actualResponse.body().string()).isEqualTo(GOOGLE_COM_BODY);
  }

  @Test
  public void testNegotiatedProtocol_unknownValue_fallsBackToHttp1() throws Exception {
    UrlResponseInfo responseInfo =
        new GoogleComResponseInfo() {
          @Override
          public String getNegotiatedProtocol() {
            return "CoolCustomProtocol";
          }
        };

    when(mockRequestCallback.getUrlResponseInfo())
        .thenReturn(Futures.immediateFuture(responseInfo));
    when(mockRequestCallback.getBodySource())
        .thenReturn(Futures.immediateFuture(createGoogleComBodySource()));

    Response actualResponse = underTest.toResponse(GOOGLE_COM_REQUEST, mockRequestCallback);
    assertThat(actualResponse.protocol()).isEqualTo(Protocol.HTTP_1_0);
    assertThat(actualResponse.body().string()).isEqualTo(GOOGLE_COM_BODY);
  }

  private static Source createGoogleComBodySource() {
    Buffer sourceBuffer = new Buffer();
    sourceBuffer.writeString(GOOGLE_COM_BODY, UTF_8);
    return sourceBuffer;
  }

  private static class GoogleComResponseInfo extends UrlResponseInfo {

    @Override
    public String getUrl() {
      return "https://www.google.com";
    }

    @Override
    public List<String> getUrlChain() {
      return ImmutableList.of("http://www.google.com", "https://www.google.com");
    }

    @Override
    public int getHttpStatusCode() {
      return 200;
    }

    @Override
    public String getHttpStatusText() {
      return "";
    }

    @Override
    public List<Entry<String, String>> getAllHeadersAsList() {
      return ImmutableList.copyOf(getHeadersMultimap().entries());
    }

    @Override
    public Map<String, List<String>> getAllHeaders() {
      return Multimaps.asMap(getHeadersMultimap());
    }

    private ListMultimap<String, String> getHeadersMultimap() {
      ListMultimap<String, String> multimap =
          Multimaps.newListMultimap(
              Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER),
              (Supplier<List<String>>) ArrayList::new);

      multimap.put("cache-control", "private, max-age=0");
      multimap.put("content-type", "text/html; charset=UTF-8");
      multimap.put("content-encoding", "gzip");
      multimap.put("content-length", String.valueOf(GOOGLE_COM_BODY.length()));
      multimap.put("x-random-header", "FooBar");

      customizeHeadersMultimap(multimap);

      return Multimaps.unmodifiableListMultimap(multimap);
    }

    void customizeHeadersMultimap(ListMultimap<String, String> multimap) {}

    @Override
    public boolean wasCached() {
      return false;
    }

    @Override
    public String getNegotiatedProtocol() {
      return "h2";
    }

    @Override
    public String getProxyServer() {
      return ":0";
    }

    @Override
    public long getReceivedByteCount() {
      return 400;
    }
  }
}
