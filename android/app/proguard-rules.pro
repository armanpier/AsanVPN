# Keep Models for Gson
-keep class com.asanvpn.app.data.models.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
