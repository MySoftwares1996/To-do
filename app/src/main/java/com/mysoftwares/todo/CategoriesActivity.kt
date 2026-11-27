package com.mysoftwares.todo

import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

/** Settings → Categories: rename, recolour, add or delete task categories. */
class CategoriesActivity : BaseActivity() {
    private lateinit var list: IosList

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        title = "Categories"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        list = IosList(this, findViewById(R.id.content))
        render()
    }

    private fun render() {
        list.clear()
        list.label("Categories")
        val g = list.group()
        val cats = Categories.all(this)
        cats.forEachIndexed { i, c -> g.row(c.name, dot = c.color) { edit(i) } }
        if (cats.isEmpty()) g.row("No categories", centered = true)
        list.footer("Tap a category to rename, recolour or delete it. Tasks keep their category name if you delete it.")
    }

    private fun edit(index: Int) {
        val cats = Categories.all(this).toMutableList()
        val c = cats[index]
        AlertDialog.Builder(this)
            .setTitle(c.name)
            .setItems(arrayOf("Rename", "Change colour", "Delete")) { _, which ->
                when (which) {
                    0 -> askName("Rename", c.name) { name ->
                        renameInTasks(c.name, name)
                        cats[index] = c.copy(name = name); Categories.save(this, cats); render()
                    }
                    1 -> pickColour { color -> cats[index] = c.copy(color = color); Categories.save(this, cats); render() }
                    2 -> { cats.removeAt(index); Categories.save(this, cats); render() }
                }
            }
            .show()
    }

    /** Keep tasks linked after a rename. */
    private fun renameInTasks(old: String, new: String) {
        val db = TaskDb.get(this)
        db.all().filter { it.category == old }.forEach { db.save(it.copy(category = new)) }
    }

    private fun add() {
        askName("New category", "") { name ->
            pickColour { color ->
                Categories.save(this, Categories.all(this) + Categories.Category(name, color)); render()
            }
        }
    }

    private fun askName(titleText: String, current: String, onDone: (String) -> Unit) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(current); setSelection(current.length)
            hint = "Name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setTextColor(ContextCompat.getColor(context, R.color.ios_label))
        }
        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setView(FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) })
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && Categories.all(this).none { it.name.equals(name, true) && it.name != current }) onDone(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickColour(onPick: (Int) -> Unit) {
        val names = arrayOf("Blue", "Red", "Green", "Purple", "Orange", "Teal", "Pink", "Grey")
        AlertDialog.Builder(this)
            .setTitle("Colour")
            .setItems(names) { _, i -> onPick(Categories.PALETTE[i]) }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Add").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) { add(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
