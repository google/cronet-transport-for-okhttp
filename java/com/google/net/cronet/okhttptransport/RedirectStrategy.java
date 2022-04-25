package com.google.net.cronet.okhttptransport;

/** Defines a redirect strategy for the Cronet OkHttp transport layer. */
public abstract class RedirectStrategy {

  /** The default number of redirects to follow. Should be less than the Chromium wide safeguard. */
  private static final int DEFAULT_REDIRECTS = 16;

  /**
   * Returns whether redirects should be followed at all. If set to false, the redirect response
   * will be returned.
   */
  abstract boolean followRedirects();

  /**
   * Returns the maximum number of redirects to follow. If more redirects are attempted an exception
   * should be thrown by the component handling the request. Shouldn't be called at all if {@link
   * #followRedirects()} return false.
   */
  abstract int numberOfRedirectsToFollow();

  /**
   * Returns a strategy which will not follow redirects.
   *
   * <p>Note that because of Cronet's limitations
   * (https://developer.android.com/guide/topics/connectivity/cronet/lifecycle#overview) it is
   * impossible to retrieve the body of a redirect response. As a result, a dummy empty body will
   * always be provided.
   */
  public static RedirectStrategy withoutRedirects() {
    return WithoutRedirectsHolder.INSTANCE;
  }

  /**
   * Returns a strategy which will follow redirects up to {@link #DEFAULT_REDIRECTS} times. If more
   * redirects are attempted an exception is thrown.
   */
  public static RedirectStrategy defaultStrategy() {
    return DefaultRedirectsHolder.INSTANCE;
  }

  private static class WithoutRedirectsHolder {
    private static final RedirectStrategy INSTANCE =
        new RedirectStrategy() {
          @Override
          boolean followRedirects() {
            return false;
          }

          @Override
          int numberOfRedirectsToFollow() {
            throw new UnsupportedOperationException();
          }
        };
  }

  private static class DefaultRedirectsHolder {
    private static final RedirectStrategy INSTANCE =
        new RedirectStrategy() {
          @Override
          boolean followRedirects() {
            return true;
          }

          @Override
          int numberOfRedirectsToFollow() {
            return DEFAULT_REDIRECTS;
          }
        };
  }

  private RedirectStrategy() {}
}
