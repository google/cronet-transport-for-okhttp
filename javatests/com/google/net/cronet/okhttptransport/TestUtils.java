/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.net.cronet.okhttptransport;

import static com.google.common.truth.Truth.assertThat;

import java.util.Random;
import okio.Buffer;

final class TestUtils {

  private TestUtils() {}

  /**
   * Generates a buffer with random bytes.
   *
   * @param byteCount The number of bytes to generate.
   * @return A buffer with random bytes.
   */
  static Buffer generateRandomBytes(int byteCount) {
    Buffer buffer = new Buffer();
    byte[] bytes = new byte[8192];
    Random random = new Random();

    while (buffer.size() < byteCount) {
      random.nextBytes(bytes);
      int remaining = byteCount - (int) buffer.size();
      int toWrite = Math.min(bytes.length, remaining);
      buffer.write(bytes, 0, toWrite);
    }

    assertThat(buffer.size()).isEqualTo(byteCount);

    return buffer;
  }

  /**
   * Generates an array with random bytes.
   *
   * @param byteCount The number of bytes to generate.
   * @return An array with random bytes.
   */
  static byte[] generateRandomBytesArray(int byteCount) {
    return generateRandomBytes(byteCount).readByteArray();
  }
}
