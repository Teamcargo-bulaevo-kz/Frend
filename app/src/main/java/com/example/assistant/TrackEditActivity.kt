package com.example.assistant

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Подробная правка характеристик песни: настроения галочками, жанр, энергичность, темп, описание, папки, лайк. */
class TrackEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRACK_ID = "track_id"
        const val EXTRA_REVIEW = "review"
    }

    private var track: MusicTrack? = null
    private var liked = 0
    private val moodBoxes = LinkedHashMap<String, CheckBox>()
    private val folderBoxes = LinkedHashMap<String, CheckBox>()
    private val tempoButtons = LinkedHashMap<String, RadioButton>()
    private val genreKeys = MusicTags.genres.keys.toList()

    private lateinit var genreSpinner: Spinner
    private lateinit var genreLabel: EditText
    private lateinit var energySeek: SeekBar
    private lateinit var energyText: TextView
    private lateinit var descEdit: EditText
    private lateinit var newFolder: EditText
    private lateinit var likeBtn: MaterialButton
    private lateinit var dislikeBtn: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_track_edit)
        genreSpinner = findViewById(R.id.genreSpinner)
        genreLabel = findViewById(R.id.genreLabel)
        energySeek = findViewById(R.id.energySeek)
        energyText = findViewById(R.id.energyText)
        descEdit = findViewById(R.id.descEdit)
        newFolder = findViewById(R.id.newFolder)
        likeBtn = findViewById(R.id.likeBtn)
        dislikeBtn = findViewById(R.id.dislikeBtn)

        val id = intent.getLongExtra(EXTRA_TRACK_ID, -1L)
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { MusicLibrary.index(this@TrackEditActivity).tracks.firstOrNull { it.id == id } }
            if (found == null) {
                Toast.makeText(this@TrackEditActivity, "Песня не найдена", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            track = found
            build(found)
        }
    }

    private fun build(t: MusicTrack) {
        val tags = MusicTags.get(this, t)
        val tagged = MusicTags.isTagged(tags)
        findViewById<TextView>(R.id.editTitle).text = t.title
        findViewById<TextView>(R.id.editArtist).text = t.artist.ifBlank { "Неизвестный исполнитель" }
        findViewById<TextView>(R.id.editInfo).text = when {
            tags == null || !tagged -> "Ещё не размечена: выбери характеристики сам или дождись разметки"
            tags.edited -> "Настроено тобой"
            else -> "Определил ${if (tags.source == TrackTags.SOURCE_WEB) "по данным из интернета" else "ИИ"}, уверенность: ${MusicCatalog.confidenceLabel(tags.confidence).lowercase()}"
        }
        liked = tags?.liked ?: 0
        updateLikeButtons()
        likeBtn.setOnClickListener { liked = if (liked > 0) 0 else 1; updateLikeButtons() }
        dislikeBtn.setOnClickListener { liked = if (liked < 0) 0 else -1; updateLikeButtons() }

        val moodBox = findViewById<LinearLayout>(R.id.moodBox)
        for ((key, label) in MusicTags.moods) {
            val cb = CheckBox(this).apply {
                text = label
                setTextColor(getColor(R.color.music_text))
                isChecked = tagged && tags != null && key in tags.moods
            }
            moodBoxes[key] = cb
            moodBox.addView(cb)
        }

        genreSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, MusicTags.genres.values.toList())
        genreSpinner.setSelection(genreKeys.indexOf(tags?.genre ?: "other").coerceAtLeast(0))
        genreLabel.setText(tags?.genreLabel.orEmpty())

        energySeek.progress = ((tags?.energy ?: 3) - 1).coerceIn(0, 4)
        updateEnergyText()
        energySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = updateEnergyText()
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        val group = findViewById<RadioGroup>(R.id.tempoGroup)
        val currentTempo = tags?.tempo?.let { MusicTags.tempoKey(it) }
        val options = listOf("" to "Не указан") + MusicTags.tempos.toList()
        for ((key, label) in options) {
            val rb = RadioButton(this).apply {
                text = label
                setTextColor(getColor(R.color.music_text))
                id = View.generateViewId()
            }
            tempoButtons[key] = rb
            group.addView(rb)
        }
        tempoButtons[currentTempo ?: ""]?.isChecked = true

        descEdit.setText(tags?.description.orEmpty())

        val folderBox = findViewById<LinearLayout>(R.id.folderBox)
        val key = MusicTags.trackKey(t)
        for ((name, list) in PlaylistStore.load(this)) {
            val cb = CheckBox(this).apply {
                text = name
                setTextColor(getColor(R.color.music_text))
                isChecked = list.any { MusicTags.trackKey(it) == key }
            }
            folderBoxes[name] = cb
            folderBox.addView(cb)
        }

        findViewById<MaterialButton>(R.id.saveBtn).setOnClickListener { save(confirmOnly = false) }
        val confirm = findViewById<MaterialButton>(R.id.confirmBtn)
        if (intent.getBooleanExtra(EXTRA_REVIEW, false) && tagged) {
            confirm.visibility = View.VISIBLE
            confirm.setOnClickListener { save(confirmOnly = true) }
        }
    }

    private fun updateLikeButtons() {
        likeBtn.text = if (liked > 0) "♥ Нравится" else "♡ Нравится"
        dislikeBtn.text = if (liked < 0) "Не нравится ✓" else "Не нравится"
    }

    private fun updateEnergyText() {
        val labels = listOf("очень спокойно", "спокойно", "средне", "бодро", "очень энергично")
        energyText.text = "Энергичность: ${energySeek.progress + 1} из 5 (${labels[energySeek.progress]})"
    }

    private fun save(confirmOnly: Boolean) {
        val t = track ?: return
        val moods = moodBoxes.filterValues { it.isChecked }.keys.toList()
        if (moods.isEmpty()) {
            Toast.makeText(this, "Выбери хотя бы одно настроение", Toast.LENGTH_SHORT).show()
            return
        }
        val genre = genreKeys[genreSpinner.selectedItemPosition.coerceIn(0, genreKeys.lastIndex)]
        val tempoKey = tempoButtons.entries.firstOrNull { it.value.isChecked }?.key.orEmpty()
        val label = genreLabel.text.toString().trim()
        val energy = energySeek.progress + 1
        val description = descEdit.text.toString().trim()
        val likedValue = liked
        MusicTags.update(this, t) { old ->
            old.copy(
                moods = moods.toSet(), mood = moods.first(), genre = genre, genreLabel = label,
                energy = energy, tempo = if (tempoKey.isEmpty()) "" else MusicTags.tempos[tempoKey].orEmpty(),
                description = description, liked = likedValue
            )
        }
        saveFolders(t)
        Toast.makeText(this, if (confirmOnly) "Подтверждено" else "Сохранено", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun saveFolders(t: MusicTrack) {
        val lists = PlaylistStore.load(this)
        val key = MusicTags.trackKey(t)
        var changed = false
        for ((name, cb) in folderBoxes) {
            val list = lists[name] ?: continue
            val has = list.any { MusicTags.trackKey(it) == key }
            if (cb.isChecked && !has) { list.add(t); changed = true }
            if (!cb.isChecked && has) { list.removeAll { MusicTags.trackKey(it) == key }; changed = true }
        }
        val fresh = newFolder.text.toString().trim()
        if (fresh.isNotEmpty()) {
            val name = PlaylistStore.findName(lists, fresh) ?: fresh
            val list = lists.getOrPut(name) { mutableListOf() }
            if (list.none { MusicTags.trackKey(it) == key }) list.add(t)
            changed = true
        }
        if (changed) {
            PlaylistStore.save(this, lists)
            MusicCatalog.scheduleExport(this)
        }
    }
}
