# ProGuard rules for the main APK (as opposed to the test APK)

# The main APK indirectly depends on kotlin-stdlib. So does the test APK.
# Therefore Gradle will put the Kotlin stdlib classes in the main APK, and leave
# them out of the test APK. Without this rule, we run the risk that R8 will
# remove some of these classes during shriking, preventing the test APK from
# finding them at runtime.
-keep class kotlin.** { *; }
