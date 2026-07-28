# אפליקציות אנדרויד

מודול אחד שמייצר **שתי אפליקציות נפרדות** (product flavors). הן מותקנות זו לצד זו ולא חולקות
כלום בזמן ריצה — `applicationId` שונה פירושו אחסון, עוגיות והגדרות נפרדים.

| flavor | applicationId | שם על המסך | מה יש בפנים |
| --- | --- | --- | --- |
| `game` | `com.gravitymaze.app` | Gravity Maze | המשחק, ארוז אופליין, עם כפתור קטן שפותח את המועדון |
| `club` | `com.ydglick.club` | המועדון שלי | רק המועדון. המשחק לא נמצא ב‑APK הזה בכלל |

## אפליקציית המועדון (`club`)

אפליקציה עצמאית שנפתחת ישר על המועדון: אין בה משחק, יש לה שם ואייקון משלה, ו‑`MainActivity`
של המשחק בכלל לא נכללת ב‑APK.

בפעם הראשונה מזינים את כתובת אתר המועדון, מתחברים עם המנוי, והסשן נשמר — מכאן זו האפליקציה
של המועדון. יש תמיכה בווידאו במסך מלא, בהורדות ובעוגיות צד‑שלישי (שרוב מסכי ההתחברות צריכים),
וכפתור **דפדפן** שמעביר את הדף הנוכחי לדפדפן המערכת.

זו *צפייה* באתר, לא העתקה שלו: שום תוכן לא מורד ולא נשמר בתוך ה‑APK.

### שינוי שם ואייקון

- שם: `app/src/club/res/values/strings.xml` → `app_name`.
- אייקון: החליפו את קובצי ה‑PNG ב‑`app/src/club/res/mipmap-*/`. הנוכחיים נוצרו ע"י
  הסקריפט שמייצר סימן גיאומטרי ניטרלי, והם רק ברירת מחדל.
- שם החבילה (`com.ydglick.club`) נמצא ב‑`app/build.gradle`. שינוי שלו אחרי התקנה מחייב
  הסרה והתקנה מחדש, כי אנדרויד רואה בזה אפליקציה אחרת.

## אפליקציית המשחק (`game`)

המשחק עצמו נשאר קובץ ה‑web שבשורש הריפו — הוא נארז לתוך ה‑APK בזמן ה‑build (מקבצים חופשיים
אם `index.html` קיים בשורש, אחרת מתוך `maze-v*.zip`), כך שאין עותק שני שצריך לעדכן.

הקבצים נטענים דרך `WebViewAssetLoader` על מקור `https://appassets.androidplatform.net`,
ולא דרך `file://` — זה מה שגורם ל‑service worker, ל‑localStorage ולחיישן ההטיה לעבוד בדיוק כמו
בגרסת הדפדפן (הדפדפן חוסם חיישנים במקור לא מאובטח).

## בנייה

```bash
cd android
./gradlew assembleClubRelease   # app/build/outputs/apk/club/release/app-club-release.apk
./gradlew assembleGameRelease   # app/build/outputs/apk/game/release/app-game-release.apk
```

דרוש JDK 17+ ו‑Android SDK (compileSdk 35, build-tools 35). אם ה‑SDK לא ב‑`ANDROID_SDK_ROOT`,
צרו `android/local.properties` עם `sdk.dir=/path/to/android-sdk`.

בלי סביבת פיתוח מקומית: הפעילו את ה‑workflow **Build APK** בלשונית Actions, וההרצה מייצרת
את שני ה‑APK כ‑artifact להורדה.

## התקנה

ה‑APK חתומים במפתח debug — ניתנים להתקנה ישירה אך לא להעלאה לחנות. מעבירים את הקובץ לטלפון
ופותחים אותו; אנדרויד יבקש אישור חד‑פעמי להתקנה ממקור לא מוכר. לפרסום בחנות צריך keystore
אמיתי במקום `signingConfigs.debug` שב‑`app/build.gradle`.

## הערות

- כניסה עם Google לאתר המועדון עשויה להיחסם בתוך WebView (מדיניות של Google). במקרה כזה
  משתמשים בכפתור **דפדפן**.
- `versionCode`/`versionName` מוגדרים לכל flavor בנפרד ב‑`app/build.gradle`. מעלים אותם
  בכל שחרור.
