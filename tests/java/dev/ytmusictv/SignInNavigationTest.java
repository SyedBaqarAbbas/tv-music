package dev.ytmusictv;

public final class SignInNavigationTest {
    private static int checks;
    public static void main(String[] args) {
        allowed("https://accounts.google.com.pk/accounts/SetSID?opaque=redacted#state", true);
        allowed("https://accounts.google.com.pk:443/accounts/SetSID", true);
        allowed("https://ACCOUNTS.GOOGLE.COM.PK/accounts/SetSID", true);
        allowed("https://accounts.google.com/ServiceLogin", true);
        allowed("https://music.youtube.com/", true);
        allowed("https://www.youtube.com/", true);
        allowed("http://accounts.google.com.pk/accounts/SetSID", false);
        allowed("https://accounts.google.com.pk.evil.invalid/accounts/SetSID", false);
        allowed("https://evilaccounts.google.com.pk/accounts/SetSID", false);
        allowed("https://foo.accounts.google.com.pk/accounts/SetSID", false);
        allowed("https://accounts.google.com.pk@evil.invalid/accounts/SetSID", false);
        allowed("https://user:pass@accounts.google.com.pk/accounts/SetSID", false);
        allowed("https://accounts.google.com.pk:8443/accounts/SetSID", false);
        allowed("https://google.com.evil.invalid/", false);
        allowed("https://accounts.google.com.au/accounts/SetSID", false);
        allowed("javascript:alert(1)", false);
        allowed("file:///accounts/SetSID", false);
        allowed("//accounts.google.com.pk/accounts/SetSID", false);
        allowed("https://accounts.google.com.pk\\@evil.invalid/", false);
        allowed(null, false);
        equal("https://accounts.google.com.pk/accounts/SetSID", SignInActivity.NavigationPolicy.safeLocation(
                "https://accounts.google.com.pk/accounts/SetSID?opaque=redacted#state"));
        equal("https://accounts.google.com.pk/accounts/SetSID", SignInActivity.NavigationPolicy.safeLocation(
                "https://user:pass@accounts.google.com.pk/accounts/SetSID?opaque=redacted#state"));
        equal("https://music.youtube.com/", SignInActivity.NavigationPolicy.safeLocation("https://music.youtube.com?opaque=redacted"));
        equal("[invalid URL]", SignInActivity.NavigationPolicy.safeLocation("javascript:alert(1)"));
        equal("[invalid URL]", SignInActivity.NavigationPolicy.safeLocation(null));
        check(SignInActivity.NavigationPolicy.isMusic("https://music.youtube.com/"));
        check(SignInActivity.NavigationPolicy.isMusic("https://music.youtube.com:443/library?ignored=1"));
        check(!SignInActivity.NavigationPolicy.isMusic("https://accounts.google.com/ServiceLogin"));
        check(!SignInActivity.NavigationPolicy.isMusic("https://music.youtube.com.evil.invalid/"));
        check(!SignInActivity.NavigationPolicy.isMusic("http://music.youtube.com/"));
        check(!SignInActivity.NavigationPolicy.isMusic("https://user@music.youtube.com/"));
        System.out.println("Sign-in navigation checks passed: " + checks);
    }
    private static void check(boolean result) {
        checks++;
        if (!result) throw new AssertionError("Music return check " + checks);
    }
    private static void allowed(String url, boolean expected) {
        checks++;
        if (SignInActivity.NavigationPolicy.allowed(url) != expected) throw new AssertionError("Navigation check " + checks);
    }
    private static void equal(String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("Redaction check " + checks);
    }
}
