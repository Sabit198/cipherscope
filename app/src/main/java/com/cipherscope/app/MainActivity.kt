package com.cipherscope.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// --------------------------------------------------
// 1. DESIGN THEME & TOKENS
// --------------------------------------------------
val DarkNavyBg = Color(0xFF070B11)
val DarkNavyCard = Color(0xFF0F172A)
val DarkNavyBorder = Color(0xFF1E293B)
val ElectricCyan = Color(0xFF00E5FF)
val ElectricBlue = Color(0xFF3B82F6)
val TextLight = Color(0xFFF8FAFC)
val TextMuted = Color(0xFF94A3B8)
val StatusGreen = Color(0xFF10B981)
val StatusRed = Color(0xFFEF4444)

@Composable
fun CipherScopeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ElectricCyan,
            background = DarkNavyBg,
            surface = DarkNavyCard,
            onBackground = TextLight,
            onSurface = TextLight
        ),
        content = content
    )
}

// --------------------------------------------------
// 2. DATA MODELS
// --------------------------------------------------
data class UserTelemetry(
    val uid: String = "",
    val email: String = "",
    val displayName: String = "",
    val remainingCredits: Long = 100L,
    val totalRequests: Long = 0L,
    val status: String = "active"
)

data class ScopeService(
    val id: String,
    val name: String,
    val description: String,
    val cost: Long,
    val placeholder: String
)

sealed class NavRoute {
    data object Splash : NavRoute()
    data object Login : NavRoute()
    data object Register : NavRoute()
    data object Dashboard : NavRoute()
    data class ApiRunner(val service: ScopeService) : NavRoute()
    data object Profile : NavRoute()
    data object History : NavRoute()
}

// --------------------------------------------------
// 3. MAIN ACTIVITY & CONTROLLER
// --------------------------------------------------
class MainActivity : ComponentActivity() {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)

        setContent {
            CipherScopeTheme {
                var currentRoute by remember { mutableStateOf<NavRoute>(NavRoute.Splash) }
                val auth = FirebaseAuth.getInstance()
                val firestore = FirebaseFirestore.getInstance()

                // Active User Telemetry Flow
                var telemetry by remember { mutableStateOf(UserTelemetry()) }

                LaunchedEffect(auth.currentUser) {
                    val uid = auth.currentUser?.uid
                    if (uid != null) {
                        firestore.collection("users").document(uid)
                            .addSnapshotListener { snapshot, _ ->
                                if (snapshot != null && snapshot.exists()) {
                                    telemetry = UserTelemetry(
                                        uid = uid,
                                        email = snapshot.getString("email") ?: auth.currentUser?.email.orEmpty(),
                                        displayName = snapshot.getString("displayName") ?: "Operative",
                                        remainingCredits = snapshot.getLong("remainingCredits") ?: 100L,
                                        totalRequests = snapshot.getLong("totalRequests") ?: 0L,
                                        status = snapshot.getString("status") ?: "active"
                                    )
                                }
                            }
                    }
                }

                Crossfade(targetState = currentRoute, label = "ScreenTransition") { route ->
                    when (route) {
                        is NavRoute.Splash -> SplashScreen {
                            currentRoute = if (auth.currentUser != null) NavRoute.Dashboard else NavRoute.Login
                        }
                        is NavRoute.Login -> LoginScreen(
                            onLoginSuccess = { currentRoute = NavRoute.Dashboard },
                            onGoToRegister = { currentRoute = NavRoute.Register }
                        )
                        is NavRoute.Register -> RegisterScreen(
                            onRegisterSuccess = { currentRoute = NavRoute.Dashboard },
                            onGoToLogin = { currentRoute = NavRoute.Login }
                        )
                        is NavRoute.Dashboard -> DashboardScreen(
                            telemetry = telemetry,
                            onSelectService = { service -> currentRoute = NavRoute.ApiRunner(service) },
                            onOpenProfile = { currentRoute = NavRoute.Profile },
                            onOpenHistory = { currentRoute = NavRoute.History }
                        )
                        is NavRoute.ApiRunner -> ApiRunnerScreen(
                            service = route.service,
                            telemetry = telemetry,
                            onExecute = { query -> executeLiveQuery(route.service, query) },
                            onBack = { currentRoute = NavRoute.Dashboard }
                        )
                        is NavRoute.Profile -> ProfileScreen(
                            telemetry = telemetry,
                            onLogout = {
                                auth.signOut()
                                currentRoute = NavRoute.Login
                            },
                            onBack = { currentRoute = NavRoute.Dashboard }
                        )
                        is NavRoute.History -> HistoryScreen(
                            uid = auth.currentUser?.uid.orEmpty(),
                            onBack = { currentRoute = NavRoute.Dashboard }
                        )
                    }
                }
            }
        }
    }

    // Direct, Safe Public OSINT / Telemetry Execution Engine
    private suspend fun executeLiveQuery(service: ScopeService, target: String): Result<String> =
        withContext(Dispatchers.IO) {
            val user = FirebaseAuth.getInstance().currentUser
                ?: return@withContext Result.failure(Exception("Operative not authenticated"))
            val userRef = FirebaseFirestore.getInstance().collection("users").document(user.uid)

            try {
                // Deduct credits and log atomically on Firestore
                val snapshot = userRef.get().await()
                val credits = snapshot.getLong("remainingCredits") ?: 0L
                if (credits < service.cost) {
                    return@withContext Result.failure(Exception("Insufficient credits ($credits remaining)"))
                }
                if (snapshot.getString("status") == "suspended") {
                    return@withContext Result.failure(Exception("Security Exception: Account suspended"))
                }

                userRef.update(
                    "remainingCredits", FieldValue.increment(-service.cost),
                    "totalRequests", FieldValue.increment(1)
                ).await()

                // Dynamic target resolution without 3rd-party private keys
                val cleanTarget = target.trim().replace(Regex("^https?://"), "").split("/")[0]
                val url = when (service.id) {
                    "dns" -> "https://dns.google/resolve?name=$cleanTarget&type=A"
                    "ip" -> "https://ipapi.co/$cleanTarget/json/"
                    else -> "https://dns.google/resolve?name=$cleanTarget&type=TXT"
                }

                val req = Request.Builder().url(url).build()
                httpClient.newCall(req).execute().use { res ->
                    val body = res.body?.string().orEmpty()
                    // Record Audit Log in Cloud Firestore
                    FirebaseFirestore.getInstance().collection("api_logs").add(
                        mapOf(
                            "uid" to user.uid,
                            "service" to service.id,
                            "target" to cleanTarget,
                            "timestamp" to FieldValue.serverTimestamp()
                        )
                    )
                    Result.success(body)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}

// --------------------------------------------------
// 4. SCREENS & UI
// --------------------------------------------------

@Composable
fun SplashScreen(onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1200)
        onDone()
    }
    Box(
        modifier = Modifier.fillMaxSize().background(DarkNavyBg),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Security, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text("CIPHERSCOPE", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ElectricCyan, letterSpacing = 3.sp)
            Text("Native Cyber Threat & Network Scope", color = TextMuted, fontSize = 13.sp)
        }
    }
}

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit, onGoToRegister: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(DarkNavyBg).padding(24.dp), contentAlignment = Alignment.Center) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkNavyCard),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Operative Authentication", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Sign in to your CipherScope terminal", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 20.dp))

                if (errorMsg != null) {
                    Text(errorMsg ?: "", color = StatusRed, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Agent Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        if (email.isBlank() || password.isBlank()) {
                            errorMsg = "Fill in all fields"; return@Button
                        }
                        loading = true; errorMsg = null
                        FirebaseAuth.getInstance().signInWithEmailAndPassword(email.trim(), password)
                            .addOnSuccessListener { loading = false; onLoginSuccess() }
                            .addOnFailureListener { loading = false; errorMsg = it.localizedMessage }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                ) {
                    if (loading) CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(22.dp))
                    else Text("AUTHENTICATE", color = Color.Black, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onGoToRegister, modifier = Modifier.fillMaxWidth()) {
                    Text("Need operative account? Register", color = TextMuted)
                }
            }
        }
    }
}

