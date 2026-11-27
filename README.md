# Office To-Do (Android)

A simple native Android to-do app for office work: bank due dates, EMI or credit-card
payments, month-end closing, tax filing, payroll, and any other important task.
Its main job is to **ring a real alarm and show a notification at the exact time, every time.**

## Features
- **iOS-style design**: large bold title that shrinks on scroll, light grey background with white grouped
  rounded lists (true black in dark mode), round tick circles, “+ New Task” bar, centred titles with ‹ back,
  grouped form with green switches, Android calendar date picker and clock-dial time picker
- **Bottom tabs**: **Today** (overdue + today, red badge = overdue count) · **Upcoming** · **Done** · **Settings**;
  “+” at the top right adds a task
- Clean Material 3 design. **Appearance** settings (Settings → Appearance):
  - Theme: System / Light / Dark
  - Colour: Blue, Green, Purple, Orange, Teal, Pink, or **Wallpaper colours** (Material You, Android 12+)
  - Task list style: **Grouped** (iOS inset list) or **Cards**
  - **Large text** option
- Tasks grouped as **Overdue / Today / Tomorrow / This week / Later / Completed**, with colour stripes
  (red = overdue, orange = important) and friendly times like "Tomorrow, 10:00 AM · in 18 hours"
- **Swipe right = Done, swipe left = Delete**, with **Undo**
- **Home screen widget**: shows your next pending tasks (overdue in red), tap ✔ to mark one done,
  tap **+** to add a task; it resizes and updates instantly when tasks change
- **Backup & restore** (Settings → Backup & restore): saves all tasks to a JSON file in a place you choose
  (Google Drive, phone storage…). After the first backup, the file is **updated automatically after every change**.
  Restore on the same or a new phone, either adding to or replacing current tasks. All alarms are set again.
- Add a task with a title, notes, date and time
- Repeat: never, daily, weekly, monthly (same date), **month-end** (last day), **quarterly** (every 3 months), yearly
- **Working day** option for repeating tasks (all except Daily): if the date falls on a weekly off, ring on the
  **same day**, the **previous working day** or the **next working day**. Weekly off (Settings): Saturday & Sunday,
  Sunday only, or Sunday + 2nd & 4th Saturday
- Early reminder: 15 min, 1 hour, 1, 2 or 3 days, or 1 week before the due time
- **Important** tasks show a red "IMPORTANT WORK – you must do this now!" screen and
  **ring again every 10 minutes until you tap Done**
- Full-screen alarm over the lock screen with **Done / Snooze 10 min / Snooze 1 hour**
- An "Alarm reliability" screen that checks the phone settings and fixes them in one tap, plus a **test alarm** button

## How it makes sure alarms ring
| Risk | What the app does |
|---|---|
| Doze or battery saver delays alarms | Uses `AlarmManager.setAlarmClock()`, the highest-priority system alarm (the same kind the Clock app uses) |
| Exact-alarm permission | `USE_EXACT_ALARM` (granted automatically on Android 13+), `SCHEDULE_EXACT_ALARM` on Android 12, and a fallback alarm if it's denied |
| Phone restart | Re-arms all alarms on boot, **even before the first unlock** (direct-boot storage) |
| Time or timezone change, app update | Re-arms all alarms |
| Alarm missed while the phone was off | Rings as soon as the phone is back on |
| Silent mode | Plays on the ALARM sound stream; if the alarm volume is 0 it is raised while ringing |
| Lock screen | Full-screen alarm activity + ongoing notification |
| Ringing service blocked | Backup: an "insistent" notification that loops the alarm sound |
| User ignores it | Important tasks ring again every 10 minutes and stay in the notification shade |
| App killed | Every time you open the app, all alarms are re-armed |

### What you must do once on your phone
Open **Settings → Alarm reliability** and make every item green. On Xiaomi, OPPO, vivo,
realme, OnePlus, Huawei or Samsung phones, also turn on **Autostart** and set battery usage to
**Unrestricted** (the screen shows instructions for your brand). Then tap **Test alarm**
and lock the phone.

> Never use **Force stop** on the app. Android then cancels all of the app's alarms until
> you open it again. No app can work around this, so don't do it.

## Get the APK
Download **OfficeToDo.apk** from the latest release:
https://github.com/MySoftwares1996/To-do/releases/latest
and install it on the phone (allow "Install unknown apps").

## Over-the-air (OTA) updates
- Every push to the default branch builds the app and publishes it as a GitHub Release
  (`v1.0.<build number>`, with `OfficeToDo.apk` attached).
- The app checks for a newer release when you open it (at most every 6 hours), or right away from **Settings → Check for updates**.
- When a new version exists, tap **Update now**: the app downloads it, verifies its SHA-256 checksum,
  and opens the Android installer. Tap **Update**. Your tasks and alarms are kept.
- The first time, Android asks you to allow **Install unknown apps** for Office To-Do.
- Android always asks you to confirm an update. Silent installs are only possible for Play Store or device-owner apps.

Builds are signed with the key in `app/debug.keystore`, so a newer APK installs over the old one
and your tasks are kept.

## Build locally
Open the folder in Android Studio and press Run, or with the Android SDK, JDK 17+ and Gradle 8.14 installed:
```
gradle assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Minimum Android version: 8.0 (API 26). Target: Android 15 (API 35).
