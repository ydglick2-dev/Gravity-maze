# 🤖 בניית AAB ב-Android Studio — מדריך מלא

זהו פרויקט Android Studio **מוכן לחלוטין** — אפליקציית TWA שעוטפת את Gravity Maze.
לא צריך לכתוב שורת קוד. רק לפתוח, לבנות ולחתום.

---

## שלב 1 — פתיחת הפרויקט

1. פתחו **Android Studio** (גרסת 2024 ומעלה מומלץ)
2. **File ← Open** ← בחרו את התיקייה `googleplay/android-studio` (התיקייה הזו)
3. חכו לסנכרון Gradle (בפעם הראשונה זה מוריד קבצים — כמה דקות).
   אם מוצעת הודעה על עדכון Gradle/AGP — אפשר לאשר "Update".

## שלב 2 — יצירת ה-AAB החתום

1. בתפריט: **Build ← Generate Signed App Bundle / APK…**
2. בחרו **Android App Bundle** ← Next
3. תחת Key store path לחצו **Create new…** וממלאים:
   - **Key store path:** בחרו מיקום ושם, למשל `gravity-maze.keystore` — **שמרו את הקובץ הזה לנצח!**
   - **Password:** בחרו סיסמה חזקה (ושמרו אותה!)
   - **Alias:** `gravity-maze`
   - **Validity:** 25 שנים (ברירת המחדל)
   - שם/ארגון — מה שתרצו
4. ← Next ← בחרו **release** ← **Create**
5. בסיום תופיע הודעה עם קישור — הקובץ נמצא ב:
   `app/release/app-release.aab` ← **זה הקובץ שמעלים ל-Google Play** 🎉

> ⚠️ **גיבוי חובה:** בלי קובץ ה-keystore והסיסמה אי אפשר לעדכן את האפליקציה בחנות לעולם.
> שמרו עותק בענן (Google Drive וכו').

## שלב 3 — טביעת האצבע (SHA-256) לאימות הדומיין

כדי שהאפליקציה תיפתח במסך מלא בלי סרגל דפדפן, גוגל דורשת להוכיח שהאתר והאפליקציה שייכים לאותו בעלים.

**בתוך Android Studio:** פתחו את הטרמינל (Terminal בתחתית) והריצו:

```
keytool -list -v -keystore <הנתיב-לקובץ>/gravity-maze.keystore -alias gravity-maze
```

הקלידו את הסיסמה, וחפשו את השורה **SHA256:** — העתיקו את כל המחרוזת (`AA:BB:CC:...`).

**שלחו לי את המחרוזת בצ'אט** ואני אפרוס אותה ל-
`https://gravity-maze.gravity-maze.workers.dev/.well-known/assetlinks.json` (הקובץ כבר קיים ומחכה).

## שלב 4 — העלאה ל-Google Play

1. https://play.google.com/console ← **Create app** (חשבון מפתח: $25 חד-פעמי)
2. שם: Gravity Maze · עברית · משחק · חינם
3. **Production ← Create new release** ← גררו את `app-release.aab`
4. מלאו את הטפסים (החנות מסמנת מה חסר):
   - אייקון 512: `icon-512.png` (בתיקיית המשחק)
   - גרפיקת Feature: `googleplay/feature-graphic-1024x500.png` (מוכנה)
   - 2+ צילומי מסך מהטלפון
   - מדיניות פרטיות: `https://gravity-maze.gravity-maze.workers.dev/privacy.html`
   - שאלון דירוג תוכן: אין אלימות/הימורים ← לכל הגילאים
5. **Send for review** — אישור ראשון לוקח בדרך כלל 2–7 ימים.

## עדכונים עתידיים

- **שינויים במשחק עצמו** (שלבים, פיצ'רים, תיקונים): מתעדכנים באתר — מגיעים לכולם מיד. **לא צריך** גרסה חדשה בחנות.
- **שינוי באריזה** (שם, אייקון): ב-`app/build.gradle` העלו את `versionCode` ב-1, בנו AAB חדש (שלב 2, אותו keystore!) והעלו ל-Play.

## תקלות נפוצות

| בעיה | פתרון |
|---|---|
| סנכרון Gradle נכשל | File ← Settings ← Build Tools ← Gradle ← ודאו JDK 17; או אשרו את עדכון הגרסה שהסטודיו מציע |
| סרגל דפדפן מופיע באפליקציה | טביעת האצבע עוד לא נפרסה לשרת (שלב 3) או שגויה |
| "App not installed" בבדיקה ידנית | הסירו התקנה קודמת עם אותו package |
