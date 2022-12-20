# Cronet Transport for OkHttp and Retrofit

This package allows OkHttp and Retrofit users to use Cronet as their transport
layer, benefiting from features like QUIC/HTTP3 support and connection
migration.

## Installation

The easiest way to import this library is to include it as a Gradle dependency
in your app's `build.gradle` file. Simply add the following line and specify
the desired version.

```
implementation 'com.google.net.cronet:cronet-okhttp:VERSION'
```

You can also build the library from source. For information how to do that,
see [CONTRIBUTING.md](CONTRIBUTING.md).

## First steps

There are two ways to use this library — either as an OkHttp application
interceptor, or as a `Call` factory.

If your application makes extensive use of application interceptors,
using the library as an interceptor will be more practical as you can keep
your current interceptor logic. Just add an extra interceptor to your client.

> **Note**: Add the Cronet interceptor last, otherwise the subsequent
> interceptors will be skipped.

```java
CronetEngine engine = new CronetEngine.Builder(applicationContext).build();

Call.Factory callFactory = new OkHttpClient.Builder()
   ...
   .addInterceptor(CronetInterceptor.newBuilder(engine).build())
   .build();
```

If you don't make heavy use of OkHttp's interceptors or if you're working with
another library which requires `Call.Factory` instances (e.g. Retrofit), you'll
be better off using the custom call factory implementation.

```java
CronetEngine engine = new CronetEngine.Builder(applicationContext).build();

Call.Factory callFactory = CronetCallFactory.newBuilder(engine).build();
```

And that's it! You can now benefit from the Cronet goodies while using OkHttp
APIs as usual:

```java
String run(String url) throws IOException {
  Request request = new Request.Builder()
      .url(url)
      .build();
  try (Response response = callFactory.newCall(request).execute()) {
    return response.body().string();
  }
}
```

It's almost as simple in Retrofit:

```java
Retrofit retrofit = new Retrofit.Builder()
    .baseUrl("https://api.github.com/")
    .callFactory(callFactory)
    .build();
```

### Getting hold of a Cronet engine

There are several ways to obtain a `CronetEngine` instance. We recommend using
a Google Play Services provider which loads Cronet from the platform. This way
the application doesn't need to pay the binary size cost of carrying Cronet
and the platform ensures that the latest updates and security fixes are
delivered. We also recommend falling back on using plain OkHttp if
the platform-wide Cronet isn't available (e.g. because the device doesn't
integrate with Google Play Services, or the platform has been tampered with).

This setup is demonstrated in the sample application provided with the library.

## Configuration
The transport libraries are configured in two ways. The builders for both the
interceptor and the call factory provide configuration options which directly
affect how the interop layer behaves. Most of the network configuration
(certificate pinning, proxies etc.) should be done directly in the Cronet
engine.

We're open to providing convenience utilities which will simplify configuring
the Cronet engine — please reach out and tell us more about your use case
if this sounds interesting!

## Incompatibilities

While our design principle is to implement the full set of OkHttp APIs
on top of Cronet, it's not always possible due to limitations and/or
fundamental incompatibilities of the two layers. We are aware of the following
list of limitations and features that are not provided by the bridge:

### Common incompatibilities
  - The entirety of OkHttp core is bypassed. This includes caching, retries,
    authentication, and network interceptors. These features have to be enabled
    directly on the Cronet engine or built on top of this library.
  - It's not possible to set multiple values for a single header key in outgoing
    requests, Cronet uses the last value provided.
  - `Accept-Encoding` are automatically populated by Cronet based on the engine
    configuration. Custom values are ignored.
  - The `Response` object doesn't have the following fields set:
    - `handshake`
    - `networkResponse`
    - `cacheResponse`
    - `sentRequestAtMillis` / `receivedResponseAtMillis`
  - The `Request` field under `Response` is set as seen by the outmost layer and
    doesn't reflect internal Cronet transformations.
  - Response parsing logic is different at places. Generally, Cronet is more
    lenient and will silently drop headers/fall back to default values where
    OkHttp might throw an exception (for example, parsing status codes). This
    shouldn't be a concern for typical usage patterns.
  - Generally, while errors convey the same message across plain OkHttp and this
    library, the error message details differ.

### Interceptor incompatibilities
  - `Call` cancellation signals are propagated with a delay.
  - If the Cronet interceptor isn't the last application interceptor, the
    subsequent interceptors are bypassed.
  - Most of the `OkHttpClient` network-related configuration which is handled
    by the core network logic is bypassed and has to be reconfigured directly
    on your `CronetEngine` builder.
  - Intermediate `EventListener` stages are not being reported.

### Call factory incompatibilities
  - `OkHttpClient` configuration is unavailable and bypassed completely.

## For contributors

Please see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

This library is licensed under Apache License Version 2.0.
