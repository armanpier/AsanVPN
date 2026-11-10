package android.content;

import java.io.File;

public abstract class Context {
    public static final int MODE_PRIVATE = 0;

    public abstract String getPackageName();

    public File getDir(String name, int mode) {
        return null;
    }

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return null;
    }

    public android.content.res.AssetManager getAssets() {
        return null;
    }
}
