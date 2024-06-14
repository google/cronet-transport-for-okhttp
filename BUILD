load("@build_bazel_rules_android//android:rules.bzl", "android_library")
load("@rules_license//rules:license.bzl", "license")
# removed copybara load statement

package(default_applicable_licenses = ["//:license"])

license(
    name = "license",
    package_name = "okhttp_cronet_transport",
    # license_kinds is not needed as it is automatically inferred by blaze
    # but we include it here so that it can be conveniently replaced when
    # exported as bazel does not have this feature yet
    license_kinds = ["@rules_license//licenses/spdx:Apache-2.0"],
)

licenses(["notice"])

exports_files(["LICENSE"])

android_library(
    name = "okhttp_cronet_transport",
    visibility = ["//visibility:public"],
    exports = [
        "//java/com/google/net/cronet/okhttptransport:okhttp_cronet_transport",
    ],
)
