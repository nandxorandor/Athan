# Settings are persisted as enum *names* and read back with valueOf(). If R8
# renames the constants, every stored preference silently falls back to its
# default — the calculation method resets, the chosen athan resets, and nothing
# logs an error. Keep the names for exactly the enums that round-trip through
# SharedPreferences.
-keepclassmembers enum com.ahmedkhalaf.athan.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers enum com.batoulapps.adhan.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Activities, services and receivers are reached from the manifest, which AGP
# already generates keep rules for. Nothing else here uses reflection.
