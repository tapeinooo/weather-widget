# 縮小（R8）の設定
# 設定に名前で保存している列挙型の名前は残す
-keepclassmembers enum com.example.weatherwidget.** { *; }
# WorkManager の Worker は名前で作られる
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
# スタックトレースを読みやすく
-keepattributes SourceFile,LineNumberTable
