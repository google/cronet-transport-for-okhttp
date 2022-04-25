/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.net.cronet.okhttptransport;

import com.google.common.base.Verify;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.ExecutionException;
import org.chromium.net.UploadDataProvider;
import org.chromium.net.UploadDataSink;

final class RequestBodyTestReader {

  private final UploadDataProvider providerUnderTest;
  private final ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
  private final ByteArrayOutputStream bodyBytesRead = new ByteArrayOutputStream();
  private final WritableByteChannel bodyBytesChannel = Channels.newChannel(bodyBytesRead);

  RequestBodyTestReader(UploadDataProvider providerUnderTest) {
    this.providerUnderTest = providerUnderTest;
  }

  RequestBodyTestReader readAll() throws Exception {
    if (providerUnderTest.getLength() != -1) {
      readAllKnownBodyLength();
    } else {
      readAllUnknownBodyLength();
    }
    return this;
  }

  byte[] getBody() {
    return bodyBytesRead.toByteArray();
  }

  private void readAllUnknownBodyLength() throws Exception {
    boolean interrupted = false;

    while (!interrupted) {
      buffer.clear();
      TestReadDataSink sink = new TestReadDataSink();

      providerUnderTest.read(sink, buffer);
      interrupted = sink.waitForResult();

      buffer.flip();
      bodyBytesChannel.write(buffer);
    }
  }

  private void readAllKnownBodyLength() throws Exception {
    long remainingBodyLength = providerUnderTest.getLength();

    while (remainingBodyLength > 0) {
      buffer.clear();
      int chunkSize = (int) Math.min(remainingBodyLength, buffer.capacity());
      buffer.limit(chunkSize);
      TestReadDataSink sink = new TestReadDataSink();

      providerUnderTest.read(sink, buffer);
      Verify.verify(!sink.waitForResult());

      buffer.flip();
      bodyBytesChannel.write(buffer);
      remainingBodyLength -= buffer.limit();
    }
  }

  private static class TestReadDataSink extends UploadDataSink {

    private final SettableFuture<Boolean> result = SettableFuture.create();

    @Override
    public void onReadSucceeded(boolean b) {
      result.set(b);
    }

    @Override
    public void onReadError(Exception e) {
      result.setException(e);
    }

    public boolean waitForResult() throws ExecutionException {
      return Uninterruptibles.getUninterruptibly(result);
    }

    @Override
    public void onRewindSucceeded() {
      throw new UnsupportedOperationException();

    }

    @Override
    public void onRewindError(Exception e) {
      throw new UnsupportedOperationException();
    }
  }
}
