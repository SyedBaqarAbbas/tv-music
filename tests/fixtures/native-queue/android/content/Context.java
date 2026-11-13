package android.content;
/** Test-only minimal platform surface; never compiled into the APK. */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
}
