load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
RULES_JVM_EXTERNAL_TAG = "4.2"
RULES_JVM_EXTERNAL_SHA = "cd1a77b7b02e8e008439ca76fd34f5b07aecb8c752961f9640dea15e9e5ba1ca"
http_archive(
    name = "rules_jvm_external",
    strip_prefix = "rules_jvm_external-%s" % RULES_JVM_EXTERNAL_TAG,
    sha256 = RULES_JVM_EXTERNAL_SHA,
    url = "https://github.com/bazelbuild/rules_jvm_external/archive/%s.zip" % RULES_JVM_EXTERNAL_TAG,
)
load("@rules_jvm_external//:repositories.bzl", "rules_jvm_external_deps")
rules_jvm_external_deps()
load("@rules_jvm_external//:setup.bzl", "rules_jvm_external_setup")
rules_jvm_external_setup()
http_archive(
    name = "robolectric",
    urls = ["https://github.com/robolectric/robolectric-bazel/archive/4.7.3.tar.gz"],
    strip_prefix = "robolectric-bazel-4.7.3",
)
load("@robolectric//bazel:robolectric.bzl", "robolectric_repositories")
robolectric_repositories()
load("@rules_jvm_external//:defs.bzl", "maven_install")
maven_install(
    artifacts = [
        # OkHttp
        "com.squareup.okhttp3:okhttp:3.12.13",
        "com.squareup.okhttp3:mockwebserver:3.12.13",
        "com.squareup.okio:okio:2.10.0",
        # Cronet
        "org.chromium.net:cronet-api:98.4758.101",
        "org.chromium.net:cronet-embedded:98.4758.101",
        # Implementation dependencies
        "com.google.guava:guava:31.1-android",
        "com.google.code.findbugs:jsr305:3.0.2",
        "com.android.support:appcompat-v7:28.0.0",
        # Testing
        "org.robolectric:robolectric:4.7.3",
        "com.google.truth:truth:1.1.3",
        "androidx.annotation:annotation:1.3.0",
        "androidx.test:core:1.3.0",
        "androidx.test.ext:junit:1.1.1",
        "androidx.test:runner:1.4.0",
        "junit:junit:4.13.2",
        # Sample app dependencies
        "com.google.android.gms:play-services-tasks:18.0.1",
        "com.google.android.gms:play-services-cronet:18.0.1",
    ],
    repositories = [
        "https://maven.google.com",
        "https://repo1.maven.org/maven2",
    ],
)
# Load the Android build rules
http_archive(
    name = "build_bazel_rules_android",
    urls = ["https://github.com/bazelbuild/rules_android/archive/v0.1.1.zip"],
    sha256 = "cd06d15dd8bb59926e4d65f9003bfc20f9da4b2519985c27e190cddc8b7a7806",
    strip_prefix = "rules_android-0.1.1",
)
# Configure Android SDK Path
load("@build_bazel_rules_android//android:rules.bzl", "android_sdk_repository")
android_sdk_repository(
    name = "androidsdk",
    api_level = 32,
    build_tools_version = "30.0.2"
)

ATS_TAG = "master"
http_archive(
    name = "android_test_support",
    strip_prefix = "android-test-%s" % ATS_TAG,
    urls = ["https://github.com/android/android-test/archive/refs/heads/master.zip"],
)
load("@android_test_support//:repo.bzl", "android_test_repositories")
android_test_repositories()