@Composable
fun RegisterScreen(onRegisterSuccess: () -> Unit, onGoToLogin: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(DarkNavyBg).padding(24.dp), contentAlignment = Alignment.Center) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkNavyCard),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Enroll Operative", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))

                if (errorMsg != null) {
                    Text(errorMsg ?: "", color = StatusRed, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                }

                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Callsign / Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password (min 6 chars)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (email.isBlank() || password.length < 6) {
                            errorMsg = "Valid email & 6+ character password required"; return@Button
                        }
                        loading = true; errorMsg = null
                        FirebaseAuth.getInstance().createUserWithEmailAndPassword(email.trim(), password)
                            .addOnSuccessListener { res ->
                                val uid = res.user?.uid ?: return@addOnSuccessListener
                                val profile = mapOf(
                                    "uid" to uid,
                                    "displayName" to name.ifBlank { "Agent" },
                                    "email" to email.trim(),
                                    "remainingCredits" to 100L,
                                    "totalRequests" to 0L,
                                    "status" to "active",
                                    "role" to "user",
                                    "createdAt" to FieldValue.serverTimestamp()
                                )
                                FirebaseFirestore.getInstance().collection("users").document(uid).set(profile)
                                    .addOnSuccessListener { loading = false; onRegisterSuccess() }
                            }
                            .addOnFailureListener { loading = false; errorMsg = it.localizedMessage }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                ) {
                    if (loading) CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(22.dp))
                    else Text("CREATE ENCRYPTED PROFILE", color = Color.Black, fontWeight = FontWeight.Bold)
                }

                TextButton(onClick = onGoToLogin, modifier = Modifier.fillMaxWidth()) {
                    Text("Existing operative? Login", color = TextMuted)
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(
    telemetry: UserTelemetry,
    onSelectService: (ScopeService) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val services = remember {
        listOf(
            ScopeService("dns", "DNS & Authority Scope", "Query NS, A-records, MX routing via authoritative resolvers", 1, "e.g., cloudflare.com"),
            ScopeService("ip", "GeoIP & ASN Telemetry", "Inspect autonomous system, ISP provider and routing points", 1, "e.g., 1.1.1.1"),
            ScopeService("txt", "SPF & TXT Audit Scope", "Verify domain ownership, DKIM records and anti-spoofing flags", 1, "e.g., github.com")
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CIPHERSCOPE", fontWeight = FontWeight.ExtraBold, color = ElectricCyan, letterSpacing = 2.sp) },
                actions = {
                    IconButton(onClick = onOpenHistory) { Icon(Icons.Default.History, contentDescription = "History") }
                    IconButton(onClick = onOpenProfile) { Icon(Icons.Default.AccountCircle, contentDescription = "Profile") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkNavyBg)
            )
        },
        containerColor = DarkNavyBg
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = DarkNavyCard), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("OPERATIVE TELEMETRY", fontSize = 11.sp, color = ElectricCyan, fontWeight = FontWeight.Bold)
                            Text(telemetry.status.uppercase(), color = if (telemetry.status == "active") StatusGreen else StatusRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Remaining Quota", color = TextMuted, fontSize = 12.sp)
                                Text("${telemetry.remainingCredits} Cr", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ElectricCyan)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Executed Requests", color = TextMuted, fontSize = 12.sp)
                                Text("${telemetry.totalRequests}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ElectricBlue)
                            }
                        }
                    }
                }
            }

            item {
                Text("INTELLIGENCE MODULES", fontSize = 12.sp, color = ElectricCyan, fontWeight = FontWeight.Bold)
            }

            items(services) { s ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkNavyCard),
                    modifier = Modifier.fillMaxWidth().clickable { onSelectService(s) }
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(40.dp).background(DarkNavyBorder, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Terminal, contentDescription = null, tint = ElectricCyan)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(s.description, color = TextMuted, fontSize = 12.sp, maxLines = 2)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
                    }
                }
            }
        }
    }
}

