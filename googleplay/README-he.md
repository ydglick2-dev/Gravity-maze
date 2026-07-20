# 📲 Gravity Maze ב-Google Play — מדריך מלא

המשחק הוא PWA, והדרך הרשמית של גוגל להעלות PWA ל-Play היא **TWA (Trusted Web Activity)** —
אפליקציית אנדרואיד "עוטפת" שפותחת את המשחק מהאתר במסך מלא, בלי סרגל דפדפן.
היתרון: כל עדכון שאתם עושים למשחק באתר מגיע **מיד** לכל מי שהתקין מה-Play — בלי להעלות גרסה חדשה לחנות.

הדומיין של האפליקציה: `https://gravity-maze.gravity-maze.workers.dev` (מראה ה-Cloudflare —
בשליטתנו המלאה, כולל `/.well-known/assetlinks.json` שנדרש לאימות).

---

## מה צריך מראש

| דרישה | פרטים |
|---|---|
| חשבון Google Play Console | חד-פעמי, $25 — https://play.google.com/console/signup |
| Node.js 18+ | מותקן במחשב |
| JDK 17 + Android SDK | Bubblewrap מציע להוריד אוטומטית בהרצה הראשונה — פשוט תאשרו |

## שלב 1 — התקנת Bubblewrap (כלי רשמי של גוגל)

```bash
npm install -g @bubblewrap/cli
```

## שלב 2 — בניית פרויקט האנדרואיד

מתוך התיקייה `googleplay/` (שכוללת כבר `twa-manifest.json` מוכן):

```bash
cd googleplay
bubblewrap build
```

- בהרצה הראשונה Bubblewrap יציע להוריד JDK ו-Android SDK — אשרו.
- כשיישאל על יצירת **keystore** (מפתח חתימה) — אשרו וצרו סיסמה. **שימרו את הקובץ
  `android.keystore` והסיסמה במקום בטוח!** בלעדיהם אי אפשר לעדכן את האפליקציה בעתיד.
- בסוף נוצרים שני קבצים:
  - `app-release-bundle.aab` ← זה מה שמעלים ל-Play
  - `app-release-signed.apk` ← להתקנה ידנית לבדיקה

## שלב 3 — טביעת האצבע (SHA-256) וקובץ האימות

הפקודה:

```bash
bubblewrap fingerprint
# או ידנית:
keytool -list -v -keystore android.keystore -alias gravity-maze | grep SHA256
```

מעתיקים את טביעת האצבע (מחרוזת בסגנון `AA:BB:CC:...`) ואז:

1. פותחים את `assetlinks-template.json` שבתיקייה הזו
2. מחליפים את `REPLACE_WITH_YOUR_SHA256_FINGERPRINT` בטביעת האצבע האמיתית
3. **שולחים לי את טביעת האצבע בצ'אט** — ואני אפרוס את הקובץ לכתובת
   `https://gravity-maze.gravity-maze.workers.dev/.well-known/assetlinks.json`
   (או פורסים לבד: מעתיקים את הקובץ ל-`site/.well-known/assetlinks.json` ומריצים `wrangler deploy`)

בלי הקובץ הזה האפליקציה תעבוד אבל תציג סרגל דפדפן למעלה — איתו היא נראית כמו אפליקציה אמיתית.

## שלב 4 — העלאה ל-Play Console

1. נכנסים ל-https://play.google.com/console ← **Create app**
2. שם: `Gravity Maze` · שפה: עברית · סוג: משחק · חינם
3. תחת **Production ← Create new release** מעלים את `app-release-bundle.aab`
4. ממלאים את הטפסים הנדרשים (החנות מסמנת מה חסר):
   - **תיאור קצר:** משחק מבוך הטיה — הטו את הטלפון ואל תיפלו!
   - **תיאור מלא:** 1000 שלבים, 100 עולמות, בוסים, סקינים של כדורגלנים, מרוצים חיים עד 4 שחקנים, פס עונה, קווסטים יומיים ועוד.
   - **גרפיקה:** אייקון 512×512 (יש — `icon-512.png`), feature graphic ‏1024×500 (אפשר לבקש ממני), וצילומי מסך מהטלפון (לפחות 2).
   - **Content rating:** שאלון קצר — משחק ללא אלימות/הימורים ← דירוג לכולם.
   - **Privacy policy:** יש לנו — `https://gravity-maze.gravity-maze.workers.dev/privacy.html`
   - **Data safety:** המשחק שומר שם משתמש + התקדמות בענן. אין פרסומות, אין מכירת נתונים.
5. שולחים לבדיקה (Review) — אישור ראשון לוקח בדרך כלל כמה ימים.

## עדכוני משחק אחרי הפרסום

- **עדכון תוכן/פיצ'רים:** פשוט מפרסמים לאתר כרגיל — כל המשתמשים מקבלים מיד. לא צריך Play.
- **עדכון האריזה עצמה** (שם, אייקון, גרסת אנדרואיד): מעדכנים `twa-manifest.json`,
  מעלים `appVersionCode` ב-1, `bubblewrap build`, ומעלים `aab` חדש ל-Play.

## פתרון תקלות

| בעיה | פתרון |
|---|---|
| סרגל דפדפן מופיע למעלה | `assetlinks.json` לא נפרס / טביעת אצבע שגויה — שלב 3 |
| "App not installed" בבדיקת APK | הסירו גרסה קודמת עם אותו packageId |
| Bubblewrap נכשל על JDK | `bubblewrap doctor` מראה מה חסר |
