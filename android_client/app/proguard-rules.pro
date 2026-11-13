# JNI uses name-based lookups for these Java-to-native entry points.
-keepclasseswithmembernames,includedescriptorclasses class org.neosteam.revival.MainActivity {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class org.neosteam.revival.gameplay.ChatSession {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class org.neosteam.revival.performance.ScenePreview {
    native <methods>;
}
