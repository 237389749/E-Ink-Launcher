package cn.modificator.launcher;

import android.app.Application;

public class App extends Application {
  @Override
  public void onCreate() {
    super.onCreate();
    FileLog.init();
    CrashCapture.getInstance().init(this);
  }
}
