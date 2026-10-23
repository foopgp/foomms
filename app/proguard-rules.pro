# What R8 must leave as it is, and why. Everything else is shrunk and
# obfuscated, as Google Play recommends.

# The OpenPGP API. foodjis answers with these Parcelables, which Android
# unmarshals by their class name, and is called through IOpenPgpService2:
# renamed, they would no longer meet foodjis's own copy of the same classes.
-keep class org.openintents.openpgp.** { *; }

# Stack traces in Play's reports stay readable through the mapping file the
# bundle carries; keep the line numbers it maps.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
