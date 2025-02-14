/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright © 2020 Skyline Team and Contributors (https://github.com/skyline-emu/)
 */

package emu.skyline

import android.content.Intent
import android.util.TypedValue
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.res.use
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import emu.skyline.adapter.*
import emu.skyline.di.getSettings
import emu.skyline.data.AppItem
import emu.skyline.data.BaseAppItem
import emu.skyline.data.AppItemTag
import emu.skyline.databinding.MainActivityBinding
import emu.skyline.loader.AppEntry
import emu.skyline.loader.RomType
import emu.skyline.loader.LoaderResult
import emu.skyline.provider.DocumentsProvider
import emu.skyline.settings.AppSettings
import emu.skyline.settings.EmulationSettings
import emu.skyline.settings.SettingsActivity
import emu.skyline.utils.GpuDriverHelper
import emu.skyline.utils.SearchLocationHelper
import emu.skyline.utils.WindowInsetsHelper
import emu.skyline.SkylineApplication
import java.util.Collections
import javax.inject.Inject
import kotlin.math.ceil
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val binding by lazy { MainActivityBinding.inflate(layoutInflater) }

    @Inject
    lateinit var appSettings : AppSettings

    private val adapter = GenericAdapter()

    private val layoutType get() = LayoutType.values()[appSettings.layoutType]

    private val viewModel by viewModels<MainViewModel>()

    private var appEntries : List<AppEntry>? = null

    enum class SortingOrder {
        AlphabeticalAsc,
        AlphabeticalDesc
    }

    private var refreshIconVisible = false
        set(visible) {
            field = visible
            binding.refreshIcon.apply {
                if (visible != isVisible) {
                    binding.refreshIcon.alpha = if (visible) 0f else 1f
                    animate().alpha(if (visible) 1f else 0f).withStartAction { isVisible = true }.withEndAction { isInvisible = !visible }.apply { duration = 500 }.start()
                }
            }
        }

    private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        it?.let { uri ->
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            SearchLocationHelper.addLocation(this, uri)

            loadRoms(false)
        }
    }

    private val settingsCallback = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (appSettings.refreshRequired) loadRoms(false)
    }

    private fun BaseAppItem.toViewItem() = AppViewItem(layoutType, this, ::selectStartGame, ::selectShowGameDialog)

    override fun onCreate(savedInstanceState : Bundle?) {
        // Need to create new instance of settings, dependency injection happens
        AppCompatDelegate.setDefaultNightMode(
            when ((AppSettings(this).appTheme)) {
                0 -> AppCompatDelegate.MODE_NIGHT_NO
                1 -> AppCompatDelegate.MODE_NIGHT_YES
                2 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
            }
        )
        setTheme(if (getSettings().useMaterialYou) R.style.AppTheme_MaterialYou else R.style.AppTheme)
        super.onCreate(savedInstanceState)

        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsHelper.applyToActivity(binding.root, binding.appList)

        PreferenceManager.setDefaultValues(this, R.xml.app_preferences, false)
        PreferenceManager.setDefaultValues(this, R.xml.emulation_preferences, false)

        binding.appList.setHasFixedSize(true)

        setupAppList()

        binding.swipeRefreshLayout.apply {
            setProgressBackgroundColorSchemeColor(obtainStyledAttributes(intArrayOf(MaterialR.attr.colorSurfaceVariant)).use { it.getColor(0, Color.BLACK) })
            setColorSchemeColors(obtainStyledAttributes(intArrayOf(MaterialR.attr.colorPrimary)).use { it.getColor(0, Color.WHITE) })
            post { setDistanceToTriggerSync(binding.swipeRefreshLayout.height / 3) }
            setOnRefreshListener { loadRoms(false) }
        }

        viewModel.stateData.observe(this, ::handleState)
        loadRoms(!appSettings.refreshRequired)

        binding.searchBar.apply {
            binding.logIcon.setOnClickListener {
                val file = DocumentFile.fromSingleUri(this@MainActivity, DocumentsContract.buildDocumentUri(DocumentsProvider.AUTHORITY, "${DocumentsProvider.ROOT_ID}/logs/emulation.log"))!!
                if (file.exists() && file.length() != 0L) {
                    val intent = Intent(Intent.ACTION_SEND)
                        .setDataAndType(file.uri, "text/plain")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(Intent.EXTRA_STREAM, file.uri)
                    startActivity(Intent.createChooser(intent, getString(R.string.log_share_prompt)))
                } else {
                    Snackbar.make(this@MainActivity.findViewById(android.R.id.content), getString(R.string.logs_not_found), Snackbar.LENGTH_SHORT).show()
                }
            }
            binding.settingsIcon.setOnClickListener { settingsCallback.launch(Intent(context, SettingsActivity::class.java)) }
            binding.refreshIcon.setOnClickListener { loadRoms(false) }
            addTextChangedListener(afterTextChanged = { editable ->
                editable?.let { text -> adapter.filter.filter(text.toString()) }
            })
        }

        window.decorView.findViewById<View>(android.R.id.content).viewTreeObserver.addOnTouchModeChangeListener { isInTouchMode ->
            refreshIconVisible = !isInTouchMode
        }

        binding.statusBarShade.setBackgroundColor(
            SkylineApplication.applyAlphaToColor(
                MaterialColors.getColor(
                    binding.root,
                    MaterialR.attr.colorSurface
                ),
                0.9f
            )
        )

        if (SkylineApplication.detectNavigationType(this) != SkylineApplication.NAV_TYPE_GESTURE) {
            binding.navigationBarShade.setBackgroundColor(
                SkylineApplication.applyAlphaToColor(
                    MaterialColors.getColor(
                        binding.root,
                        MaterialR.attr.colorSurface
                    ),
                    0.9f
                )
            )
        }
        
        // we collect the themeChanges and apply
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                SkylineApplication.themeChangeFlow.distinctUntilChanged().collect { themeId ->
                    recreate()
                }
            }
        }
        setInsets()
    }

    private fun setAppListDecoration() {
        binding.appList.apply {
            while (itemDecorationCount > 0) removeItemDecorationAt(0)
            when (layoutType) {
                LayoutType.List -> Unit

                LayoutType.Grid, LayoutType.GridCompact -> addItemDecoration(GridSpacingItemDecoration(resources.getDimensionPixelSize(R.dimen.grid_padding)))
            }
        }
    }

    /**
     * This layout manager handles situations where [onFocusSearchFailed] gets called, when possible we always want to focus on the item with the same span index
     */
    private inner class CustomLayoutManager(gridSpan : Int) : GridLayoutManager(this, gridSpan) {
        init {
            spanSizeLookup = object : SpanSizeLookup() {
                override fun getSpanSize(position : Int) = if (layoutType == LayoutType.List || adapter.currentItems[position].fullSpan) gridSpan else 1
            }
        }

        override fun onRequestChildFocus(parent : RecyclerView, state : RecyclerView.State, child : View, focused : View?) : Boolean {
            binding.appBarLayout.setExpanded(false)
            return super.onRequestChildFocus(parent, state, child, focused)
        }

        override fun onFocusSearchFailed(focused : View, focusDirection : Int, recycler : RecyclerView.Recycler, state : RecyclerView.State) : View? {
            val nextFocus = super.onFocusSearchFailed(focused, focusDirection, recycler, state)
            when (focusDirection) {
                View.FOCUS_DOWN -> {
                    return null
                }

                View.FOCUS_UP -> {
                    if (nextFocus?.isFocusable != true) {
                        binding.searchBar.requestFocus()
                        binding.appBarLayout.setExpanded(true)
                        binding.appList.smoothScrollToPosition(0)
                        return null
                    }
                }
            }
            return nextFocus
        }
    }

    private fun setupAppList() {
        binding.appList.adapter = adapter

        val itemWidth = 225
        val metrics = resources.displayMetrics
        val gridSpan = ceil((metrics.widthPixels / metrics.density) / itemWidth).toInt()

        binding.appList.layoutManager = CustomLayoutManager(gridSpan)
        setAppListDecoration()

        if (appSettings.searchLocation.isEmpty()) documentPicker.launch(null)
    }

    private fun getAppItems() = mutableListOf<AppViewItem>().apply {
        appEntries?.let { entries ->
            sortGameList(entries.toList()).forEach { entry ->
                val updates : List<BaseAppItem> = entries.filter { it.romType == RomType.Update && it.parentTitleId == entry.titleId }.map { BaseAppItem(it, true) }
                val dlcs : List<BaseAppItem> = entries.filter { it.romType == RomType.DLC && it.parentTitleId == entry.titleId }.map { BaseAppItem(it, true) }
                add(AppItem(entry, updates, dlcs).toViewItem())
            }
        }
    }

    private fun sortGameList(gameList : List<AppEntry>) : List<AppEntry> {
        val sortedApps : MutableList<AppEntry> = mutableListOf()
        gameList.forEach { entry ->
            if (validateAppEntry(entry))
                sortedApps.add(entry)
        }
        when (appSettings.sortAppsBy) {
            SortingOrder.AlphabeticalAsc.ordinal -> sortedApps.sortBy { it.name }
            SortingOrder.AlphabeticalDesc.ordinal -> sortedApps.sortByDescending { it.name }
        }
        return sortedApps
    }

    private fun validateAppEntry(entry : AppEntry) : Boolean {
        // Unknown ROMs are shown because NROs have this type
        return !appSettings.filterInvalidFiles || entry.loaderResult != LoaderResult.ParsingError && (entry.romType == RomType.Base || entry.romType == RomType.Unknown)
    }

    private fun handleState(state : MainState) = when (state) {
        MainState.Loading -> {
            binding.refreshIcon.apply { animate().rotation(rotation - 180f) }
            binding.swipeRefreshLayout.isRefreshing = true
        }

        is MainState.Loaded -> {
            binding.swipeRefreshLayout.isRefreshing = false

            appEntries = state.items
            populateAdapter()
        }

        is MainState.Error -> Snackbar.make(findViewById(android.R.id.content), getString(R.string.error) + ": ${state.ex.localizedMessage}", Snackbar.LENGTH_SHORT).show()
    }

    private fun selectStartGame(appItem : BaseAppItem) {
        if (binding.swipeRefreshLayout.isRefreshing) return

        if (appSettings.selectAction) {
            AppDialog.newInstance(appItem).show(supportFragmentManager, "game")
        } else if (appItem.loaderResult == LoaderResult.Success) {
            startActivity(Intent(this, EmulationActivity::class.java).apply {
                putExtra(AppItemTag, appItem)
                putExtra(EmulationActivity.ReturnToMainTag, true)
            })
        }
    }

    private fun selectShowGameDialog(appItem : BaseAppItem) {
        if (binding.swipeRefreshLayout.isRefreshing) return

        AppDialog.newInstance(appItem).show(supportFragmentManager, "game")
    }

    private fun loadRoms(loadFromFile : Boolean) {
        if (!loadFromFile) {
            binding.romPlaceholder.isVisible = true
            binding.romPlaceholder.text = getString(R.string.searching_roms)
        }
        viewModel.loadRoms(this, loadFromFile, SearchLocationHelper.getSearchLocations(this), EmulationSettings.global.systemLanguage)
        appSettings.refreshRequired = false
    }

    private fun populateAdapter() {
        val items = getAppItems()
        if (items.isEmpty())
            binding.romPlaceholder.text = getString(R.string.no_rom)
        else
            binding.romPlaceholder.isVisible = false
        adapter.setItems(items)
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            binding.searchBar.apply {
                val inputMethodManager = context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                if (!inputMethodManager.hideSoftInputFromWindow(windowToken, 0)) {
                    text = ""
                    clearFocus()
                }
            }
            isEnabled = binding.searchBar.hasFocus() || binding.searchBar.text.isNotEmpty()
        }
    }

    override fun onStart() {
        super.onStart()

        binding.searchBar.addTextChangedListener { text ->
            if (!onBackPressedCallback.isEnabled && !text.isNullOrEmpty()) {
                onBackPressedCallback.isEnabled = true
            }
        }

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
        onBackPressedCallback.isEnabled = binding.searchBar.hasFocus() || binding.searchBar.text.isNotEmpty()
    }

    override fun onResume() {
        super.onResume()

        // Try to return to normal GPU clocks upon resuming back to main activity, to avoid GPU being stuck at max clocks after a crash
        GpuDriverHelper.forceMaxGpuClocks(false)

        var layoutTypeChanged = false
        for (appViewItem in adapter.allItems.filterIsInstance(AppViewItem::class.java)) {
            if (layoutType != appViewItem.layoutType) {
                appViewItem.layoutType = layoutType
                layoutTypeChanged = true
            } else {
                break
            }
        }

        if (layoutTypeChanged) {
            setAppListDecoration()
            adapter.notifyItemRangeChanged(0, adapter.currentItems.size)
        }

        viewModel.checkRomHash(SearchLocationHelper.getSearchLocations(this), EmulationSettings.global.systemLanguage)
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.root
        ) { _: View, windowInsets: WindowInsetsCompat ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val mlpStatusShade = binding.statusBarShade.layoutParams as MarginLayoutParams
            mlpStatusShade.height = insets.top
            binding.statusBarShade.layoutParams = mlpStatusShade

            val mlpNavShade = binding.navigationBarShade.layoutParams as MarginLayoutParams
            mlpNavShade.height = insets.bottom
            binding.navigationBarShade.layoutParams = mlpNavShade

            windowInsets
        }
}
