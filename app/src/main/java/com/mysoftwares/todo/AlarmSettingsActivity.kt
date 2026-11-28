package com.mysoftwares.todo

import android.content.Intent
import android.media.RingtoneManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog

/** Settings → Alarms & snooze: sound, ring length, snooze buttons, tomorrow time, important repeat. */
class AlarmSettingsActivity : BaseActivity() {
    private lateinit var list: IosList

    private val soundPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = r.data?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            AlarmPrefs.setSound(this, uri)
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        setTitle(R.string.set_alarms_snooze)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        list = IosList(this, findViewById(R.id.content))
        render()
    }

    private fun timeText(minutes: Int): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(
        java.util.Date(java.time.LocalDate.now().atTime(minutes / 60, minutes % 60)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
    )

    private fun render() {
        list.clear()
        list.label(getString(R.string.set_sound))
        val sound = list.group()
        sound.row(getString(R.string.msg_alarm_sound), (if (AlarmPrefs.vibrateOnly(this)) getString(R.string.set_off) else soundName()) + " ›") { pickSound() }
        sound.switchRow(getString(R.string.msg_vibrate_only), null, AlarmPrefs.vibrateOnly(this)) { on ->
            AlarmPrefs.setVibrateOnly(this, on); render()
        }
        sound.row(getString(R.string.msg_ring_for), "${AlarmPrefs.ringMinutes(this)} min ›") {
            pickMinutes(getString(R.string.msg_ring_for), AlarmPrefs.RING_CHOICES, AlarmPrefs.ringMinutes(this)) {
                AlarmPrefs.setRingMinutes(this, it); render()
            }
        }

        list.label(getString(R.string.msg_snooze_buttons))
        val snooze = list.group()
        snooze.row(getString(R.string.set_snooze_buttons_long),
            "${AlarmPrefs.label(AlarmPrefs.snooze1(this))} · ${AlarmPrefs.label(AlarmPrefs.snooze2(this))} ›") { chooseSnooze() }
        snooze.row(getString(R.string.set_morning), timeText(AlarmPrefs.morningMinutes(this)) + " ›") { pickMorning() }

        list.label(getString(R.string.set_important_tasks))
        list.group().row(getString(R.string.set_remind_again_every), "${AlarmPrefs.nagMinutes(this)} min ›") {
            pickMinutes(getString(R.string.set_remind_again_every), AlarmPrefs.NAG_CHOICES, AlarmPrefs.nagMinutes(this)) {
                AlarmPrefs.setNagMinutes(this, it); render()
            }
        }
        list.label(getString(R.string.summary_section))
        val summary = list.group()
        summary.switchRow(getString(R.string.summary_switch), getString(R.string.summary_hint), AlarmPrefs.summaryOn(this)) { on ->
            AlarmPrefs.setSummaryOn(this, on); AlarmScheduler.scheduleSummary(this); render()
        }
        if (AlarmPrefs.summaryOn(this)) summary.row(getString(R.string.summary_time), timeText(AlarmPrefs.summaryMinutes(this)) + " ›") {
            val m = AlarmPrefs.summaryMinutes(this)
            val picker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
                .setTimeFormat(
                    if (android.text.format.DateFormat.is24HourFormat(this)) com.google.android.material.timepicker.TimeFormat.CLOCK_24H
                    else com.google.android.material.timepicker.TimeFormat.CLOCK_12H
                )
                .setHour(m / 60).setMinute(m % 60)
                .setTitleText(getString(R.string.summary_time))
                .build()
            picker.addOnPositiveButtonClickListener {
                AlarmPrefs.setSummaryMinutes(this, picker.hour * 60 + picker.minute)
                AlarmScheduler.scheduleSummary(this); render()
            }
            picker.show(supportFragmentManager, "summary")
        }

        list.footer(getString(R.string.set_alarm_footer))
    }

    private fun pickSound() {
        soundPicker.launch(
            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.msg_alarm_sound))
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, AlarmPrefs.sound(this) ?: Notifications.alarmSound())
        )
    }

    private fun soundName(): String {
        val uri = AlarmPrefs.sound(this) ?: return getString(R.string.set_default)
        return runCatching { RingtoneManager.getRingtone(this, uri)?.getTitle(this) }.getOrNull() ?: getString(R.string.set_custom)
    }

    private fun pickMinutes(title: String, choices: List<Int>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(choices.map { AlarmPrefs.label(it) }.toTypedArray(), choices.indexOf(current)) { d, i ->
                onPick(choices[i]); d.dismiss()
            }
            .show()
    }

    /** Time used by "Snooze until tomorrow" on the alarm screen. */
    private fun pickMorning() {
        val m = AlarmPrefs.morningMinutes(this)
        val picker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
            .setTimeFormat(
                if (android.text.format.DateFormat.is24HourFormat(this)) com.google.android.material.timepicker.TimeFormat.CLOCK_24H
                else com.google.android.material.timepicker.TimeFormat.CLOCK_12H
            )
            .setHour(m / 60).setMinute(m % 60)
            .setTitleText(getString(R.string.set_morning))
            .build()
        picker.addOnPositiveButtonClickListener {
            AlarmPrefs.setMorningMinutes(this, picker.hour * 60 + picker.minute); render()
        }
        picker.show(supportFragmentManager, "morning")
    }

    /** Pick the two quick snooze buttons shown on the alarm screen. */
    private fun chooseSnooze() {
        val choices = AlarmPrefs.SNOOZE_CHOICES
        pickMinutes(getString(R.string.msg_first_snooze_button), choices, AlarmPrefs.snooze1(this)) { first ->
            pickMinutes(getString(R.string.msg_second_snooze_button), choices.filter { it != first }, AlarmPrefs.snooze2(this)) { second ->
                AlarmPrefs.setSnooze(this, minOf(first, second), maxOf(first, second))
                render()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
