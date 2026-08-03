# 📋 פרומפט מוכן להדבקה ב-ChatGPT — בניית APK/AAB ב-Android Studio

העתק את כל מה שמתחת לקו והדבק ל-ChatGPT:

---

אתה מומחה Android שמלווה אותי צעד-אחר-צעד בבניית אפליקציה ב-Android Studio. אני לא מפתח — תן לי הוראות מדויקות ברמת "על מה ללחוץ", חכה שאדווח שסיימתי כל שלב לפני שתמשיך, ואם אשלח צילום מסך של שגיאה — אבחן ותן פתרון מדויק.

## מה יש לי ביד

יש לי פרויקט Android Studio **מוכן ושלם** בתיקייה בשם `android-studio` (חולץ מ-zip). אין בו קוד Java/Kotlin בכלל — זו אפליקציית **TWA (Trusted Web Activity)** שעוטפת משחק ווב קיים. אלה הפרטים הטכניים:

- **סוג:** TWA מבוסס הספרייה `com.google.androidbrowserhelper:androidbrowserhelper:2.5.0`
- **האתר שהאפליקציה פותחת:** `https://gravity-maze.gravity-maze.workers.dev/index.html`
- **applicationId:** `com.gravitymaze.game` — אסור לשנות!
- **גרסאות:** AGP 8.6.0, Gradle 8.9 (wrapper), compileSdk 35, targetSdk 35, minSdk 21, versionCode 1
- **מבנה:** `settings.gradle`, `build.gradle`, `gradle.properties`, `app/build.gradle`, `app/src/main/AndroidManifest.xml` (עם LauncherActivity של הספרייה, asset_statements, אייקונים בכל הרזולוציות ו-splash) — הכל כבר כתוב ותקין.
- מותקן אצלי Android Studio (אם לא — תתחיל בהתקנתו).

## המטרה הסופית — 3 תוצרים

1. **`app-release.aab`** — קובץ Bundle חתום להעלאה ל-Google Play
2. **`app-release.apk`** — קובץ APK חתום להתקנה ישירה בטלפון לבדיקה
3. **טביעת אצבע SHA-256** של מפתח החתימה — אני צריך להעתיק אותה בסוף (היא נחוצה לקובץ assetlinks.json בשרת, מישהו אחר מטפל בזה)

## מה אני צריך שתעשה, בסדר הזה

**שלב א — פתיחה וסנכרון:**
הדרך אותי לפתוח את הפרויקט (File → Open → בחירת תיקיית `android-studio`), לוודא שסנכרון Gradle מצליח. אם הסטודיו מציע עדכון Gradle/AGP — תגיד לי אם לאשר. אם יש שגיאת סנכרון — אבחן לפי ההודעה (בדוק קודם: JDK 17 מוגדר? File → Settings → Build Tools → Gradle → Gradle JDK).

**שלב ב — יצירת keystore וחתימה:**
הדרך אותי דרך Build → Generate Signed App Bundle / APK:
- ליצור keystore חדש: שם קובץ `gravity-maze.keystore`, alias `gravity-maze`, validity 25 שנים
- תזכיר לי לשמור את הקובץ והסיסמה בגיבוי (בלעדיהם אי אפשר לעדכן את האפליקציה בחנות לעולם)
- לבנות **גם AAB וגם APK** (שתי הרצות של האשף, release, אותו keystore)
- תגיד לי איפה בדיוק הקבצים נוצרים במחשב

**שלב ג — טביעת אצבע:**
תן לי את פקודת ה-keytool המדויקת להרצה בטרמינל של Android Studio כדי לחלץ את ה-SHA256 מה-keystore, והסבר איזו שורה להעתיק.

**שלב ד — בדיקה בטלפון:**
הסבר איך להתקין את ה-APK בטלפון אנדרואיד שלי לבדיקה (העברת קובץ + אישור התקנה ממקור לא מוכר), ומה אמור לקרות כשפותחים (המשחק נפתח; ייתכן סרגל דפדפן למעלה — זה תקין בשלב הזה, הוא ייעלם אחרי שטביעת האצבע תעלה לשרת).

## כללים חשובים

- אל תציע לשנות שום קובץ בפרויקט אלא אם יש שגיאה שמחייבת זאת — הפרויקט נבדק ותקין.
- אם משהו נכשל, בקש ממני את הודעת השגיאה המלאה או צילום מסך לפני שאתה מנחש.
- אל תשנה את applicationId, את הדומיין, או את versionCode.
- דבר איתי בעברית פשוטה.

התחל משלב א' עכשיו: שאל אותי אם Android Studio כבר מותקן ואם חילצתי את ה-zip.
