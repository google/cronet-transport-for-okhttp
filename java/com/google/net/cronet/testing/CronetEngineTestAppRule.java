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

package com.google.net.cronet.testing;

import android.os.StrictMode;
import android.os.StrictMode.ThreadPolicy;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ActivityScenario.ActivityAction;
import com.google.net.cronet.testing.testapp.MainActivity;
import org.chromium.net.CronetEngine;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public final class CronetEngineTestAppRule implements TestRule {

  private CronetEngine engine;

  public CronetEngine getEngine() {
    return engine;
  }

  @Override
  public Statement apply(Statement base, Description description) {
    return new Statement() {
      @Override
      public void evaluate() {
        try (ActivityScenario<MainActivity> activityScenario =
            ActivityScenario.launch(MainActivity.class)) {
          activityScenario.onActivity(
              createCronetTestAppAction(
                  engine -> {
                    try {
                      CronetEngineTestAppRule.this.engine = engine;
                      base.evaluate();
                    } finally {
                      CronetEngineTestAppRule.this.engine = null;
                    }
                  }));
        }
      }
    };
  }

  private static ActivityAction<MainActivity> createCronetTestAppAction(
      CronetEngineAction cronetEngineAction) {
    return activity -> {
      StrictMode.setThreadPolicy(ThreadPolicy.LAX);
      try {
        cronetEngineAction.perform(activity.getCronetEngine());
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      } catch (Throwable t) {
        throw new AssertionError("Exception and errors are both handled above!", t);
      }
    };
  }

  interface CronetEngineAction {
    void perform(CronetEngine engine) throws Throwable;
  }
}
