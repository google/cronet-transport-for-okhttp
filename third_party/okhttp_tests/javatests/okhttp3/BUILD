load("@build_bazel_rules_android//android:rules.bzl", "android_library")

licenses(["notice"])

android_library(
    name = "call_test_lib",
    testonly = 1,
    srcs = glob(["*.java"]),
    visibility = ["//javatests:__subpackages__"],
    deps = [
        "//java/com/google/testing/mockito:mockito-android",
        "//third_party/java/android_libs/cronet:cronet_pure_api_compiletime",
        "@maven//:androidx_annotation_annotation",
        "@maven//:androidx_test_core",
        "@maven//:androidx_test_ext_junit",
        "@maven//:com_google_code_findbugs_jsr305",
        "@maven//:com_google_guava_guava",
        "@maven//:com_google_truth_truth",
        "@maven//:com_squareup_okhttp3_mockwebserver",
        "@maven//:com_squareup_okhttp3_okhttp",
        "@maven//:com_squareup_okio_okio",
        "@maven//:junit_junit",
        "@maven//:org_chromium_net_cronet_api",
        "@maven//:org_chromium_net_cronet_embedded",
    ],
)
