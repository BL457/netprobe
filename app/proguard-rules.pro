# Rhino (tools platform G13). Scripts run in interpreted mode only (optimization level -1), so no class is generated at run time.
#
# Rhino finds a few of its own classes by name (Kit.classOrNull). Context creates the interpreter with Class.forName on
# "org.mozilla.javascript.Interpreter", so R8 must neither rename nor remove it, or every script would fail to compile on the device.
# The optimizer (Codegen) is looked up the same way but is deliberately left out: with no Codegen Rhino stays interpreted.
-keep class org.mozilla.javascript.Interpreter { <init>(); }
-keepnames class org.mozilla.javascript.ContextFactory
# The message bundle is a plain resource (org/mozilla/javascript/resources/Messages*.properties); keep the names of its holder classes readable.
-keep class org.mozilla.javascript.resources.** { *; }
# Parts of Rhino the app never reaches: the command line tools, XML beans, the optimizer, the JSR 223 binding and java.beans (absent on Android).
-dontwarn org.mozilla.javascript.tools.**
-dontwarn org.mozilla.javascript.xml.impl.xmlbeans.**
-dontwarn org.mozilla.javascript.optimizer.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-dontwarn org.mozilla.classfile.**
