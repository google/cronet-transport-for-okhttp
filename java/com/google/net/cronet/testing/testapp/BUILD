load("@build_bazel_rules_android//android:rules.bzl", "android_binary", "android_library")

licenses(["notice"])

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = [
        "//java/com/google/net/cronet/testing:__subpackages__",
        "//javatests:__subpackages__",
    ],
)

android_library(
    name = "embedded_cronet_test_app_lib",
    testonly = 1,
    srcs = glob(["*.java"]),
    deps = [
        "@maven//:org_chromium_net_cronet_api",
        "@maven//:org_chromium_net_cronet_embedded",
    ],
)

android_binary(
    name = "embedded_cronet_test_app",
    testonly = 1,
    manifest = "AndroidManifest.xml",
    multidex = "native",
    deps = [
        ":embedded_cronet_test_app_lib",
    ],
)
