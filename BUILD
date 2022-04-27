load("@build_bazel_rules_android//android:rules.bzl", "android_library")
load("//devtools/copybara/rules:copybara.bzl", "copybara_config_test")

licenses(["notice"])

exports_files(["LICENSE"])

android_library(
    name = "okhttp_cronet_transport",
    visibility = ["//visibility:public"],
    exports = [
        "//java/com/google/net/cronet/okhttptransport:okhttp_cronet_transport",
    ],
)

copybara_config_test(
    name = "copybara_config_test",
    config = "copy.bara.sky",
)
