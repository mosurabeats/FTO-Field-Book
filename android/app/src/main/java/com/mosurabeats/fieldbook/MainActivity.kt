package com.mosurabeats.fieldbook

import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import java.io.File

object App {
    lateinit var store: Store
    lateinit var lock: LockManager
    fun init(ctx: Context) {
        if (::store.isInitialized) return
        Prefs.load(ctx); SecureStore.init(ctx); Ref.load(ctx)
        store = Store(ctx.applicationContext); lock = LockManager()
    }
}

val LocalNav = staticCompositionLocalOf<NavController> { error("no nav") }
val LocalAct = staticCompositionLocalOf<FragmentActivity> { error("no activity") }

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(this)
        enableEdgeToEdge(SystemBarStyle.dark(AColor.TRANSPARENT), SystemBarStyle.dark(AColor.TRANSPARENT))
        setContent { Root(this) }
    }

    override fun onStart() { super.onStart(); if (App.lock.locked) App.lock.unlock(this) }
    override fun onStop() { super.onStop(); App.store.saveNow(); App.lock.appWentToBackground() }

    fun systemBars(dark: Boolean) {
        enableEdgeToEdge(SystemBarStyle.dark(AColor.TRANSPARENT),
            if (dark) SystemBarStyle.dark(AColor.TRANSPARENT) else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT))
    }
}

/** Opens the share sheet for a file in the app's export cache. */
fun shareFile(ctx: Context, f: File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, f.name))
}

fun shareText(ctx: Context, text: String, subject: String = "FieldBook") {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, subject)
    ctx.startActivity(Intent.createChooser(i, subject))
}

fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("FieldBook", text))
}

@Composable
fun Root(act: MainActivity) {
    val p = Palette.named(Prefs.theme, isSystemInDarkTheme())
    LaunchedEffect(p.dark) { act.systemBars(p.dark) }
    var toast by remember { mutableStateOf<String?>(null) }
    val scheme = if (p.dark) darkColorScheme(primary = p.hi, onPrimary = p.hitx, surface = p.p1, onSurface = p.tx, background = p.bg, onBackground = p.tx,
        surfaceContainerHigh = p.p1, surfaceContainerHighest = p.p2, onSurfaceVariant = p.mu, secondaryContainer = p.p3, outline = p.line)
    else lightColorScheme(primary = p.hi, onPrimary = p.hitx, surface = p.p1, onSurface = p.tx, background = p.bg, onBackground = p.tx,
        surfaceContainerHigh = p.p1, surfaceContainerHighest = p.p2, onSurfaceVariant = p.mu, secondaryContainer = p.p3, outline = p.line)
    val nav = rememberNavController()
    CompositionLocalProvider(LocalPal provides p, LocalToast provides { toast = it }, LocalNav provides nav, LocalAct provides act) {
        MaterialTheme(colorScheme = scheme) {
            Box(Modifier.fillMaxSize().background(p.bg)) {
                NavHost(nav, "home") {
                    composable("home") { Home() }
                    composable("dor/{id}") { DOREditorScreen(it.arguments?.getString("id") ?: "") }
                    composable("rate/{id}/{n}") { RatingScreen(it.arguments?.getString("id") ?: "", it.arguments?.getString("n")?.toIntOrNull() ?: 1) }
                    composable("export/{id}") { ExportScreen(it.arguments?.getString("id") ?: "") }
                    composable("rtp/{id}") { RTPEditorScreen(it.arguments?.getString("id") ?: "") }
                    composable("note/{id}") { NoteEditScreen(it.arguments?.getString("id") ?: "") }
                    composable("trainee/{id}") { TraineeEditorScreen(it.arguments?.getString("id")?.takeIf { s -> s != "new" }) }
                }
                ToastHost(toast) { toast = null }
                if (Prefs.lockEnabled && App.lock.locked) LockScreen(act)
            }
        }
    }
}

@Composable
private fun BoxScope.ToastHost(text: String?, clear: () -> Unit) {
    LaunchedEffect(text) { if (text != null) { delay(2200); clear() } }
    AnimatedVisibility(text != null, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp), enter = fadeIn(), exit = fadeOut()) {
        Text(text ?: "", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(CircleShape).background(hex("17181B", .95f)).padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

private data class TabDef(val label: String, val icon: ImageVector)
private val tabs = listOf(TabDef("Notes", Icons.Default.EditNote), TabDef("DORs", Icons.Default.Description),
    TabDef("Tasks", Icons.Default.Checklist), TabDef("Progress", Icons.AutoMirrored.Filled.ShowChart), TabDef("Trainee", Icons.Default.AccountCircle))

@Composable
fun Home() {
    val p = pal()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(tab != 0) { tab = 0 }
    Scaffold(
        containerColor = p.bg,
        topBar = { HeaderBar() },
        bottomBar = {
            NavigationBar(containerColor = p.p1, tonalElevation = 0.dp) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(t.icon, t.label) }, label = { Text(t.label, fontSize = 11.5.sp) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = p.hi2, selectedTextColor = p.hi2, indicatorColor = p.hi.copy(alpha = .14f),
                            unselectedIconColor = p.mu, unselectedTextColor = p.mu))
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad).imePadding()) {
            when (tab) {
                0 -> NotesTab { tab = 1 }
                1 -> DORsTab()
                2 -> TasksTab()
                3 -> ProgressTab()
                else -> SettingsTab()
            }
        }
    }
}

@Composable
fun HeaderBar() {
    val p = pal(); val store = App.store
    val t = store.trainee
    val sub = if (t == null) "Field training · FTEP" else {
        val n = store.dors(t.id).count { it.phase == t.phase }
        "Level ${t.level} · ${t.phase} · $n ride${if (n == 1) "" else "s"} logged"
    }
    Row(Modifier.fillMaxWidth().background(p.hdr).statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AppLogo(32.dp)
        Column {
            Text(t?.name ?: "FTO FieldBook", color = p.hdrtx, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
            Text(sub, color = p.hdrmu, fontSize = 11.5.sp, maxLines = 1)
        }
    }
}

/** Full-screen page with the black-deck top bar and a back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(title: String, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val p = pal(); val nav = LocalNav.current
    Scaffold(
        containerColor = p.bg,
        topBar = {
            TopAppBar(title = { Text(title, color = p.hdrtx, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = p.hdrtx) } },
                actions = actions, colors = TopAppBarDefaults.topAppBarColors(containerColor = p.hdr))
        },
    ) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Scrolling tab body. */
@Composable
fun TabColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun LockScreen(act: FragmentActivity) {
    val p = pal()
    LaunchedEffect(Unit) { App.lock.unlock(act) }
    Column(Modifier.fillMaxSize().background(p.bg).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AppLogo(96.dp)
        Spacer(Modifier.height(22.dp))
        Text("FieldBook is locked", color = p.tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(22.dp))
        Box(Modifier.widthIn(max = 280.dp)) { PrimaryButton("Unlock") { App.lock.unlock(act) } }
        App.lock.lastError?.let { Spacer(Modifier.height(10.dp)); Text(it, color = p.mu, fontSize = 13.sp) }
    }
}

/** Shown on tabs that need a trainee when none exists yet. */
@Composable
fun Onboarding() {
    val nav = LocalNav.current
    TabColumn {
        Card("Get started") {
            Text("Add your trainee. Log observations on calls, and each shift's DOR fills itself in from those notes, using the 40 VCJTC Level II/III SEGs. When you're done it exports as your FTEP-1 form.",
                color = pal().tx, fontSize = 15.sp)
            PrimaryButton("Add trainee") { nav.navigate("trainee/new") }
        }
    }
}
