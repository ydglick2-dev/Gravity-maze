# Gravity Maze — אפליקציית אנדרויד (APK)

עטיפת אנדרויד למשחק Gravity Maze. המשחק עצמו נשאר קובץ ה‑web שבשורש הריפו — הוא נארז
לתוך ה‑APK בזמן ה‑build, כך שאין עותק שני שצריך לעדכן.

## מה יש באפליקציה

- **המשחק**, עובד לגמרי אופליין. הקבצים נטענים דרך `WebViewAssetLoader` על מקור
  `https://appassets.androidplatform.net`, ולא דרך `file://` — זה מה שגורם ל‑service worker,
  ל‑localStorage ולחיישן ההטיה לעבוד בדיוק כמו בגרסת הדפדפן (הדפדפן חוסם חיישנים במקור לא מאובטח).
- **טאב "מועדון"** — כפתור קטן בפינה התחתונה פותח דפדפן פנימי לאתר המועדון/הקורס שלכם.
  בפעם הראשונה מזינים את כתובת האתר, מתחברים עם המנוי שלכם, והסשן נשמר — בפעם הבאה המועדון
  נפתח ישירות מתוך האפליקציה. אפשר להגיע לשם גם בלחיצה ארוכה על אייקון האפליקציה.
  זהו *צפייה* באתר, לא העתקה שלו: שום תוכן לא מורד ולא נשמר בתוך ה‑APK.

## בנייה

```bash
cd android
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

דרוש JDK 17+ ו‑Android SDK (compileSdk 35, build-tools 35). אם ה‑SDK לא ב‑`ANDROID_SDK_ROOT`,
צרו `android/local.properties` עם `sdk.dir=/path/to/android-sdk`.

בלי סביבת פיתוח מקומית: הפעילו את ה‑workflow **Build APK** בלשונית Actions, וההרצה מייצרת
את שני ה‑APK כ‑artifact להורדה.

## התקנה על הטלפון

שני ה‑APK חתומים במפתח debug, כלומר ניתנים להתקנה ישירה אך לא להעלאה לחנות. מעבירים את הקובץ
לטלפון ופותחים אותו; אנדרויד יבקש אישור חד‑פעמי להתקנה ממקור לא מוכר. לפרסום בחנות צריך
keystore אמיתי במקום `signingConfigs.debug` שב‑`app/build.gradle`.

## הערות

- כניסה עם Google לאתר המועדון עשויה להיחסם בתוך WebView (מדיניות של Google). במקרה כזה
  יש כפתור **דפדפן** שפותח את אותו דף בדפדפן המערכת.
- `versionCode`/`versionName` ב‑`app/build.gradle` עוקבים אחרי גרסת המשחק (v27). מעלים אותם
  בכל שחרור חדש.
