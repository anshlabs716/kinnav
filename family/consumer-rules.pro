# Keep rules for consumers that minify. Mirrors the blocks Luogo shipped in its app
# proguard file: serialization serializers, Room, and the Bouncy Castle classes that are
# only referenced by name.
-keep class org.geonav.family.domain.** { *; }
-keepclassmembers class org.geonav.family.domain.** {
    *** Companion;
}
-keepclasseswithmembers class org.geonav.family.domain.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class org.geonav.family.db.** { *; }

-keep class org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters { *; }
-keep class org.bouncycastle.crypto.params.Ed25519PublicKeyParameters { *; }
-keep class org.bouncycastle.crypto.signers.Ed25519Signer { *; }
-keep class org.bouncycastle.util.encoders.Base64 { *; }
-dontwarn org.bouncycastle.**

-dontwarn okhttp3.**
-dontwarn okio.**
