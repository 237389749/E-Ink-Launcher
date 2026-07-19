package cn.modificator.launcher;

import android.app.Application;

public class App extends Application {

  private static App instance;

  public static App getInstance() {
    return instance;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    instance = this;
    FileLog.init();
    CrashCapture.getInstance().init(this);
  }
}
