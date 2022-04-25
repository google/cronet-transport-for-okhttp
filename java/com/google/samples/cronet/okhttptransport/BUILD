load("@build_bazel_rules_android//android:rules.bzl", "android_binary")

licenses(["notice"])

android_binary(
    name = "okhttp_cronet_transport_sample",
    srcs = glob(["*.java"]),
    manifest = "AndroidManifest.xml",
    multidex = "legacy",
    resource_files = glob(["res/**"]),
    deps = [
        "//:okhttp_cronet_transport",
        "//third_party/java/androidx/appcompat",
        "@maven//:com_google_android_gms_play_services_cronet",
        "@maven//:com_google_android_gms_play_services_tasks",
        "@maven//:com_google_code_findbugs_jsr305",
        "@maven//:com_google_guava_guava",
        "@maven//:com_squareup_okhttp3_okhttp",
        "@maven//:com_squareup_okio_okio",
        "@maven//:org_chromium_net_cronet_api",
        "@maven//:org_chromium_net_cronet_embedded",
    ],
)