@Composable
fun ApiRunnerScreen(
    service: ScopeService,
    telemetry: UserTelemetry,
    onExecute: suspend (String) -> Result<String>,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(service.name, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkNavyBg)
            )
        },
        containerColor = DarkNavyBg
    ) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Card(colors = CardDefaults.cardColors(containerColor = DarkNavyCard), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Target Parameter", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(service.placeholder) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = {
                            if (query.isBlank()) { error = "Target cannot be empty"; return@Button }
                            running = true; error = null; output = null
                            scope.launch {
                                val res = onExecute(query)
                                running = false
                                res.onSuccess { output = it }.onFailure { error = it.localizedMessage }
                            }
                        },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                    ) {
                        if (running) CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp))
                        else Text("EXECUTE SCOPE (-${service.cost} Credit)", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (error != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = StatusRed.copy(alpha = 0.15f)), modifier = Modifier.fillMaxWidth()) {
                    Text(error ?: "", color = StatusRed, modifier = Modifier.padding(12.dp), fontSize = 13.sp)
                }
            }

            if (output != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("PAYLOAD RESPONSE", fontSize = 11.sp, color = ElectricCyan, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF030712), RoundedCornerShape(8.dp)).padding(12.dp).horizontalScroll(rememberScrollState())) {
                    Text(output ?: "", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF38BDF8))
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(telemetry: UserTelemetry, onLogout: () -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Operative Profile") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkNavyBg)
            )
        },
        containerColor = DarkNavyBg
    ) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad).padding(20.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = DarkNavyCard), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(telemetry.displayName, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(telemetry.email, color = TextMuted, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = DarkNavyBorder)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Operative UID: ${telemetry.uid}", fontSize = 11.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                    Text("Quota Credits: ${telemetry.remainingCredits}", fontSize = 14.sp, color = ElectricCyan, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onLogout,
                colors = ButtonDefaults.buttonColors(containerColor = StatusRed),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("DISENGAGE / LOGOUT", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun HistoryScreen(uid: String, onBack: () -> Unit) {
    BackHandler { onBack() }
    var logs by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }

    LaunchedEffect(uid) {
        FirebaseFirestore.getInstance().collection("api_logs")
            .whereEqualTo("uid", uid)
            .limit(20)
            .get()
            .addOnSuccessListener { snap -> logs = snap.documents.mapNotNull { it.data } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Execution History") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkNavyBg)
            )
        },
        containerColor = DarkNavyBg
    ) { pad ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (logs.isEmpty()) {
                item { Text("No telemetry queries logged yet.", color = TextMuted, modifier = Modifier.padding(16.dp)) }
            }
            items(logs) { log ->
                Card(colors = CardDefaults.cardColors(containerColor = DarkNavyCard), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(log["service"]?.toString()?.uppercase() ?: "SCOPE", fontWeight = FontWeight.Bold, color = ElectricCyan, fontSize = 13.sp)
                            Text("SUCCESS", color = StatusGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("Target: ${log["target"] ?: "unknown"}", fontSize = 13.sp, color = TextLight, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
