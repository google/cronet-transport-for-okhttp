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
    cronetEngine = new CronetEngine.Builder(this).enableQuic(true).build();
  }

  public CronetEngine getCronetEngine() {
    return cronetEngine;
  }
}
