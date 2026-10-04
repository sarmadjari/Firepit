# Release keep rules for R8 (build plan, Stage 9). Libraries bring their own
# consumer rules; these are only what Firepit itself needs on top.

# SQLCipher's native code calls back into these classes by name.
-keep class net.zetetic.database.** { *; }
