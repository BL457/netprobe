# Rhino (tools platform G13). Scripts run in interpreted mode only (optimization level -1), so no class is generated at run time.
# Rhino loads its message bundle by name and a few classes by reflection; keep those, silence the optional pieces the app never uses.
-keep class org.mozilla.javascript.Context { *; }
-keep class org.mozilla.javascript.engine.RhinoScriptEngineFactory { *; }
-keepclassmembers class org.mozilla.javascript.** { public static final java.lang.String MESSAGES_*; }
-keep class org.mozilla.javascript.resources.** { *; }
-dontwarn org.mozilla.javascript.tools.**
-dontwarn org.mozilla.javascript.xml.impl.xmlbeans.**
-dontwarn org.mozilla.javascript.optimizer.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-dontwarn org.mozilla.classfile.**
