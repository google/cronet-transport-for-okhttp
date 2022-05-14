load("@build_bazel_rules_android//android:rules.bzl", "android_library")

licenses(["notice"])

# OkHttp and OkIO should be provided by the application that integrates with
# this library. As different applications might use different versions we don't
# bundle it with the transport bridge.
android_library(
    name = "okhttp_cronet_transport_compile_only_deps",
    neverlink = 1,
    visibility = ["//visibility:private"],
    exports = [
        "@maven//:com_squareup_okhttp3_okhttp",
        "@maven//:com_squareup_okio_okio",
    ],
)

android_library(
    name = "okhttp_cronet_transport",
    srcs = glob(["*.java"]),
    manifest = "AndroidManifest.xml",
    visibility = [
        "//:__pkg__",
    ],
    deps = [
        ":okhttp_cronet_transport_compile_only_deps",
        "@maven//:androidx_annotation_annotation",
        "@maven//:com_google_code_findbugs_jsr305",
        "@maven//:com_google_guava_guava",
        "@maven//:org_chromium_net_cronet_api",
    ],
)
