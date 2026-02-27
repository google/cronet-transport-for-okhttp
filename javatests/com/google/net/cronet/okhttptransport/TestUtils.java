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
