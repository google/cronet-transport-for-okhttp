# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# ProGuard rules for the main APK (as opposed to the test APK)

# The main APK indirectly depends on kotlin-stdlib. So does the test APK.
# Therefore Gradle will put the Kotlin stdlib classes in the main APK, and leave
# them out of the test APK. Without this rule, we run the risk that R8 will
# remove some of these classes during shriking, preventing the test APK from
# finding them at runtime.
-keep class kotlin.** { *; }
