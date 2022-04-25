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
