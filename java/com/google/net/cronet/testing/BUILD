load("@build_bazel_rules_android//android:rules.bzl", "android_library")

android_library(
    name = "cronet_engine_test_app_rule",
    testonly = 1,
    srcs = [
        "CronetEngineTestAppRule.java",
    ],
    visibility = [
        "//javatests:__subpackages__",
    ],
    deps = [
        "//java/com/google/net/cronet/testing/testapp:embedded_cronet_test_app_lib",
        "//third_party/java/android_libs/cronet:cronet_api",
        "@maven//:androidx_annotation_annotation",
        "@maven//:androidx_test_core",
        "@maven//:androidx_test_ext_junit",
        "@maven//:junit_junit",
        "@maven//:org_chromium_net_cronet_api",
    ],
)
