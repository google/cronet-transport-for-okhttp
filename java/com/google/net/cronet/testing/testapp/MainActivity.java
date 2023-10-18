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

package com.google.net.cronet.testing.testapp;

import android.app.Activity;
import android.os.Bundle;
import org.chromium.net.CronetEngine;

/**
 * Entry point for the test application.
 */
public final class MainActivity extends Activity {

  private CronetEngine cronetEngine;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    initCronetEngine();
  }

  private void initCronetEngine() {
    cronetEngine = new CronetEngine.Builder(this).enableBrotli(true).build();
  }

  public CronetEngine getCronetEngine() {
    return cronetEngine;
  }
}
