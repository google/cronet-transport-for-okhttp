load("@build_bazel_rules_android//android:rules.bzl", "android_binary", "android_instrumentation_test", "android_library", "android_local_test")

licenses(["notice"])

android_library(
    name = "cronet_test_helpers",
    testonly = 1,
    srcs = glob(
        ["*.java"],
        exclude = [
            "*Test.java",
            "CronetEngineTestAppRule.java",
        ],
    ),
    deps = [
        "//third_party/java/android_libs/cronet:cronet_api",
        "@maven//:androidx_annotation_annotation",
        "@maven//:androidx_test_core",
        "@maven//:androidx_test_ext_junit",
        "@maven//:com_google_guava_guava",
        "@maven//:org_chromium_net_cronet_api",
    ],
)

android_local_test(
    name = "RequestBodyConverterTest",
    srcs = [
        "RequestBodyConverterTest.java",
    ],
    manifest = "LocalTestManifest.xml",
    deps = [
        ":cronet_test_helpers",
        "//:okhttp_cronet_transport",
        "@maven//:androidx_annotation_annotation",
        "@maven//:com_google_guava_guava",
        "@maven//:com_google_truth_truth",
        "@maven//:com_squareup_okhttp3_okhttp",
        "@maven//:com_squareup_okio_okio",
        "@maven//:org_chromium_net_cronet_api",
        "@maven//:org_robolectric_robolectric",
        "@robolectric//bazel:android-all",
    ],
)

android_local_test(
    name = "ResponseConverterTest",
    srcs = [
        "ResponseConverterTest.java",
    ],
    manifest = "LocalTestManifest.xml",
    deps = [
        "//:okhttp_cronet_transport",
        "@maven//:androidx_test_ext_junit",
        "@maven//:com_google_guava_guava",
        "@maven//:com_google_truth_truth",
        "@maven//:com_squareup_okhttp3_okhttp",
        "@maven//:com_squareup_okio_okio",
        "@maven//:org_chromium_net_cronet_api",
        "@maven//:org_robolectric_robolectric",
        "@robolectric//bazel:android-all",
    ],
)

android_library(
    name = "cronet_interceptor_test_lib",
    testonly = 1,
    srcs = ["CronetInterceptorEndToEndTest.java"],
    deps = [
        "//:okhttp_cronet_transport",
        "//java/com/google/net/cronet/testing:cronet_engine_test_app_rule",
        "//third_party/java/android_libs/cronet:cronet_pure_api_compiletime",
        "@maven//:androidx_test_core",
        "@maven//:androidx_test_ext_junit",
        "@maven//:androidx_test_runner",  # unuseddeps: keep
        "@maven//:com_google_guava_guava",
        "@maven//:com_google_truth_truth",
        "@maven//:com_squareup_okhttp3_mockwebserver",
        "@maven//:com_squareup_okhttp3_okhttp",
        "@maven//:junit_junit",
        "@maven//:org_chromium_net_cronet_api",
        "@maven//:org_chromium_net_cronet_embedded",
    ],
)

android_binary(
    name = "cronet_interceptor_test_app",
    testonly = 1,
    instruments = "//java/com/google/net/cronet/testing/testapp:embedded_cronet_test_app",
    manifest = "InstrumentationTestManifest.xml",
    multidex = "native",
    deps = [":cronet_interceptor_test_lib"],
)

android_instrumentation_test(
    name = "CronetInterceptorEndToEndTest",
    target_device = "@android_test_support//tools/android/emulated_devices/generic_phone:google_25_x86",
    test_app = ":cronet_interceptor_test_app",
)

android_library(
    name = "call_tests",
    testonly = 1,
    srcs = [
        "CronetCallFactoryCallTest.java",
        "CronetInterceptorCallTest.java",
    ],
    deps = [
        "//:okhttp_cronet_transport",
        "//java/com/google/net/cronet/testing:cronet_engine_test_app_rule",
        "//third_party/java/android_libs/cronet:cronet_pure_api_compiletime",
        "//third_party/okhttp_tests/javatests/okhttp3:call_test_lib",
        "@maven//:androidx_annotation_annotation",
        "@maven//:androidx_test_core",
        "@maven//:androidx_test_ext_junit",
        "@maven//:androidx_test_runner",  # unuseddeps: keep
        "@maven//:com_google_truth_truth",
        "@maven//:com_squareup_okhttp3_mockwebserver",
        "@maven//:com_squareup_okhttp3_okhttp",
        "@maven//:junit_junit",
        "@maven//:org_chromium_net_cronet_api",
        "@maven//:org_chromium_net_cronet_embedded",
    ],
)

android_binary(
    name = "call_test_app",
    testonly = 1,
    instruments = "//java/com/google/net/cronet/testing/testapp:embedded_cronet_test_app",
    manifest = "InstrumentationTestManifest.xml",
    multidex = "native",
    deps = [
        ":call_tests",
    ],
)

android_instrumentation_test(
    name = "CronetInterceptorCallTest",
    size = "large",
    shard_count = 4,
    target_device = "@android_test_support//tools/android/emulated_devices/generic_phone:google_25_x86",
    test_app = ":call_test_app",
)

android_instrumentation_test(
    name = "CronetCallFactoryCallTest",
    size = "large",
    shard_count = 4,
    target_device = "@android_test_support//tools/android/emulated_devices/generic_phone:google_25_x86",
    test_app = ":call_test_app",
)
