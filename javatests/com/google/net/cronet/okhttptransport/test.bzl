load("//tools/build_defs/android:rules.bzl", "android_instrumentation_test")

TEST_DEVICES = [
    # The oldest API level that we support (min SDK).
    "google_24_x86_gms_stable",
    # Also test on the latest version.
    "google_slim_36_x86_64_gms_stable",
]

def test(name, additional_devices = [], excluded_devices = [], **kwargs):
    """
    Creates an android_instrumentation_test for each device in TEST_DEVICES,
    plus any additional devices, excluding any in excluded_devices.
    """

    for device in additional_devices:
        if device in TEST_DEVICES:
            fail(device)
    for device in excluded_devices:
        if device not in TEST_DEVICES:
            fail(device)
    for device in TEST_DEVICES + additional_devices:
        if device in excluded_devices:
            continue
        android_instrumentation_test(
            name = name + "_" + device,
            target_device = "@android_test_support//tools/android/emulated_devices/generic_phone:" + device,
            **kwargs
        )
