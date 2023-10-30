# How to Contribute

We'd love to accept your patches and contributions to this project. There are
just a few small guidelines you need to follow.

## Contributor License Agreement

Contributions to this project must be accompanied by a Contributor License
Agreement (CLA). You (or your employer) retain the copyright to your
contribution; this simply gives us permission to use and redistribute your
contributions as part of the project. Head over to
<https://cla.developers.google.com/> to see your current agreements on file or
to sign a new one.

You generally only need to submit a CLA once, so if you've already submitted one
(even if it was for a different project), you probably don't need to do it
again.

## Building & testing the code

The project uses [Bazel](https://bazel.build/) and requires:

- Android SDK Platform 32
- Android NDK 21+

For local builds, set ANDROID_HOME and ANDROID_NDK_HOME. For example,
```
ANDROID_HOME=$PATH_TO_ANDROID_SDK
ANDROID_NDK_HOME=$PATH_TO_ANDROID_NDK
```

If you run into `sdk/ndk path not set error` while building with the [bazel plugin](https://plugins.jetbrains.com/plugin/9185-bazel-for-android-studio), 
consider setting the `--action_env` bazel flag. For eg:
```
--action_env=ANDROID_NDK_HOME=$PATH_TO_ANDROID_SDK
```

To build the entire repository, run

```
bazel build $PATH_TO_GIT_REPO/...
```

To run all tests, use

```
bazel test $PATH_TO_GIT_REPO/javatests/...
```

To install sample app, use

```
bazel mobile-install //java/com/google/samples/cronet/okhttptransport:okhttp_cronet_transport_sample --fat_apk_cpu=x86_64
```

## Code Reviews

All submissions, including submissions by project members, require review. We
use GitHub pull requests for this purpose. Consult
[GitHub Help](https://help.github.com/articles/about-pull-requests/) for more
information on using pull requests.

## Community Guidelines

This project follows
[Google's Open Source Community Guidelines](https://opensource.google/conduct/).
