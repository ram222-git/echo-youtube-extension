# Add the missing rules to suppress warnings about java.beans classes
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor

## Rules for NewPipeExtractor
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**

# Rhino JavaScript engine
-dontwarn javax.script.** 
-dontwarn jdk.dynalink.**

# JSoup re2j optional dependency
-dontwarn com.google.re2j.Matcher
-dontwarn com.google.re2j.Pattern

-keep class kotlinx.serialization.** { *; }