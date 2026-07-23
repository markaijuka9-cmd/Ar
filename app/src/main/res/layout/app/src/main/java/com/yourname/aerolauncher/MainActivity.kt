// File 6: app/src/main/java/com/yourname/aerolauncher/MainActivity.kt
package com.yourname.aerolauncher

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStreamReader

// ========== Data class for an app ==========
data class AppInfo(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Drawable? // system icon
)

// ========== Device admin receiver for screen lock ==========
class LockScreenAdminReceiver : android.app.admin.DeviceAdminReceiver()

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var homeRecyclerView: RecyclerView
    private lateinit var drawerContainer: FrameLayout
    private lateinit var glassPanel: LinearLayout
    private lateinit var searchEditText: EditText
    private lateinit var drawerRecyclerView: RecyclerView

    private lateinit var gestureDetector: GestureDetector
    private var isDrawerOpen = false
    private var drawerAnimating = false

    // App lists and adapters
    private val allApps = mutableListOf<AppInfo>()
    private val favoriteApps = mutableListOf<AppInfo>()
    private var filteredApps = mutableListOf<AppInfo>()

    private lateinit var favoritesAdapter: FavoritesAdapter
    private lateinit var appListAdapter: AppListAdapter

    // Icon pack
    private var iconPackPackage: String? = null
    private var iconPackResources: Resources? = null
    private var componentToDrawableName: Map<String, String> = emptyMap()

    // SharedPreferences
    private lateinit var prefs: SharedPreferences

    // For blur
    private var blurRadius = 15f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("aero_prefs", Context.MODE_PRIVATE)
        iconPackPackage = prefs.getString("icon_pack", null)
        if (iconPackPackage != null) loadIconPackResources()

        // Bind views
        rootLayout = findViewById(R.id.rootLayout)
        homeRecyclerView = findViewById(R.id.homeRecyclerView)
        drawerContainer = findViewById(R.id.drawerContainer)
        glassPanel = findViewById(R.id.glassPanel)
        searchEditText = findViewById(R.id.searchEditText)
        drawerRecyclerView = findViewById(R.id.drawerRecyclerView)

        // Style the glass panel background
        val glassBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 32f
            setColor(Color.parseColor("#CCFFFFFF")) // semi-transparent white
        }
        glassPanel.background = glassBg

        // Style search bar glass background
        val searchBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 48f
            setColor(Color.parseColor("#33FFFFFF"))
            setStroke(2, Color.parseColor("#55FFFFFF"))
        }
        searchEditText.background = searchBg

        // Set up home grid (4 columns, 5 rows max)
        homeRecyclerView.layoutManager = GridLayoutManager(this, 4)
        favoritesAdapter = FavoritesAdapter()
        homeRecyclerView.adapter = favoritesAdapter

        // Drawer grid (4 columns)
        drawerRecyclerView.layoutManager = GridLayoutManager(this, 4)
        appListAdapter = AppListAdapter()
        drawerRecyclerView.adapter = appListAdapter

        // Gesture detection
        gestureDetector = GestureDetector(this, GestureListener())
        rootLayout.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        // Drawer dim overlay: tap to close
        findViewById<View>(R.id.dimBackground).setOnClickListener {
            closeDrawer()
        }

        // Search filter
        searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                filterApps(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        loadApps()
        loadFavorites()
    }

    override fun onResume() {
        super.onResume()
        // Refresh apps in case of install/uninstall changes
        loadApps()
        loadFavorites()
    }

    // ---------- App loading ----------
    private fun loadApps() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, 0)
        allApps.clear()
        for (ri in resolved) {
            val ai = ri.activityInfo
            val pkg = ai.packageName
            val cls = ai.name
            // Skip our own launcher (optional)
            if (pkg == packageName) continue
            val label = ri.loadLabel(pm).toString()
            val icon: Drawable? = ri.loadIcon(pm)
            allApps.add(AppInfo(pkg, cls, label, icon))
        }
        allApps.sortBy { it.label.lowercase() }
        filterApps(searchEditText.text.toString())
    }

    private fun filterApps(query: String) {
        filteredApps = if (query.isBlank()) {
            allApps.toMutableList()
        } else {
            val q = query.lowercase()
            allApps.filter { it.label.lowercase().contains(q) }.toMutableList()
        }
        appListAdapter.notifyDataSetChanged()
    }

    // ---------- Favorites ----------
    private fun loadFavorites() {
        val comps = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        favoriteApps.clear()
        for (comp in comps) {
            val info = allApps.find { "${it.packageName}/${it.activityName}" == comp }
            if (info != null) favoriteApps.add(info)
        }
        favoritesAdapter.notifyDataSetChanged()
    }

    private fun saveFavorites() {
        val set = favoriteApps.map { "${it.packageName}/${it.activityName}" }.toSet()
        prefs.edit().putStringSet("favorites", set).apply()
    }

    // ---------- Drawer open/close ----------
    private fun openDrawer() {
        if (isDrawerOpen || drawerAnimating) return
        drawerAnimating = true

        // Capture and blur home screen for glass effect
        val blurBitmap = captureAndBlurRoot()
        if (blurBitmap != null) {
            // Set blurred bitmap as background of glass panel
            glassPanel.background = BitmapDrawable(resources, blurBitmap)
        } else {
            // fallback
            val fallback = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 32f
                setColor(Color.parseColor("#CCFFFFFF"))
            }
            glassPanel.background = fallback
        }

        // Position offscreen and show
        drawerContainer.translationY = rootLayout.height.toFloat()
        drawerContainer.visibility = View.VISIBLE
        drawerContainer.animate()
            .translationY(0f)
            .setDuration(250)
            .withStartAction { /* nothing */ }
            .withEndAction {
                isDrawerOpen = true
                drawerAnimating = false
            }
            .start()
    }

    private fun closeDrawer() {
        if (!isDrawerOpen || drawerAnimating) return
        drawerAnimating = true
        drawerContainer.animate()
            .translationY(rootLayout.height.toFloat())
            .setDuration(250)
            .withStartAction { /* nothing */ }
            .withEndAction {
                drawerContainer.visibility = View.GONE
                isDrawerOpen = false
                drawerAnimating = false
                // Clear search
                searchEditText.text.clear()
            }
            .start()
    }

    // ---------- Screenshot + blur ----------
    private fun captureAndBlurRoot(): Bitmap? {
        return try {
            val v = rootLayout
            v.isDrawingCacheEnabled = true
            v.buildDrawingCache()
            val bmp = v.drawingCache
            val copy = bmp?.copy(Bitmap.Config.ARGB_8888, false)
            v.isDrawingCacheEnabled = false
            v.destroyDrawingCache()
            if (copy != null) fastBlur(copy, blurRadius) else null
        } catch (e: Exception) {
            null
        }
    }

    // Simple fast blur (StackBlur algorithm derived from Quasimondo)
    private fun fastBlur(bitmap: Bitmap, radius: Float): Bitmap {
        if (radius < 1) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1
        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var p: Int
        var p1: Int
        var p2: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(Math.max(w, h))
        val dv = IntArray(256 * div)
        for (i in 0 until 256 * div) {
            dv[i] = i / div
        }

        var y = 0
        var index = 0

        // Blur horizontal
        for (x in 0 until w) {
            rsum = 0; gsum = 0; bsum = 0
            for (i in -radius.toInt()..radius.toInt()) {
                p = pix[yi + Math.min(wm, Math.max(i, 0))]
                rsum += (p and 0xff0000) shr 16
                gsum += (p and 0x00ff00) shr 8
                bsum += p and 0x0000ff
            }
            for (y in 0 until h) {
                index = y * w
                r[index] = dv[rsum]
                g[index] = dv[gsum]
                b[index] = dv[bsum]

                if (y == 0) vmin[x] = Math.min(x + radius + 1, wm) else {
                    val pcurr = pix[index + vmin[x]]
                    val pprev = pix[index + Math.max(0, x - radius.toInt())]
                    rsum += ((pcurr and 0xff0000) shr 16) - ((pprev and 0xff0000) shr 16)
                    gsum += ((pcurr and 0x00ff00) shr 8) - ((pprev and 0x00ff00) shr 8)
                    bsum += (pcurr and 0x0000ff) - (pprev and 0x0000ff)
                }
            }
        }

        // Blur vertical
        for (x in 0 until w) {
            rsum = 0; gsum = 0; bsum = 0
            val yp = -radius.toInt() * w
            for (i in -radius.toInt()..radius.toInt()) {
                yi = Math.max(0, yp) + x
                rsum += r[yi]
                gsum += g[yi]
                bsum += b[yi]
            }
            var yiCounter = 0
            for (y in 0 until h) {
                val pout = index and 0xff shl 24 or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                pix[index] = pout

                if (x == 0) vmin[y] = Math.min(y + radius + 1, hm) * w else {
                    val pcurr = y + vmin[y]
                    val pprev = y + Math.max(0, y - radius.toInt())
                    rsum += r[pcurr] - r[pprev]
                    gsum += g[pcurr] - g[pprev]
                    bsum += b[pcurr] - b[pprev]
                    index += w
                }
            }
        }
        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }

    // ---------- Lock screen ----------
    private fun lockScreen() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, LockScreenAdminReceiver::class.java)
        if (dpm.isAdminActive(adminComponent)) {
            dpm.lockNow()
        } else {
            // Prompt user to enable device admin
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required to lock screen on double tap.")
            }
            startActivity(intent)
            Toast.makeText(this, "Enable device admin to lock screen", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Icon pack support ----------
    private fun loadIconPackResources() {
        val pkg = iconPackPackage ?: return
        try {
            iconPackResources = packageManager.getResourcesForApplication(pkg)
            parseIconPackFilter()
        } catch (e: Exception) {
            iconPackResources = null
            componentToDrawableName = emptyMap()
        }
    }

    private fun parseIconPackFilter() {
        val res = iconPackResources ?: return
        val map = mutableMapOf<String, String>()
        try {
            val assets = res.assets
            val inputStream = assets.open("appfilter.xml")
            val reader = InputStreamReader(inputStream)
            val factory = XmlPullParserFactory.newInstance()
            val parser: XmlPullParser = factory.newPullParser()
            parser.setInput(reader)
            var eventType = parser.eventType
            var currentComponent: String? = null
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "item") {
                            val component = parser.getAttributeValue(null, "component")
                            val drawable = parser.getAttributeValue(null, "drawable")
                            if (!component.isNullOrBlank() && !drawable.isNullOrBlank()) {
                                map[component] = drawable
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
            reader.close()
        } catch (_: Exception) {}
        componentToDrawableName = map
    }

    private fun getIconForApp(info: AppInfo): Drawable? {
        // Try icon pack first
        val pkg = iconPackPackage
        val res = iconPackResources
        if (pkg != null && res != null) {
            val component = "${info.packageName}/${info.activityName}"
            val drawableName = componentToDrawableName[component]
            if (!drawableName.isNullOrBlank()) {
                try {
                    val id = res.getIdentifier(drawableName, "drawable", pkg)
                    if (id != 0) {
                        return res.getDrawable(id, theme)
                    }
                } catch (_: Exception) {}
            }
        }
        // Fallback to system icon
        return info.icon
    }

    // ---------- Long press to add/remove favorites ----------
    private fun toggleFavorite(info: AppInfo) {
        val compKey = "${info.packageName}/${info.activityName}"
        val existing = favoriteApps.find { "${it.packageName}/${it.activityName}" == compKey }
        if (existing != null) {
            favoriteApps.remove(existing)
            Toast.makeText(this, "Removed from Favorites", Toast.LENGTH_SHORT).show()
        } else {
            if (favoriteApps.size >= 20) {
                Toast.makeText(this, "Favorites grid full (max 20)", Toast.LENGTH_SHORT).show()
                return
            }
            favoriteApps.add(info)
            Toast.makeText(this, "Added to Favorites", Toast.LENGTH_SHORT).show()
        }
        saveFavorites()
        favoritesAdapter.notifyDataSetChanged()
    }

    // ---------- Gesture Listener ----------
    inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        private val SWIPE_THRESHOLD = 100
        private val SWIPE_VELOCITY_THRESHOLD = 100

        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
        ): Boolean {
            if (e1 == null) return false
            val diffY = e2.y - e1.y
            val diffX = e2.x - e1.x
            if (Math.abs(diffY) > Math.abs(diffX)
                && Math.abs(diffY) > SWIPE_THRESHOLD
                && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD
            ) {
                if (diffY < 0 && !isDrawerOpen) {
                    openDrawer()
                    return true
                } else if (diffY > 0 && isDrawerOpen) {
                    closeDrawer()
                    return true
                }
            }
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            lockScreen()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            // Long press on empty area: offer icon pack picker
            if (!isDrawerOpen) {
                pickIconPack()
            }
        }
    }

    private fun pickIconPack() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory("com.anddoes.launcher.THEME")
        }
        val resolveList = packageManager.queryIntentActivities(intent, 0)
        if (resolveList.isEmpty()) {
            Toast.makeText(this, "No icon packs found", Toast.LENGTH_SHORT).show()
            return
        }
        val names = resolveList.map { it.loadLabel(packageManager).toString() }
        val packages = resolveList.map { it.activityInfo.packageName }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Select Icon Pack")
            .setItems(names.toTypedArray()) { _, which ->
                iconPackPackage = packages[which]
                prefs.edit().putString("icon_pack", iconPackPackage).apply()
                loadIconPackResources()
                // Refresh adapters
                loadApps()
                loadFavorites()
            }
            .create()
        dialog.show()
    }

    // ---------- Favorites Adapter ----------
    inner class FavoritesAdapter : RecyclerView.Adapter<FavoritesAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.icon)
            val label: TextView = view.findViewById(R.id.label)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = layoutInflater.inflate(R.layout.item_app, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = favoriteApps[position]
            holder.label.text = app.label
            // Use Glide to load icon (from icon pack or system)
            val drawable = getIconForApp(app)
            Glide.with(this@MainActivity)
                .load(drawable)
                .into(holder.icon)

            holder.itemView.setOnClickListener {
        
