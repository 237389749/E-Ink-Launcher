package cn.modificator.launcher;

import android.app.Application;

public class App extends Application {

  private static App instance;

  @Override
  public void onCreate() {
    super.onCreate();
    instance = this;
    CrashCapture.getInstance().init(this, 1, Launcher.class);
  }

  public static App getInstance() {
    return instance;
  }
}
