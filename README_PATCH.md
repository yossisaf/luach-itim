# פאץ' — 3 תכונות חדשות ל-LuachItimLvina

## קבצים שהוחלפו במלואם (קיימים כבר בריפו שלך)
- `composeApp/build.gradle.kts` — נוספה תלות `androidx.core.ktx` (נדרשת ל-ShortcutManagerCompat/IconCompat של סעיף 3)
- `composeApp/src/androidMain/AndroidManifest.xml` — נוספו 2 receivers (הווידג'ט החדש + רענון חצות)
- `composeApp/src/androidMain/res/values/strings.xml` — נוסף string אחד
- `composeApp/src/commonMain/kotlin/com/luachitim/ui/LuachApp.kt` — כפתור/מסך "לוח רגיל" + שורת הגדרה לאייקון היומי
- `composeApp/src/commonMain/kotlin/com/luachitim/ui/UnifiedDatePicker.kt` — 4 טבלאות/פונקציות הפכו מ-`private` ל-`internal`

## קבצים חדשים
- `composeApp/src/commonMain/kotlin/com/luachitim/ui/ClassicCalendarScreen.kt`
- `composeApp/src/commonMain/kotlin/com/luachitim/ui/DailyIconSetting.kt`
- `composeApp/src/androidMain/kotlin/com/luachitim/ui/DailyIconSetting.android.kt`
- `composeApp/src/desktopMain/kotlin/com/luachitim/ui/DailyIconSetting.desktop.kt`
- `composeApp/src/androidMain/kotlin/com/luachitim/widget/DynamicIconShortcut.kt`
- `composeApp/src/androidMain/kotlin/com/luachitim/widget/MidnightIconReceiver.kt`
- `composeApp/src/androidMain/kotlin/com/luachitim/widget/LuachPageWidgetProvider.kt`
- `composeApp/src/androidMain/res/layout/widget_luach_page.xml`
- `composeApp/src/androidMain/res/xml/luach_page_widget_info.xml`

## שלוש התכונות
1. **"לוח רגיל"** — כפתור רביעי בסרגל התחתון (אייקון גריד) שפותח מסך גריד-חודשי מלא, עברי/לועזי, עם חגים ונקודת-אירוע ליד כל יום. לחיצה על יום פותחת את אותו תפריט-הקשר הקיים.
2. **ווידג'ט "עמוד הלוח"** — ווידג'ט בית נוסף (250×250dp ומעלה) שמציג את עמוד ה-PDF האמיתי (עמוד ב') של השבוע הנוכחי, לא רק טקסט.
3. **קיצור-דרך עם תאריך יומי** — מתג חדש במסך ההגדרות. אנדרואיד לא מאפשר לשנות בזמן ריצה את אייקון ההפעלה עצמו, אז זהו קיצור-דרך נפרד (pinned shortcut) שהבִּיטמאפ שלו מצויר מחדש כל יום (יום בשבוע / תאריך עברי גדול / חודש) ומתעדכן אוטומטית בחצות.

## איך למזג לתוך גיט אצלך
בהנחה שהתיקייה הזו חולצה לתוך `patch/` בשורש עותק מקומי של הריפו שלך:

```bash
# מתוך שורש הריפו המקומי שלך (איפה ש-settings.gradle.kts נמצא)
cp -r /path/to/patch/composeApp/* composeApp/
cp /path/to/patch/composeApp/build.gradle.kts composeApp/build.gradle.kts

git status                     # לבדוק מה בדיוק השתנה/נוסף
git add -A
git commit -m "Add classic calendar view, page widget, and daily-icon shortcut"
git push
```

או, אם אתה מעדיף לעבור קובץ-קובץ ולוודא ידנית לפני commit — כל קובץ כאן נמצא בדיוק בנתיב היחסי שהוא אמור לשבת בו בריפו, כך שאפשר גם פשוט לגרור-ולהחליף בסייר הקבצים / ב-GitHub Desktop.

## מה עוד כדאי לבדוק לפני build
- ודא ש-`libs.androidx.core.ktx` קיים ב-`gradle/libs.versions.toml` (הוא כבר שם אצלך תחת `[libraries]` בשם `androidx-core-ktx`) — לא נדרש שינוי שם.
- ה-build עצמו (GitHub Actions) לא דורש שינוי - שני ה-workflows הקיימים (`main.yml`, `desktop.yml`) יתפסו את הקבצים החדשים אוטומטית כי הם בתוך `composeApp/src/...`.
- מומלץ להריץ קודם build מקומי/CI ולוודא הידור נקי לפני שמפרסמים release, כרגיל.
