package com.codex.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.codex.chat.ui.Motion
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.codex.chat.core.security.SecureKeyVault
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codex.chat.core.model.*
import com.codex.chat.core.network.*
import com.codex.chat.core.repository.DynamicModelsRepository
import com.codex.chat.core.repository.DynamicSubagentsRepository
import com.codex.chat.core.repository.SkillsRepository
import com.codex.chat.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import android.widget.RadioButton
import android.widget.RadioGroup
import com.codex.chat.core.attachment.AttachmentGuard
import com.codex.chat.core.concurrency.PollToken
import com.codex.chat.core.concurrency.StreamBuffer
import com.codex.chat.core.mcp.approval.*
import com.codex.chat.core.provider.BuiltInProviders
import com.codex.chat.core.provider.ProviderManager
import com.codex.chat.core.provider.ProviderProfile
import java.io.InputStream
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsManager
    private lateinit var apiClient: CodexApiClient
    private lateinit var modelsRepo: DynamicModelsRepository
    private lateinit var subagentsRepo: DynamicSubagentsRepository
    private lateinit var updateManager: AppUpdateManager
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var drawerAdapter: DrawerConversationsAdapter
    
    private val messages = mutableListOf<ChatMessage>()
    private val drawerConversations = mutableListOf<RemoteConversation>()
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val directE2bClient = DirectE2BClient()
    private val mobileWebSearchClient = MobileWebSearchClient()

    enum class AppMode {
        CHATGPT_NORMAL,
        CODEX_PC
    }

    private var currentMode = AppMode.CHATGPT_NORMAL
    private val chatGptMessages = mutableListOf<ChatMessage>()
    private val codexMessages = mutableListOf<ChatMessage>()

    private lateinit var localChatRepo: LocalChatRepository
    private var activeLocalSessionId: String? = null
    private var activeThreadId: String? = null
    private var activeCwd: String = ""
    private var activeSandboxPolicy: String = "danger-full-access"
    private var activeApprovalPolicy: String = "never"
    private var activeSubagent: SubagentInfo? = null
    private lateinit var skillsRepo: SkillsRepository
    private lateinit var mcpRegistry: com.codex.chat.core.mcp.McpRegistry
    private var activeSkill: SkillInfo? = null
    private lateinit var slashAdapter: SlashCommandsAdapter
    private var pendingAttachment: Attachment? = null
    private var isWebSearchActive = false
    private var isPythonModeActive = false
    private var activeCall: Call? = null
    private var tokenPollActivo: PollToken? = null
    private var codexPollJob: Thread? = null

    private val approvalGate by lazy {
        ToolApprovalGate(
            enHiloUi = { r -> runOnUiThread(r) },
            actividadViva = { !isFinishing && !isDestroyed },
            dialogRenderer = { req, callback -> showToolApprovalDialog(req, callback) }
        )
    }

    private val providerManager by lazy { ProviderManager(settings) }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    // File Picker launchers
    private val documentPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            handleFileUri(result.data!!.data!!, isImage = false)
        }
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            handleFileUri(result.data!!.data!!, isImage = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsManager(this)
        val initialProfile = providerManager.getActiveProfile()
        if (initialProfile.isReadOnly) {
            settings.baseUrl = initialProfile.baseUrl
            settings.apiKey = initialProfile.apiKey
        }
        apiClient = CodexApiClient()
        modelsRepo = DynamicModelsRepository()
        subagentsRepo = DynamicSubagentsRepository()
        skillsRepo = SkillsRepository(this)
        mcpRegistry = com.codex.chat.core.mcp.McpRegistry(this)
        updateManager = AppUpdateManager(this)
        localChatRepo = LocalChatRepository(this)
        com.codex.chat.core.media.GeneratedMediaStorage.init(this)

        val savedSkillId = settings.activeSkillId
        if (!savedSkillId.isNullOrBlank()) {
            activeSkill = skillsRepo.getSkillById(savedSkillId)
        }

        setupRecyclerView()
        setupDrawer()
        setupHeader()
        setupContextBar()
        setupModeSwitcher()
        setupInputListeners()
        setupKeyboardInsets()
        setupSpeechRecognizer()
        updateActiveSkillIndicator()

        onBackPressedDispatcher.addCallback(this) {
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        // Sync live models, load PC conversations from SQLite, and check for OTA updates
        syncLiveModels()
        loadRemoteConversations()
        fetchRemoteConfig()
        checkForAppUpdates(silent = true)
        checkAndPromptInitialPermissions()
    }

    private fun checkAndPromptInitialPermissions() {
        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { Environment.isExternalStorageManager() } catch (e: Throwable) { false }
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasAudio || !hasStorage) {
            requestMissingPermissions(hasStorage)
        }
    }

    private fun setupModeSwitcher() {
        binding.tabModeChatGpt.setOnClickListener {
            switchMode(AppMode.CHATGPT_NORMAL)
        }
        binding.tabModeCodex.setOnClickListener {
            switchMode(AppMode.CODEX_PC)
        }
        // Default on startup is ChatGPT Normal Mode
        switchMode(AppMode.CHATGPT_NORMAL)
    }

    private fun switchMode(mode: AppMode) {
        if (currentMode == mode) return
        currentMode = mode
        if (mode == AppMode.CHATGPT_NORMAL) {
            Motion.fadeBackgroundResource(binding.tabModeChatGpt, R.drawable.bg_tab_selected)
            Motion.animateTextColor(binding.tabModeChatGpt, Color.parseColor("#ECECEC"))
            Motion.fadeBackgroundResource(binding.tabModeCodex, R.drawable.bg_tab_unselected)
            Motion.animateTextColor(binding.tabModeCodex, Color.parseColor("#8E8E8E"))

            Motion.setGoneSmoothly(binding.activeContextBar, gone = true)
            binding.etMessage.hint = "Mensaje a ChatGPT..."
            binding.tvDrawerSectionTitle.text = "HISTORIAL (ChatGPT Móvil)"

            chatAdapter.setMessages(chatGptMessages)
            loadDrawerHistory()
        } else {
            Motion.fadeBackgroundResource(binding.tabModeCodex, R.drawable.bg_tab_selected_codex)
            Motion.animateTextColor(binding.tabModeCodex, Color.parseColor("#6EE7B7"))
            Motion.fadeBackgroundResource(binding.tabModeChatGpt, R.drawable.bg_tab_unselected)
            Motion.animateTextColor(binding.tabModeChatGpt, Color.parseColor("#8E8E8E"))

            Motion.slideUpFadeIn(binding.activeContextBar)
            binding.etMessage.hint = "Mensaje a Codex Desktop..."
            binding.tvDrawerSectionTitle.text = "HISTORIAL (Codex PC codex-dev.db)"

            chatAdapter.setMessages(codexMessages)
            loadDrawerHistory()
        }
    }
    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(messages) { msg ->
            continueAgenticTask(msg)
        }
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        binding.rvMessages.layoutManager = layoutManager
        binding.rvMessages.adapter = chatAdapter
        binding.rvMessages.itemAnimator = com.codex.chat.ui.ChatItemAnimator()

        slashAdapter = SlashCommandsAdapter(emptyList()) { cmd ->
            onSlashCommandSelected(cmd)
        }
        binding.rvSlashSuggestions.layoutManager = LinearLayoutManager(this)
        binding.rvSlashSuggestions.adapter = slashAdapter
    }

    private var lastScrollChatTime = 0L

    private fun scrollChatToBottom(smooth: Boolean = false, onlyIfAtBottom: Boolean = false) {
        if (messages.isEmpty()) return
        if (onlyIfAtBottom && binding.rvMessages.canScrollVertically(1)) {
            // Usuario está leyendo historial arriba: no forzar salto brusco
            return
        }
        val now = System.currentTimeMillis()
        if (onlyIfAtBottom && now - lastScrollChatTime < 90) {
            // Evita saltos bruscos y micro-tirones durante ráfagas de streaming
            return
        }
        lastScrollChatTime = now
        val lastIdx = messages.size - 1
        if (smooth) {
            binding.rvMessages.smoothScrollToPosition(lastIdx)
        } else {
            binding.rvMessages.scrollToPosition(lastIdx)
        }
    }

    private fun continueAgenticTask(msg: ChatMessage) {
        msg.canContinueTask = false
        chatAdapter.notifyDataSetChanged()
        binding.etMessage.setText("Continúa con la tarea exactamente desde donde te quedaste. Ejecuta los siguientes pasos o herramientas necesarias.")
        sendMessage()
    }

    private fun setupDrawer() {
        drawerAdapter = DrawerConversationsAdapter(drawerConversations) { conv ->
            if (currentMode == AppMode.CHATGPT_NORMAL) {
                loadLocalSession(conv.threadId)
            } else {
                loadConversationThread(conv)
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        binding.rvDrawerConversations.layoutManager = LinearLayoutManager(this)
        binding.rvDrawerConversations.adapter = drawerAdapter

        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
            loadDrawerHistory()
        }

        binding.btnDrawerNewChat.setOnClickListener {
            startNewChat()
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        binding.btnDrawerSettings.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showSettingsDialog()
        }

        binding.tvDrawerPort.text = "v" + BuildConfig.VERSION_NAME

        binding.btnDrawerSkillStore.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showSkillStoreBottomSheet()
        }
    }

    private fun loadDrawerHistory() {
        if (currentMode == AppMode.CHATGPT_NORMAL) {
            // FIX: getAllSessions fuera del hilo UI (la primera llamada puede leer el JSON multi-MB).
            thread {
                val sessions = localChatRepo.getAllSessions()
                val list = sessions.map { s ->
                    RemoteConversation(
                        threadId = s.id,
                        title = s.title,
                        dateFormatted = s.formattedDate,
                        cwd = "",
                        provider = "local"
                    )
                }
                runOnUiThread {
                    drawerAdapter.updateData(list, activeLocalSessionId)
                    binding.tvServerDot.text = "${list.size} chats locales"
                    binding.tvServerDot.setTextColor(Color.parseColor("#10A37F"))
                }
            }
        } else {
            loadRemoteConversations()
        }
    }

    private fun loadLocalSession(sessionId: String) {
        // FIX conversación congelada al reabrir: obtener la sesión NUNCA en el hilo UI.
        // (getSession puede tocar el JSON multi-MB la primera vez: 451 ms medidos con 10 imágenes.)
        thread {
            val session = localChatRepo.getSession(sessionId) ?: return@thread
            runOnUiThread {
                activeLocalSessionId = session.id
                chatGptMessages.clear()
                chatGptMessages.addAll(session.messages)
                chatAdapter.setMessages(chatGptMessages)
                if (messages.isNotEmpty()) {
                    binding.rvMessages.scrollToPosition(messages.size - 1)
                }
            }
        }
    }

    private fun setupHeader() {
        updateHeaderBadges()

        binding.modelSelectorContainer.setOnClickListener {
            showModelAndEffortPicker()
        }

        binding.btnNewChat.setOnClickListener {
            startNewChat()
        }
    }

    private fun setupContextBar() {
        updateContextBarDisplay()
        binding.activeContextBar.setOnClickListener {
            showCodexActionsBottomSheet()
        }
    }

    private fun updateContextBarDisplay() {
        val folderName = if (activeCwd.isNotEmpty()) {
            activeCwd.split("\\", "/").lastOrNull() ?: activeCwd
        } else {
            "Desktop"
        }
        binding.tvActiveCwdIndicator.text = "📁 " + folderName

        when (activeSandboxPolicy) {
            "danger-full-access" -> {
                binding.tvActivePolicyIndicator.text = "🔴 Full Access"
                binding.tvActivePolicyIndicator.setTextColor(Color.parseColor("#FF7B72"))
            }
            "workspace-write" -> {
                binding.tvActivePolicyIndicator.text = "🟡 Workspace"
                binding.tvActivePolicyIndicator.setTextColor(Color.parseColor("#E3B341"))
            }
            else -> {
                binding.tvActivePolicyIndicator.text = "🟢 Read Only"
                binding.tvActivePolicyIndicator.setTextColor(Color.parseColor("#7EE787"))
            }
        }
    }

    private fun updateHeaderBadges() {
        val model = modelsRepo.getModelById(settings.selectedModelId)
        binding.tvModelTitle.text = model.displayName + " ▾"

        if (model.supportsReasoning) {
            binding.tvEffortBadge.visibility = View.VISIBLE
            binding.tvEffortBadge.text = settings.reasoningEffort.value.uppercase()
        } else {
            binding.tvEffortBadge.visibility = View.GONE
        }
    }

    private fun startNewChat() {
        activeCall?.cancel()
        activeCall = null
        binding.btnSend.isEnabled = true

        pendingAttachment = null
        Motion.slideDownFadeOut(binding.attachmentPreviewBar)
        chatAdapter.setMessages(emptyList())

        if (currentMode == AppMode.CHATGPT_NORMAL) {
            activeLocalSessionId = null
            chatGptMessages.clear()
            loadDrawerHistory()
            Toast.makeText(this, "Nuevo chat", Toast.LENGTH_SHORT).show()
        } else {
            activeThreadId = null
            codexMessages.clear()
            loadDrawerHistory()
            thread {
                try {
                    val url = getCodexServerBaseUrl() + "/api/new_chat"
                    val json = JSONObject().apply {
                        put("cwd", activeCwd)
                        put("sandbox_policy", activeSandboxPolicy)
                    }
                    val body = json.toString().toRequestBody("application/json".toMediaType())
                    val req = Request.Builder().url(url).post(body).build()
                    okHttpClient.newCall(req).execute()
                } catch (e: Exception) {
                    // Ignore network errors on new_chat notify
                }
            }
            Toast.makeText(this, "Nueva sesión en PC", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupInputListeners() {
        binding.btnSend.setOnClickListener {
            sendMessage()
        }

        binding.btnMic.setOnClickListener {
            toggleSpeech()
        }

        binding.btnPlus.setOnClickListener {
            if (currentMode == AppMode.CHATGPT_NORMAL) {
                showChatGptNormalBottomSheet()
            } else {
                showCodexActionsBottomSheet()
            }
        }

        binding.btnWebSearchToggle.setOnClickListener {
            setWebSearchActive(!isWebSearchActive)
        }

        setupMcpPolicyButton()

        binding.btnRemoveAttachment.setOnClickListener {
            pendingAttachment = null
            setWebSearchActive(false)
            setPythonModeActive(false)
            Motion.slideDownFadeOut(binding.attachmentPreviewBar)
            binding.etMessage.hint = if (currentMode == AppMode.CHATGPT_NORMAL) "Mensaje a ChatGPT..." else "Mensaje a Codex Desktop..."
            val hasText = !binding.etMessage.text.isNullOrBlank()
            binding.btnSend.visibility = if (hasText) View.VISIBLE else View.GONE
            binding.btnMic.visibility = if (hasText) View.GONE else View.VISIBLE
        }

        binding.etMessage.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && messages.isNotEmpty()) {
                binding.rvMessages.postDelayed({
                    binding.rvMessages.scrollToPosition(messages.size - 1)
                }, 200)
            }
        }

        binding.etMessage.setOnClickListener {
            if (messages.isNotEmpty()) {
                binding.rvMessages.postDelayed({
                    binding.rvMessages.scrollToPosition(messages.size - 1)
                }, 200)
            }
        }

        // Active Skill bar listeners
        binding.btnCloseActiveSkill.setOnClickListener {
            deactivateSkill()
        }
        binding.btnViewActiveSkillRules.setOnClickListener {
            activeSkill?.let { showSkillDetailsDialog(it) }
        }

        // Initial state of send button and mic (Authentic ChatGPT dynamic visibility)
        binding.btnSend.visibility = View.GONE
        binding.btnMic.visibility = View.VISIBLE

        // Toggle send button appearance based on input length
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrBlank() || pendingAttachment != null
                if (hasText) {
                    Motion.setGoneSmoothly(binding.btnMic, gone = true, duration = Motion.DURATION_XS)
                    Motion.popIn(binding.btnSend, duration = Motion.DURATION_S)
                } else {
                    Motion.popIn(binding.btnMic, duration = Motion.DURATION_S)
                    Motion.setGoneSmoothly(binding.btnSend, gone = true, duration = Motion.DURATION_XS)
                }

                // Dynamic Slash Commands Autocomplete (/ Claude Style)
                val currentText = s?.toString() ?: ""
                updateSlashSuggestions(currentText)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupKeyboardInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            val bottomInset = if (ime.bottom > 0) ime.bottom else systemBars.bottom

            binding.mainContent.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                bottomInset
            )

            if (ime.bottom > 0 && messages.isNotEmpty()) {
                binding.rvMessages.postDelayed({
                    binding.rvMessages.scrollToPosition(messages.size - 1)
                }, 100)
            }

            insets
        }
    }

    private fun setWebSearchActive(active: Boolean) {
        isWebSearchActive = active
        if (active) {
            setPythonModeActive(false)
            binding.btnWebSearchToggle.setColorFilter(Color.parseColor("#10A37F"))
            binding.tvAttachmentIcon.text = "🌐"
            binding.tvAttachmentName.text = "Búsqueda Web en Vivo (Activa para la próxima consulta)"
            Motion.slideUpFadeIn(binding.attachmentPreviewBar)
            binding.etMessage.hint = "Pregunta lo que sea en internet..."
            Toast.makeText(this, "🌐 Búsqueda Web activada: escribe tu pregunta normalmente", Toast.LENGTH_SHORT).show()
        } else {
            binding.btnWebSearchToggle.setColorFilter(Color.parseColor("#8E8E8E"))
            if (pendingAttachment == null && !isPythonModeActive) {
                Motion.slideDownFadeOut(binding.attachmentPreviewBar)
                binding.etMessage.hint = if (currentMode == AppMode.CHATGPT_NORMAL) "Mensaje a ChatGPT..." else "Mensaje a Codex Desktop..."
            }
        }
    }

    private fun setupMcpPolicyButton() {
        updateMcpPolicyButtonUi()

        binding.btnMcpPolicyToggle.setOnClickListener {
            val nextPolicy = when (settings.approvalPolicy) {
                ApprovalPolicy.ALWAYS_ASK -> ApprovalPolicy.ASK_ON_RISK
                ApprovalPolicy.ASK_ON_RISK -> ApprovalPolicy.FULL_ACCESS
                ApprovalPolicy.FULL_ACCESS -> ApprovalPolicy.ALWAYS_ASK
            }
            settings.approvalPolicy = nextPolicy
            updateMcpPolicyButtonUi()

            val msg = when (nextPolicy) {
                ApprovalPolicy.ALWAYS_ASK -> "🛡️ MCP Nivel 1: Solicitar Aprobación (Máxima Seguridad)"
                ApprovalPolicy.ASK_ON_RISK -> "🛡️ MCP Nivel 2: Preguntar por Mí (Recomendado)"
                ApprovalPolicy.FULL_ACCESS -> "⚡ MCP Nivel 3: Acceso Completo (Autónomo)"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.btnMcpPolicyToggle.setOnLongClickListener {
            val options = arrayOf(
                "🛡️ Nivel 1 - Solicitar Aprobación\nToda herramienta pide confirmación (Máxima seguridad)",
                "🛡️ Nivel 2 - Preguntar por Mí (Recomendado)\nHerramientas seguras van solas; sensibles o root preguntan",
                "⚡ Nivel 3 - Acceso Completo (Autónomo)\nCodex ejecuta todo sin pedir confirmación"
            )
            val policies = arrayOf(
                ApprovalPolicy.ALWAYS_ASK,
                ApprovalPolicy.ASK_ON_RISK,
                ApprovalPolicy.FULL_ACCESS
            )
            val currentIdx = policies.indexOf(settings.approvalPolicy).coerceAtLeast(0)

            MaterialAlertDialogBuilder(this)
                .setTitle("🛡️ Política de Seguridad MCP")
                .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                    val chosen = policies[which]
                    settings.approvalPolicy = chosen
                    updateMcpPolicyButtonUi()
                    dialog.dismiss()
                    val toastMsg = when (chosen) {
                        ApprovalPolicy.ALWAYS_ASK -> "🛡️ MCP Nivel 1: Solicitar Aprobación"
                        ApprovalPolicy.ASK_ON_RISK -> "🛡️ MCP Nivel 2: Preguntar por Mí"
                        ApprovalPolicy.FULL_ACCESS -> "⚡ MCP Nivel 3: Acceso Completo"
                    }
                    Toast.makeText(this@MainActivity, toastMsg, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cerrar", null)
                .show()
            true
        }
    }

    private fun updateMcpPolicyButtonUi() {
        when (settings.approvalPolicy) {
            ApprovalPolicy.ALWAYS_ASK -> {
                binding.btnMcpPolicyToggle.setColorFilter(Color.parseColor("#38BDF8"))
                binding.btnMcpPolicyToggle.contentDescription = "MCP Nivel 1: Solicitar Aprobación"
            }
            ApprovalPolicy.ASK_ON_RISK -> {
                binding.btnMcpPolicyToggle.setColorFilter(Color.parseColor("#10A37F"))
                binding.btnMcpPolicyToggle.contentDescription = "MCP Nivel 2: Preguntar por Mí"
            }
            ApprovalPolicy.FULL_ACCESS -> {
                binding.btnMcpPolicyToggle.setColorFilter(Color.parseColor("#F59E0B"))
                binding.btnMcpPolicyToggle.contentDescription = "MCP Nivel 3: Acceso Completo Autónomo"
            }
        }
    }

    private fun setPythonModeActive(active: Boolean) {
        isPythonModeActive = active
        if (active) {
            setWebSearchActive(false)
            binding.tvAttachmentIcon.text = "🐍"
            binding.tvAttachmentName.text = "Modo Python E2B Cloud (MicroVM en la nube)"
            Motion.slideUpFadeIn(binding.attachmentPreviewBar)
            binding.etMessage.hint = "Escribe código Python para ejecutar en la nube..."
            Toast.makeText(this, "🐍 Modo Python Cloud activado: escribe tu código directamente", Toast.LENGTH_SHORT).show()
        } else {
            if (pendingAttachment == null && !isWebSearchActive) {
                Motion.slideDownFadeOut(binding.attachmentPreviewBar)
                binding.etMessage.hint = if (currentMode == AppMode.CHATGPT_NORMAL) "Mensaje a ChatGPT..." else "Mensaje a Codex Desktop..."
            }
        }
    }

    private fun showChatGptNormalBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_chatgpt_normal, null)
        dialog.setContentView(view)

        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        view.findViewById<View>(R.id.actionNormalAttachDoc).setOnClickListener {
            dialog.dismiss()
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/json", "application/javascript", "application/python", "application/pdf"))
            }
            documentPickerLauncher.launch(intent)
        }

        view.findViewById<View>(R.id.actionNormalAttachImage).setOnClickListener {
            dialog.dismiss()
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
            }
            imagePickerLauncher.launch(intent)
        }

        view.findViewById<View>(R.id.actionNormalWebSearch).setOnClickListener {
            dialog.dismiss()
            setWebSearchActive(!isWebSearchActive)
        }

        view.findViewById<View>(R.id.actionNormalCloudPython).setOnClickListener {
            dialog.dismiss()
            setPythonModeActive(!isPythonModeActive)
        }

        view.findViewById<View>(R.id.actionNormalSubagents).setOnClickListener {
            dialog.dismiss()
            showSkillStoreBottomSheet()
        }

        dialog.show()
    }

    private fun updateActiveSkillIndicator() {
        if (activeSkill != null) {
            Motion.slideUpFadeIn(binding.activeSkillBar)
            binding.tvActiveSkillIcon.text = activeSkill?.iconEmoji ?: "⚡"
            binding.tvActiveSkillName.text = "Skill: " + activeSkill?.name
        } else {
            Motion.slideDownFadeOut(binding.activeSkillBar)
        }
    }

    private fun activateSkill(skill: SkillInfo) {
        activeSkill = skill
        settings.activeSkillId = skill.id
        updateActiveSkillIndicator()
        Toast.makeText(this, "Skill activada: " + skill.name, Toast.LENGTH_SHORT).show()
        val notice = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = skill.iconEmoji + " **Skill nativa activada:** `" + skill.name + "` (" + skill.category + ")\n*" + skill.description + "*"
        )
        if (currentMode == AppMode.CHATGPT_NORMAL) chatGptMessages.add(notice) else codexMessages.add(notice)
        chatAdapter.addMessage(notice)
        binding.rvMessages.scrollToPosition(messages.size - 1)
    }

    private fun deactivateSkill() {
        val name = activeSkill?.name ?: "Skill"
        activeSkill = null
        settings.activeSkillId = null
        updateActiveSkillIndicator()
        Toast.makeText(this, name + " desactivada", Toast.LENGTH_SHORT).show()
    }

    private fun showSkillStoreBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_skill_store, null)
        dialog.setContentView(view)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val rv = view.findViewById<RecyclerView>(R.id.rvSkillsStore)
        val etSearch = view.findViewById<EditText>(R.id.etSearchSkills)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmptySkills)
        val btnSync = view.findViewById<TextView>(R.id.btnSyncPcSkills)
        val btnCreate = view.findViewById<TextView>(R.id.btnCreateCustomSkill)

        var currentCategory = "Todas"
        val allSkills = skillsRepo.getAllSkills().toMutableList()

        lateinit var adapter: SkillsAdapter

        fun getFilteredSkills(): List<SkillInfo> {
            val query = etSearch.text.toString().trim().lowercase()
            return allSkills.filter { skill ->
                val matchesCategory = when (currentCategory) {
                    "Todas" -> true
                    "Activas" -> skill.id == activeSkill?.id
                    "Mis Skills" -> skill.isCustom
                    else -> skill.category.equals(currentCategory, ignoreCase = true)
                }
                val matchesQuery = query.isEmpty() ||
                        skill.name.lowercase().contains(query) ||
                        skill.description.lowercase().contains(query) ||
                        skill.category.lowercase().contains(query)
                matchesCategory && matchesQuery
            }
        }

        fun refreshList() {
            val filtered = getFilteredSkills()
            tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            adapter.updateData(filtered, activeSkill?.id)
        }

        adapter = SkillsAdapter(
            skills = getFilteredSkills(),
            activeSkillId = activeSkill?.id,
            onToggle = { skill ->
                if (activeSkill?.id == skill.id) {
                    deactivateSkill()
                } else {
                    activateSkill(skill)
                }
                refreshList()
            },
            onDetails = { skill ->
                showSkillDetailsDialog(skill) {
                    refreshList()
                }
            },
            onDelete = { skill ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Eliminar skill")
                    .setMessage("¿Deseas eliminar la skill personalizada '" + skill.name + "'?")
                    .setPositiveButton("Eliminar") { _, _ ->
                        skillsRepo.deleteCustomSkill(skill.id)
                        if (activeSkill?.id == skill.id) deactivateSkill()
                        allSkills.clear()
                        allSkills.addAll(skillsRepo.getAllSkills())
                        refreshList()
                        Toast.makeText(this, "Skill eliminada", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        )

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        // Setup Chips
        val chipAll = view.findViewById<TextView>(R.id.chipAll)
        val chipActive = view.findViewById<TextView>(R.id.chipActive)
        val chipClaudeCodex = view.findViewById<TextView>(R.id.chipClaudeCodex)
        val chipEng = view.findViewById<TextView>(R.id.chipEngineering)
        val chipDevops = view.findViewById<TextView>(R.id.chipDevops)
        val chipSec = view.findViewById<TextView>(R.id.chipSecurity)
        val chipAiMcp = view.findViewById<TextView>(R.id.chipAiMcp)
        val chipFrontend = view.findViewById<TextView>(R.id.chipFrontend)
        val chipProduct = view.findViewById<TextView>(R.id.chipProduct)
        val chipProductivity = view.findViewById<TextView>(R.id.chipProductivity)
        val chipCustom = view.findViewById<TextView>(R.id.chipCustom)

        val chips = listOf(
            chipAll to "Todas",
            chipActive to "Activas",
            chipClaudeCodex to "Claude & Codex",
            chipEng to "Ingeniería",
            chipDevops to "DevOps & Cloud",
            chipSec to "Ciberseguridad",
            chipAiMcp to "IA & MCP",
            chipFrontend to "Frontend",
            chipProduct to "C-Level & Producto",
            chipProductivity to "Productividad",
            chipCustom to "Mis Skills"
        )

        fun selectChip(selectedCat: String) {
            currentCategory = selectedCat
            chips.forEach { (viewChip, cat) ->
                if (cat == selectedCat) {
                    viewChip.setBackgroundColor(Color.parseColor("#10A37F"))
                    viewChip.setTextColor(Color.WHITE)
                } else {
                    viewChip.setBackgroundColor(Color.parseColor("#212121"))
                    viewChip.setTextColor(Color.parseColor("#A0A0A0"))
                }
            }
            refreshList()
        }

        chips.forEach { (viewChip, cat) ->
            viewChip.setOnClickListener { selectChip(cat) }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Sync with PC Button
        btnSync.setOnClickListener {
            btnSync.text = "Sincronizando…"
            thread {
                val (ok, count) = skillsRepo.syncWithPcServer(getCodexServerBaseUrl())
                runOnUiThread {
                    btnSync.text = "🔄 Sincronizar PC"
                    if (ok) {
                        allSkills.clear()
                        allSkills.addAll(skillsRepo.getAllSkills())
                        refreshList()
                        Toast.makeText(this, "Sincronizado: " + count + " skills de PC", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "PC offline o error conectando al servidor", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Install from URL / GitHub Button (/install)
        val btnInstallUrl = view.findViewById<TextView>(R.id.btnInstallUrlSkill)
        btnInstallUrl.setOnClickListener {
            dialog.dismiss()
            showInstallSkillDialog()
        }

        // Create Custom Skill
        btnCreate.setOnClickListener {
            showCreateSkillDialog { newSkill ->
                skillsRepo.addCustomSkill(newSkill)
                allSkills.clear()
                allSkills.addAll(skillsRepo.getAllSkills())
                selectChip("Mis Skills")
                Toast.makeText(this, "Skill guardada: " + newSkill.name, Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showSkillDetailsDialog(skill: SkillInfo, onStateChanged: (() -> Unit)? = null) {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_skill_details, null)
        dialog.setContentView(view)

        val tvIcon = view.findViewById<TextView>(R.id.tvDetailSkillIcon)
        val tvName = view.findViewById<TextView>(R.id.tvDetailSkillName)
        val tvMeta = view.findViewById<TextView>(R.id.tvDetailSkillMeta)
        val tvDesc = view.findViewById<TextView>(R.id.tvDetailSkillDescription)
        val tvRules = view.findViewById<TextView>(R.id.tvDetailSkillRules)
        val btnClose = view.findViewById<TextView>(R.id.btnDetailClose)
        val btnToggle = view.findViewById<TextView>(R.id.btnDetailToggle)

        tvIcon.text = skill.iconEmoji
        tvName.text = skill.name
        tvMeta.text = skill.category + " • Autor: " + skill.author
        tvDesc.text = skill.description
        tvRules.text = skill.systemPrompt

        val isActive = skill.id == activeSkill?.id
        if (isActive) {
            btnToggle.text = "Desactivar Skill"
            btnToggle.setBackgroundColor(Color.parseColor("#D32F2F"))
        } else {
            btnToggle.text = "⚡ Activar Skill"
            btnToggle.setBackgroundColor(Color.parseColor("#10A37F"))
        }

        btnToggle.setOnClickListener {
            if (activeSkill?.id == skill.id) {
                deactivateSkill()
            } else {
                activateSkill(skill)
            }
            onStateChanged?.invoke()
            dialog.dismiss()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showCreateSkillDialog(onCreated: (SkillInfo) -> Unit) {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_create_skill, null)
        dialog.setContentView(view)

        val etEmoji = view.findViewById<EditText>(R.id.etNewSkillEmoji)
        val etName = view.findViewById<EditText>(R.id.etNewSkillName)
        val etCat = view.findViewById<EditText>(R.id.etNewSkillCategory)
        val etDesc = view.findViewById<EditText>(R.id.etNewSkillDescription)
        val etPrompt = view.findViewById<EditText>(R.id.etNewSkillPrompt)
        val btnSave = view.findViewById<TextView>(R.id.btnSaveNewSkill)
        val btnCancel = view.findViewById<TextView>(R.id.btnCancelNewSkill)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            val prompt = etPrompt.text.toString().trim()
            if (name.isEmpty()) {
                etName.error = "Ingresa el nombre de la skill"
                return@setOnClickListener
            }
            if (prompt.isEmpty()) {
                etPrompt.error = "Ingresa las directivas e instrucciones"
                return@setOnClickListener
            }

            val emoji = etEmoji.text.toString().trim().ifEmpty { "⚡" }
            val cat = etCat.text.toString().trim().ifEmpty { "Personalizadas" }
            val desc = etDesc.text.toString().trim().ifEmpty { "Skill personalizada de " + name }
            val id = "custom-" + System.currentTimeMillis()

            val skill = SkillInfo(
                id = id,
                name = name,
                description = desc,
                category = cat,
                systemPrompt = prompt,
                iconEmoji = emoji,
                author = "Usuario",
                isInstalled = true,
                isCustom = true
            )

            dialog.dismiss()
            onCreated(skill)
        }

        dialog.show()
    }

    private fun showSubagentsPicker() {
        showSkillStoreBottomSheet()
    }

    private fun onSlashCommandSelected(cmd: SlashCommandInfo) {
        Motion.slideDownFadeOut(binding.slashSuggestionsContainer)
        when (cmd.actionType) {
            SlashActionType.OPEN_STORE -> {
                binding.etMessage.setText("")
                showSkillStoreBottomSheet()
            }
            SlashActionType.CLEAR_CHAT -> {
                binding.etMessage.setText("")
                startNewChat()
            }
            SlashActionType.INSTALL_SKILL_DIALOG -> {
                binding.etMessage.setText("")
                showInstallSkillDialog()
            }
            SlashActionType.EXECUTE_INSTANT -> {
                if (cmd.targetSkillId != null) {
                    val skill = skillsRepo.getSkillById(cmd.targetSkillId)
                    if (skill != null) {
                        binding.etMessage.setText("")
                        activateSkill(skill)
                    }
                } else if (cmd.command == "/unskill") {
                    binding.etMessage.setText("")
                    deactivateSkill()
                }
            }
            SlashActionType.AUTOCOMPLETE -> {
                binding.etMessage.setText(cmd.command + " ")
                binding.etMessage.setSelection(binding.etMessage.text.length)
                Motion.slideDownFadeOut(binding.slashSuggestionsContainer)
            }
        }
    }

    private fun updateSlashSuggestions(input: String) {
        if (!input.startsWith("/") || input.contains(" ")) {
            Motion.slideDownFadeOut(binding.slashSuggestionsContainer)
            return
        }

        val query = input.removePrefix("/").trim().lowercase()
        val allSkills = skillsRepo.getAllSkills()

        val list = mutableListOf<SlashCommandInfo>()

        // System slash commands
        list.add(SlashCommandInfo("/skills", "Abrir la Tienda Oficial de Skills", "🧭", "STORE", SlashActionType.OPEN_STORE))
        list.add(SlashCommandInfo("/mcp", "Administrador de Servidores MCP Nativos", "🔌", "MCP", SlashActionType.AUTOCOMPLETE))
        list.add(SlashCommandInfo("/mcp store", "Imprimir Tienda de Servidores MCP (Claude)", "🏪", "STORE", SlashActionType.EXECUTE_INSTANT))
        list.add(SlashCommandInfo("/mcp tools", "Listar herramientas MCP nativas activas", "🛠️", "MCP", SlashActionType.EXECUTE_INSTANT))
        list.add(SlashCommandInfo("/permissions", "Estado y concesión de todos los permisos del APK", "🛡️", "PERMS", SlashActionType.EXECUTE_INSTANT))
        list.add(SlashCommandInfo("/battery", "Consultar batería y hardware del móvil", "🔋", "MCP", SlashActionType.AUTOCOMPLETE))
        list.add(SlashCommandInfo("/device", "Consultar telemetría de hardware Android", "📱", "MCP", SlashActionType.AUTOCOMPLETE))
        list.add(SlashCommandInfo("/memory", "Memoria persistente de hechos e IA", "🧠", "MCP", SlashActionType.AUTOCOMPLETE))
        list.add(SlashCommandInfo("/calc", "Calculadora matemática y utilidades", "🧮", "MCP", SlashActionType.AUTOCOMPLETE))
        list.add(SlashCommandInfo("/install", "Instalar skill desde GitHub o URL", "📥", "INSTALL", SlashActionType.INSTALL_SKILL_DIALOG))
        list.add(SlashCommandInfo("/unskill", "Desactivar la skill activa actual", "❌", "CLEAR", SlashActionType.EXECUTE_INSTANT))
        list.add(SlashCommandInfo("/clear", "Limpiar mensajes e iniciar nuevo chat", "🧹", "RESET", SlashActionType.CLEAR_CHAT))
        list.add(SlashCommandInfo("/help", "Ver comandos disponibles", "❓", "HELP", SlashActionType.AUTOCOMPLETE))

        // Dynamic skill commands
        for (skill in allSkills) {
            val shortId = skill.id
                .removePrefix("codex-")
                .removePrefix("anthropic-")
                .removePrefix("devops-")
                .removePrefix("security-")
                .removePrefix("fullstack-")
                .removePrefix("ai-")
                .removePrefix("style-")
                .removePrefix("c-level-")
            list.add(
                SlashCommandInfo(
                    command = "/$shortId",
                    description = skill.name + " • " + skill.category,
                    iconEmoji = skill.iconEmoji,
                    badge = "SKILL",
                    actionType = SlashActionType.EXECUTE_INSTANT,
                    targetSkillId = skill.id
                )
            )
        }

        val filtered = if (query.isEmpty()) {
            list.take(7)
        } else {
            list.filter {
                it.command.lowercase().contains(query) ||
                it.description.lowercase().contains(query)
            }.take(7)
        }

        if (filtered.isNotEmpty()) {
            slashAdapter.updateData(filtered)
            Motion.slideUpFadeIn(binding.slashSuggestionsContainer)
        } else {
            Motion.slideDownFadeOut(binding.slashSuggestionsContainer)
        }
    }

    private fun handleSlashCommand(commandText: String): Boolean {
        val trimmed = commandText.trim()
        if (trimmed.isEmpty() || trimmed == "/") {
            return false
        }
        val parts = trimmed.split("\\s+".toRegex(), limit = 2)
        val cmd = parts[0].lowercase()
        val arg = if (parts.size > 1) parts[1].trim() else ""

        when {
            cmd == "/skills" || cmd == "/store" -> {
                showSkillStoreBottomSheet()
                return true
            }
            cmd == "/permissions" || cmd == "/perm" || cmd == "/perms" -> {
                showPermissionsStatusAndRequest()
                return true
            }
            cmd == "/mcp" || cmd == "/tools" || cmd == "/mcp-store" || cmd == "/mcpstore" -> {
                when {
                    cmd == "/mcp-store" || cmd == "/mcpstore" || arg == "store" || arg == "claude" || arg == "market" || arg == "tienda" || arg == "catalog" -> {
                        printOfficialMcpStoreToChat()
                    }
                    arg == "tools" || arg == "list" -> {
                        printMcpToolsList()
                    }
                    arg.startsWith("install ") -> {
                        executeInstallMcpServer(arg.removePrefix("install ").trim())
                    }
                    arg.startsWith("call ") -> {
                        executeMcpToolDirect(arg.removePrefix("call ").trim())
                    }
                    arg.isEmpty() -> {
                        showMcpManagerBottomSheet()
                    }
                    else -> {
                        showMcpManagerBottomSheet()
                    }
                }
                return true
            }
            cmd == "/battery" -> {
                executeMcpToolDirect("get_battery_status")
                return true
            }
            cmd == "/device" -> {
                executeMcpToolDirect("get_device_telemetry")
                return true
            }
            cmd == "/memory" -> {
                if (arg.isEmpty()) {
                    executeMcpToolDirect("list_memories")
                } else if (arg.startsWith("save ")) {
                    val payload = arg.removePrefix("save ").trim()
                    val partsKv = payload.split("=", ":", limit = 2)
                    val k = partsKv[0].trim()
                    val v = if (partsKv.size > 1) partsKv[1].trim() else ""
                    executeMcpToolDirect("save_memory {\"key\":\"$k\",\"value\":\"$v\"}")
                } else {
                    executeMcpToolDirect("get_memory {\"key\":\"$arg\"}")
                }
                return true
            }
            cmd == "/calc" || cmd == "/calculate" -> {
                if (arg.isEmpty()) {
                    executeMcpToolDirect("evaluate_math {\"expression\":\"2^10 + 24\"}")
                } else {
                    executeMcpToolDirect("evaluate_math {\"expression\":\"$arg\"}")
                }
                return true
            }
            cmd == "/root" || cmd == "/su" -> {
                showRootStatusOrExecute(arg)
                return true
            }
            cmd == "/py" || cmd == "/python" || cmd == "/e2b" -> {
                if (arg.isEmpty()) {
                    executeCloudPython("print('¡Hola desde Python en E2B Cloud MicroVM!')")
                } else {
                    executeCloudPython(arg)
                }
                return true
            }
            cmd == "/unskill" || cmd == "/noskill" -> {
                deactivateSkill()
                return true
            }
            cmd == "/clear" || cmd == "/new" -> {
                startNewChat()
                return true
            }
            cmd == "/install" || cmd == "/install-skill" || cmd == "/add-skill" -> {
                if (arg.isEmpty()) {
                    showInstallSkillDialog()
                } else {
                    executeInstallSkillCommand(arg)
                }
                return true
            }
            cmd == "/help" -> {
                showSlashHelpNotice()
                return true
            }
            else -> {
                // Check if user entered /<skill-id>
                val rawId = cmd.removePrefix("/")
                if (rawId.length >= 2) {
                    val skill = skillsRepo.getAllSkills().find {
                        it.id.equals(rawId, ignoreCase = true) ||
                        it.id.removePrefix("codex-").removePrefix("anthropic-").equals(rawId, ignoreCase = true) ||
                        it.name.replace(" ", "-").equals(rawId, ignoreCase = true) ||
                        (rawId.length >= 3 && it.id.removePrefix("codex-").removePrefix("anthropic-").startsWith(rawId, ignoreCase = true))
                    }
                    if (skill != null) {
                        activateSkill(skill)
                        if (arg.isNotEmpty()) {
                            val cleanArg = if (arg.startsWith("/")) arg.removePrefix("/").trim() else arg
                            if (cleanArg.isNotEmpty()) {
                                dispatchDirectPrompt(cleanArg)
                            }
                        }
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun dispatchDirectPrompt(promptText: String) {
        val cleanPrompt = promptText.trim()
        if (cleanPrompt.isEmpty()) return

        val userMsg = ChatMessage(
            role = MessageRole.USER,
            content = cleanPrompt
        )
        if (currentMode == AppMode.CHATGPT_NORMAL) {
            chatGptMessages.add(userMsg)
        } else {
            codexMessages.add(userMsg)
        }
        chatAdapter.addMessage(userMsg)

        val assistantMsg = ChatMessage(role = MessageRole.ASSISTANT, content = "Pensando…", isStreaming = true)
        chatAdapter.addMessage(assistantMsg)
        binding.rvMessages.scrollToPosition(messages.size - 1)

        binding.btnSend.isEnabled = false

        if (currentMode == AppMode.CODEX_PC) {
            sendCodexPcMessage(cleanPrompt)
            return
        }

        executeStreamWithContext(cleanPrompt, "")
    }

    private fun executeInstallSkillCommand(urlOrId: String) {
        val targetMode = currentMode
        val userNotice = ChatMessage(role = MessageRole.USER, content = "/install " + urlOrId)
        if (targetMode == AppMode.CHATGPT_NORMAL) chatGptMessages.add(userNotice) else codexMessages.add(userNotice)
        chatAdapter.addMessage(userNotice)

        val progressNotice = ChatMessage(role = MessageRole.ASSISTANT, content = "⏳ Descargando e instalando skill desde GitHub: `" + urlOrId + "`...")
        if (targetMode == AppMode.CHATGPT_NORMAL) chatGptMessages.add(progressNotice) else codexMessages.add(progressNotice)
        chatAdapter.addMessage(progressNotice)
        binding.rvMessages.scrollToPosition(messages.size - 1)

        thread {
            val (ok, message, skillId) = skillsRepo.installSkillFromUrl(urlOrId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    val finalMsg = "✅ **¡Skill instalada exitosamente con el comando /!**\n\n" + message + "\n\n*Ya está guardada en la memoria local del APK y activa para este chat.*"
                    if (currentMode == targetMode) {
                        chatAdapter.updateLastMessage(finalMsg)
                    } else {
                        val targetList = if (targetMode == AppMode.CHATGPT_NORMAL) chatGptMessages else codexMessages
                        if (targetList.isNotEmpty()) {
                            targetList[targetList.size - 1] = targetList.last().copy(content = finalMsg, isStreaming = false)
                        }
                    }
                    val installedSkill = if (skillId != null) skillsRepo.getSkillById(skillId) else skillsRepo.getAllSkills().firstOrNull { it.isCustom }
                    if (installedSkill != null) {
                        activateSkill(installedSkill)
                    }
                } else {
                    val errorMsg = "❌ **Fallo al instalar skill desde GitHub:**\n" + message + "\n\n*Comprueba la conexión o usa una URL directa al archivo SKILL.md o formato `usuario/repositorio/skills/nombre-skill`.*"
                    if (currentMode == targetMode) {
                        chatAdapter.updateLastMessage(errorMsg)
                    } else {
                        val targetList = if (targetMode == AppMode.CHATGPT_NORMAL) chatGptMessages else codexMessages
                        if (targetList.isNotEmpty()) {
                            targetList[targetList.size - 1] = targetList.last().copy(content = errorMsg, isStreaming = false)
                        }
                    }
                }
                if (currentMode == targetMode) {
                    binding.rvMessages.scrollToPosition(messages.size - 1)
                }
            }
        }
    }

    private fun showInstallSkillDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            hint = "https://github.com/.../SKILL.md o anthropics/skills/skills/webapp-testing"
            setTextColor(Color.parseColor("#ECECEC"))
            setHintTextColor(Color.parseColor("#666666"))
            setBackgroundColor(Color.parseColor("#292929"))
            setPadding(pad, pad, pad, pad)
            textSize = 13f
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("📥 Instalar Skill con comando /")
            .setMessage("Introduce la URL de GitHub o el identificador de la skill (ej. 'anthropics/skills/skills/webapp-testing'):")
            .setView(input)
            .setPositiveButton("Instalar") { _, _ ->
                val target = input.text.toString().trim()
                if (target.isNotEmpty()) {
                    executeInstallSkillCommand(target)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showMcpManagerBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_mcp_manager, null)
        dialog.setContentView(view)

        val rvServers = view.findViewById<RecyclerView>(R.id.rvMcpServers)
        val tvTotalBadge = view.findViewById<TextView>(R.id.tvTotalMcpToolsBadge)
        val btnAddRemote = view.findViewById<TextView>(R.id.btnAddRemoteMcpServer)
        val btnClose = view.findViewById<TextView>(R.id.btnCloseMcpSheet)

        fun updateBadge() {
            val activeTools = mcpRegistry.getAllActiveTools().size
            tvTotalBadge.text = "$activeTools Tools Activas"
        }
        updateBadge()

        val serversList = mcpRegistry.getServers().toMutableList()
        val adapter = McpServersAdapter(
            servers = serversList,
            onToggle = { server, enabled ->
                mcpRegistry.setServerEnabled(server.id, enabled)
                updateBadge()
            },
            onViewTools = { server ->
                showMcpServerToolsDialog(server)
            }
        )
        rvServers.layoutManager = LinearLayoutManager(this)
        rvServers.adapter = adapter

        val btnOpenCatalog = view.findViewById<TextView>(R.id.btnOpenMcpCatalog)
        btnOpenCatalog.setOnClickListener {
            dialog.dismiss()
            showOfficialMcpStoreDialog()
        }

        btnAddRemote.setOnClickListener {
            showAddRemoteMcpServerDialog {
                serversList.clear()
                serversList.addAll(mcpRegistry.getServers())
                adapter.updateData(serversList)
                updateBadge()
            }
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showMcpServerToolsDialog(server: com.codex.chat.core.mcp.model.McpServerInfo) {
        val tools = mcpRegistry.getAllActiveTools().filter { it.serverName == server.name }
        val items = tools.map { "${it.name}\n${it.description}" }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("${server.iconEmoji} ${server.name} (${tools.size} herramientas)")
            .setItems(if (items.isNotEmpty()) items else arrayOf("No hay herramientas activas en este servidor.")) { _, which ->
                if (tools.isNotEmpty()) {
                    val tool = tools[which]
                    executeMcpToolDirect(tool.name)
                }
            }
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun showOfficialMcpStoreDialog() {
        val catalog = mcpRegistry.getOfficialCatalog()
        val items = catalog.map { s ->
            "${s.iconEmoji} ${s.name} (${s.category})\n${s.description}\n⚡ ${s.tools.size} herramientas (${s.author})"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("🧭 Tienda Oficial de Servidores MCP")
            .setItems(items) { _, which ->
                val selected = catalog[which]
                showOfficialMcpServerDetails(selected)
            }
            .setPositiveButton("Cerrar") { _, _ ->
                showMcpManagerBottomSheet()
            }
            .show()
    }

    private fun showOfficialMcpServerDetails(server: com.codex.chat.core.mcp.model.OfficialMcpServerInfo) {
        val toolsText = server.tools.joinToString("\n") { "• ${it.name}: ${it.description}" }
        val detailMsg = "${server.description}\n\n" +
                "👤 Autor: ${server.author}\n" +
                "📁 Categoría: ${server.category}\n" +
                "🔗 Endpoint por defecto: ${server.defaultUrl}\n\n" +
                "🛠️ Herramientas disponibles:\n$toolsText"

        MaterialAlertDialogBuilder(this)
            .setTitle("${server.iconEmoji} ${server.name}")
            .setMessage(detailMsg)
            .setPositiveButton("Conectar Servidor") { _, _ ->
                mcpRegistry.addRemoteServer(server.name, server.defaultUrl)
                Toast.makeText(this, "Servidor ${server.name} configurado en tu app", Toast.LENGTH_SHORT).show()
                showMcpManagerBottomSheet()
            }
            .setNegativeButton("Volver") { _, _ ->
                showOfficialMcpStoreDialog()
            }
            .show()
    }

    private fun showAddRemoteMcpServerDialog(onAdded: () -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val etName = EditText(this).apply {
            hint = "Nombre del Servidor (ej. 'PC Codex MCP')"
            setTextColor(Color.parseColor("#ECECEC"))
            setHintTextColor(Color.parseColor("#666666"))
            setBackgroundColor(Color.parseColor("#292929"))
            setPadding(24, 20, 24, 20)
            textSize = 13f
        }

        val etUrl = EditText(this).apply {
            hint = "URL endpoint (ej. 'http://192.168.1.50:22345/mcp')"
            setTextColor(Color.parseColor("#ECECEC"))
            setHintTextColor(Color.parseColor("#666666"))
            setBackgroundColor(Color.parseColor("#292929"))
            setPadding(24, 20, 24, 20)
            textSize = 13f
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.topMargin = (10 * resources.displayMetrics.density).toInt()
            layoutParams = params
        }

        layout.addView(etName)
        layout.addView(etUrl)

        MaterialAlertDialogBuilder(this)
            .setTitle("🔌 Conectar Servidor MCP Remoto")
            .setMessage("Introduce el nombre y la dirección HTTP del servidor MCP (en tu red local o PC):")
            .setView(layout)
            .setPositiveButton("Conectar") { _, _ ->
                val name = etName.text.toString().trim().ifEmpty { "Servidor MCP Remoto" }
                val url = etUrl.text.toString().trim()
                if (url.isNotEmpty()) {
                    mcpRegistry.addRemoteServer(name, url)
                    Toast.makeText(this, "Servidor MCP añadido: $name", Toast.LENGTH_SHORT).show()
                    onAdded()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun executeMcpToolDirect(callSpec: String) {
        val trimmed = callSpec.trim()
        val parts = trimmed.split("\\s+".toRegex(), limit = 2)
        val toolName = parts[0]
        val argsJson = if (parts.size > 1) parts[1].trim() else "{}"

        val userNotice = ChatMessage(role = MessageRole.USER, content = "🔧 /mcp call $toolName" + (if (argsJson != "{}") " $argsJson" else ""))
        if (currentMode == AppMode.CHATGPT_NORMAL) chatGptMessages.add(userNotice) else codexMessages.add(userNotice)
        chatAdapter.addMessage(userNotice)

        val progressNotice = ChatMessage(role = MessageRole.ASSISTANT, content = "⚙️ Ejecutando herramienta MCP `$toolName` en el dispositivo...")
        if (currentMode == AppMode.CHATGPT_NORMAL) chatGptMessages.add(progressNotice) else codexMessages.add(progressNotice)
        chatAdapter.addMessage(progressNotice)
        binding.rvMessages.scrollToPosition(messages.size - 1)

        thread {
            val result = mcpRegistry.executeTool(toolName, argsJson)
            runOnUiThread {
                val icon = if (result.isError) "❌" else "✅"
                val finalMsg = "$icon **Resultado MCP: `${result.toolName}`**\n\n```json\n${result.content}\n```"
                chatAdapter.updateLastMessage(finalMsg)
                binding.rvMessages.scrollToPosition(messages.size - 1)
            }
        }
    }

    private fun printOfficialMcpStoreToChat() {
        val catalog = mcpRegistry.getOfficialCatalog()
        val sb = StringBuilder("### 🏪 Tienda Oficial de Servidores MCP (Ecosistema Claude & Anthropic)\n")
        sb.append("*Explora y conecta servidores MCP para dotar al modelo de herramientas de bases de datos, APIs y automatización en el chat:*\n\n")

        for ((index, s) in catalog.withIndex()) {
            sb.append("${index + 1}. ${s.iconEmoji} **${s.name}** (`${s.id}`)\n")
            sb.append("   • **Autor:** ${s.author} | **Categoría:** ${s.category}\n")
            sb.append("   • **Descripción:** ${s.description}\n")
            val toolNames = s.tools.joinToString(", ") { "`${it.name}`" }
            sb.append("   • **Herramientas (${s.tools.size}):** $toolNames\n")
            sb.append("   • 📥 **Instalar:** `/mcp install ${s.id}`\n\n")
        }
        sb.append("💡 *Tip: Puedes instalar cualquier servidor escribiendo `/mcp install <id>` o abriendo la interfaz visual con `/mcp`.*")

        val msg = ChatMessage(role = MessageRole.ASSISTANT, content = sb.toString())
        if (currentMode == AppMode.CHATGPT_NORMAL) chatGptMessages.add(msg) else codexMessages.add(msg)
        chatAdapter.addMessage(msg)
        binding.rvMessages.scrollToPosition(messages.size - 1)
    }

    private fun executeInstallMcpServer(targetId: String) {
        val catalog = mcpRegistry.getOfficialCatalog()
        val target = catalog.find {
            it.id.equals(targetId, ignoreCase = true) ||
            it.id.removePrefix("anthropic-").equals(targetId, ignoreCase = true) ||
            it.name.replace(" ", "-").equals(targetId, ignoreCase = true)
        }

        if (target != null) {
            mcpRegistry.addRemoteServer(target.name, target.defaultUrl)
            val msg = ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "✅ **Servidor MCP Conectado con Éxito:** ${target.iconEmoji} `${target.name}`\n\n" +
                        "• **Endpoint:** `${target.defaultUrl}`\n" +
                        "• **Categoría:** ${target.category} (${target.author})\n" +
                        "• **Nuevas Herramientas (${target.tools.size}):** " +
                        target.tools.joinToString(", ") { "`${it.name}`" } + "\n\n" +
                        "*Las herramientas ya están activas y registradas para tus conversaciones.*"
            )
            if (currentMode == AppMode.CHATGPT_NORMAL) chatGptMessages.add(msg) else codexMessages.add(msg)
            chatAdapter.addMessage(msg)
            binding.rvMessages.scrollToPosition(messages.size - 1)
        } else {
            val msg = ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "⚠️ **Servidor MCP no encontrado:** `$targetId`\n\n" +
                        "Usa `/mcp store` para ver la lista completa de servidores MCP oficiales disponibles."
            )
            if (currentMode == AppMode.CHATGPT_NORMAL) chatGptMessages.add(msg) else codexMessages.add(msg)
            chatAdapter.addMessage(msg)
            binding.rvMessages.scrollToPosition(messages.size - 1)
        }
    }

    private fun showPermissionsStatusAndRequest() {
        val hasAllFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { Environment.isExternalStorageManager() } catch (e: Throwable) { false }
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasFineLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val hasCalendar = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val hasCalls = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val canWriteSettings = Settings.System.canWrite(this)

        val sb = StringBuilder("### 🛡️ Catálogo Total de Permisos del APK (Nivel sin Root):\n\n")
        sb.append(if (hasAllFiles) "✅" else "❌").append(" **Almacenamiento Total (`MANAGE_EXTERNAL_STORAGE`):** ").append(if (hasAllFiles) "Concedido (Moverse y crear en Download/Documents)" else "Pendiente").append("\n")
        sb.append(if (hasAudio) "✅" else "❌").append(" **Micrófono y Dictado (`RECORD_AUDIO`):** ").append(if (hasAudio) "Concedido" else "Pendiente").append("\n")
        sb.append(if (hasCamera) "✅" else "❌").append(" **Cámara y Linterna (`CAMERA` / `FLASHLIGHT`):** ").append(if (hasCamera) "Concedido" else "Pendiente").append("\n")
        sb.append(if (hasFineLoc) "✅" else "❌").append(" **Ubicación GPS (`ACCESS_FINE_LOCATION`):** ").append(if (hasFineLoc) "Concedido" else "Pendiente").append("\n")
        sb.append(if (hasContacts) "✅" else "❌").append(" **Contactos y Agenda (`READ_CONTACTS`):** ").append(if (hasContacts) "Concedido" else "Pendiente").append("\n")
        sb.append(if (hasCalendar) "✅" else "❌").append(" **Google Calendar (`READ_CALENDAR`):** ").append(if (hasCalendar) "Concedido" else "Pendiente").append("\n")
        sb.append(if (hasSms) "✅" else "❌").append(" **Mensajes SMS (`READ_SMS` / `SEND_SMS`):** ").append(if (hasSms) "Concedido" else "Pendiente").append("\n")
        sb.append(if (hasCalls) "✅" else "❌").append(" **Historial de Llamadas (`READ_CALL_LOG`):** ").append(if (hasCalls) "Concedido" else "Pendiente").append("\n")
        sb.append(if (hasNotifications) "✅" else "❌").append(" **Notificaciones del Sistema (`POST_NOTIFICATIONS`):** ").append(if (hasNotifications) "Concedido" else "Pendiente").append("\n")
        sb.append(if (canWriteSettings) "✅" else "❌").append(" **Modificar Ajustes/Brillo (`WRITE_SETTINGS`):** ").append(if (canWriteSettings) "Concedido" else "Pendiente").append("\n\n")

        val allGranted = hasAllFiles && hasAudio && hasCamera && hasFineLoc && hasContacts && hasCalendar && hasSms && hasCalls && hasNotifications
        if (allGranted) {
            sb.append("✨ **¡Todos los permisos del sistema están concedidos!** El APK tiene control autónomo completo sin requerir root.")
        } else {
            sb.append("⚡ *Se abrirán los cuadros de diálogo oficiales de Android para conceder los permisos pendientes.*")
        }

        val msg = ChatMessage(role = MessageRole.ASSISTANT, content = sb.toString())
        if (currentMode == AppMode.CHATGPT_NORMAL) chatGptMessages.add(msg) else codexMessages.add(msg)
        chatAdapter.addMessage(msg)
        binding.rvMessages.scrollToPosition(messages.size - 1)

        requestMissingPermissions(hasAllFiles)
    }

    private fun requestMissingPermissions(hasAllFiles: Boolean) {
        val neededPerms = mutableListOf<String>()
        val checkAdd = { perm: String ->
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                neededPerms.add(perm)
            }
        }

        checkAdd(Manifest.permission.RECORD_AUDIO)
        checkAdd(Manifest.permission.CAMERA)
        checkAdd(Manifest.permission.ACCESS_FINE_LOCATION)
        checkAdd(Manifest.permission.ACCESS_COARSE_LOCATION)
        checkAdd(Manifest.permission.READ_CONTACTS)
        checkAdd(Manifest.permission.WRITE_CONTACTS)
        checkAdd(Manifest.permission.READ_CALENDAR)
        checkAdd(Manifest.permission.WRITE_CALENDAR)
        checkAdd(Manifest.permission.READ_SMS)
        checkAdd(Manifest.permission.SEND_SMS)
        checkAdd(Manifest.permission.READ_CALL_LOG)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkAdd(Manifest.permission.POST_NOTIFICATIONS)
            checkAdd(Manifest.permission.READ_MEDIA_IMAGES)
            checkAdd(Manifest.permission.READ_MEDIA_VIDEO)
            checkAdd(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            checkAdd(Manifest.permission.READ_EXTERNAL_STORAGE)
            checkAdd(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (neededPerms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPerms.toTypedArray(), 1001)
        }

        if (!hasAllFiles && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:" + packageName)
                }
                startActivity(intent)
                Toast.makeText(this, "Activa 'Permitir administrar todos los archivos' para Codex ChatGPT", Toast.LENGTH_LONG).show()
            } catch (e: Throwable) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                } catch (ex: Throwable) {
                    // Fallback
                }
            }
        }
    }

    private fun printMcpToolsList() {
        val tools = mcpRegistry.getAllActiveTools()
        val sb = StringBuilder("### 🔌 Herramientas MCP Nativas Disponibles (${tools.size}):\n\n")
        for (t in tools) {
            sb.append("* **`${t.name}`**: ${t.description} *(Servidor: ${t.serverName})*\n")
        }
        sb.append("\n*Usa `/mcp call <nombre_herramienta>` para ejecutar directamente.*")
        val msg = ChatMessage(role = MessageRole.ASSISTANT, content = sb.toString())
        if (currentMode == AppMode.CHATGPT_NORMAL) chatGptMessages.add(msg) else codexMessages.add(msg)
        chatAdapter.addMessage(msg)
        binding.rvMessages.scrollToPosition(messages.size - 1)
    }

    private fun showSlashHelpNotice() {
        val helpText = "### 🧭 Comandos con Barra Diagonal (/) Disponibles:\n" +
                "* **/mcp**: Administrador de Servidores MCP Nativos y Remotos.\n" +
                "* **/mcp tools**: Muestra la lista completa de herramientas MCP nativas.\n" +
                "* **/battery**: Consulta en tiempo real la batería y temperatura del teléfono.\n" +
                "* **/device**: Consulta la telemetría de hardware, RAM y Android del móvil.\n" +
                "* **/memory**: Accede o guarda hechos en la memoria persistente del teléfono.\n" +
                "* **/calc <expr>**: Evalúa expresiones matemáticas con el motor de cálculo MCP.\n" +
                "* **/skills**: Abre la Tienda Oficial de Skills con catálogo de 60+ habilidades.\n" +
                "* **/install <url-o-repo>**: Instala directamente cualquier skill desde GitHub.\n" +
                "* **/unskill**: Desactiva la skill actual y regresa a ChatGPT estándar.\n" +
                "* **/clear**: Limpia la sesión de conversación actual.\n" +
                "* **/tdd, /debugging, /docker, /owasp, /caveman**: Activa skills por su nombre corto."
        val msg = ChatMessage(role = MessageRole.ASSISTANT, content = helpText)
        if (currentMode == AppMode.CHATGPT_NORMAL) chatGptMessages.add(msg) else codexMessages.add(msg)
        chatAdapter.addMessage(msg)
        binding.rvMessages.scrollToPosition(messages.size - 1)
    }
    private fun showRootStatusOrExecute(arg: String) {
        thread {
            val sb = java.lang.StringBuilder()
            if (arg.isBlank()) {
                val isSuPresent = com.codex.chat.core.root.RootShellExecutor.isSuBinaryPresent()
                val rootRes = com.codex.chat.core.root.RootShellExecutor.checkRootAccess()
                sb.append("### ⚡ Estado del Motor Root / Superusuario\n\n")
                if (rootRes.isRooted) {
                    sb.append("🟢 **Dispositivo Rooteado y Operativo:**\n")
                    sb.append("* **Permisos:** Acceso de superusuario concedido (`uid=0`).\n")
                    sb.append("* **Shell de Root:** Activo y listo para comandos.\n")
                    sb.append("* **Herramientas MCP:** `mcp-android-root` disponible para el modelo de IA.\n")
                    sb.append("* **Salida id:** `").append(rootRes.stdout).append("`\n\n")
                    sb.append("💡 *Puedes ejecutar comandos root escribiendo `/root <comando>` o pedirle al chat que los ejecute.*")
                } else if (isSuPresent) {
                    sb.append("🟡 **Binario 'su' Detectado (Pendiente de Autorización):**\n")
                    sb.append("* El binario de superusuario existe en el sistema, pero la aplicación no ha recibido autorización todavía.\n")
                    sb.append("* Abre tu gestor de root (**Magisk**, **KernelSU** o **APatch**) y concede acceso de superusuario a **Codex ChatGPT**.\n")
                    sb.append("* Mensaje: `").append(rootRes.stderr).append("`")
                } else {
                    sb.append("⚪ **Dispositivo Actualmente No Rooteado:**\n")
                    sb.append("* No se encontró el binario `su` en las rutas del sistema.\n")
                    sb.append("* **Preparación Total:** El APK ya cuenta con los 136 permisos del sistema y el servidor MCP nativo `mcp-android-root` compilados.\n")
                    sb.append("* **Compatibilidad Futura:** Si en algún momento rooteas este celular (con Magisk, KernelSU o APatch), el motor se activará automáticamente sin requerir reinstalar ni reconfigurar nada.\n")
                }
            } else {
                sb.append("### ⚡ Ejecución Root: `").append(arg).append("`\n\n")
                val res = com.codex.chat.core.root.RootShellExecutor.executeSu(arg)
                if (res.success) {
                    sb.append("✅ **Comando completado (exit 0):**\n```text\n")
                    sb.append(if (res.stdout.isEmpty()) "(Sin salida)" else res.stdout)
                    sb.append("\n```")
                } else {
                    sb.append("❌ **Error ejecutando comando (exit ").append(res.exitCode).append("):**\n")
                    if (res.stderr.isNotEmpty()) sb.append("```text\n").append(res.stderr).append("\n```\n")
                    if (res.stdout.isNotEmpty()) sb.append("Salida:\n```text\n").append(res.stdout).append("\n```")
                }
            }

            runOnUiThread {
                val msg = ChatMessage(role = MessageRole.ASSISTANT, content = sb.toString())
                if (currentMode == AppMode.CHATGPT_NORMAL) chatGptMessages.add(msg) else codexMessages.add(msg)
                chatAdapter.addMessage(msg)
                binding.rvMessages.scrollToPosition(messages.size - 1)
            }
        }
    }

    private fun showCodexActionsBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_actions, null)
        dialog.setContentView(view)

        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val tvSheetCwd = view.findViewById<TextView>(R.id.tvSheetActiveCwd)
        val tvCwdSub = view.findViewById<TextView>(R.id.tvCwdSubtext)
        tvSheetCwd.text = "📁 " + (activeCwd.split("\\", "/").lastOrNull() ?: activeCwd)
        tvCwdSub.text = activeCwd

        // CWD Picker
        view.findViewById<View>(R.id.actionChangeCwd).setOnClickListener {
            dialog.dismiss()
            showWorkspacePicker()
        }

        // Tienda de Skills (Claude & Codex)
        val actionCodexSkill = view.findViewById<View>(R.id.actionCodexSkillStore)
        val tvActiveSkillSub = view.findViewById<TextView>(R.id.tvActiveSkillSubtext)
        if (activeSkill != null) {
            tvActiveSkillSub.text = "⚡ Activa: " + activeSkill?.name
        } else {
            tvActiveSkillSub.text = "Habilidades nativas activas o disponibles"
        }
        actionCodexSkill.setOnClickListener {
            dialog.dismiss()
            showSkillStoreBottomSheet()
        }

        // Servidores MCP Nativos
        val actionMcp = view.findViewById<View>(R.id.actionMcpManager)
        val tvMcpSub = view.findViewById<TextView>(R.id.tvMcpActionsSubtext)
        val activeToolsCount = mcpRegistry.getAllActiveTools().size
        tvMcpSub.text = "$activeToolsCount herramientas activas (Batería, Memoria, Archivos, Red)"
        actionMcp.setOnClickListener {
            dialog.dismiss()
            showMcpManagerBottomSheet()
        }

        // Sandbox Policies
        view.findViewById<View>(R.id.actionPermDanger).setOnClickListener {
            activeSandboxPolicy = "danger-full-access"
            updateContextBarDisplay()
            dialog.dismiss()
            Toast.makeText(this, "Permiso: Acceso Total a Terminal y Archivos", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<View>(R.id.actionPermWorkspace).setOnClickListener {
            activeSandboxPolicy = "workspace-write"
            updateContextBarDisplay()
            dialog.dismiss()
            Toast.makeText(this, "Permiso: Solo Espacio de Trabajo", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<View>(R.id.actionPermReadonly).setOnClickListener {
            activeSandboxPolicy = "read-only"
            updateContextBarDisplay()
            dialog.dismiss()
            Toast.makeText(this, "Permiso: Solo Lectura", Toast.LENGTH_SHORT).show()
        }

        // Attachments
        view.findViewById<View>(R.id.actionAttachDoc).setOnClickListener {
            dialog.dismiss()
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/json", "application/javascript", "application/python", "application/pdf"))
            }
            documentPickerLauncher.launch(intent)
        }

        view.findViewById<View>(R.id.actionAttachImage).setOnClickListener {
            dialog.dismiss()
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
            }
            imagePickerLauncher.launch(intent)
        }

        // Web Search
        view.findViewById<View>(R.id.actionWebSearch).setOnClickListener {
            dialog.dismiss()
            setWebSearchActive(!isWebSearchActive)
        }

        // Codex Superpower Skills
        view.findViewById<View>(R.id.actionSkillTdd).setOnClickListener {
            dialog.dismiss()
            insertSkillPrompt("🧪 [Skill: Test-Driven Development (TDD)]")
        }

        view.findViewById<View>(R.id.actionSkillDebugging).setOnClickListener {
            dialog.dismiss()
            insertSkillPrompt("🔍 [Skill: Systematic Debugging]")
        }

        view.findViewById<View>(R.id.actionSkillPlans).setOnClickListener {
            dialog.dismiss()
            insertSkillPrompt("🏛️ [Skill: Architecture Plans]")
        }

        view.findViewById<View>(R.id.actionSkillParallel).setOnClickListener {
            dialog.dismiss()
            insertSkillPrompt("🚀 [Skill: Dispatching Parallel Agents]")
        }

        view.findViewById<View>(R.id.actionSkillVerification).setOnClickListener {
            dialog.dismiss()
            insertSkillPrompt("🛡️ [Skill: Verification Before Completion]")
        }

        dialog.show()
    }

    private fun insertSkillPrompt(tag: String) {
        val cur = binding.etMessage.text.toString().trim()
        val textToSet = if (cur.isEmpty()) "$tag: " else "$tag: $cur"
        binding.etMessage.setText(textToSet)
        binding.etMessage.setSelection(binding.etMessage.text.length)
        binding.etMessage.requestFocus()
    }

    private fun showWorkspacePicker() {
        thread {
            try {
                val url = getCodexServerBaseUrl() + "/api/workspaces"
                val req = Request.Builder().url(url).get().build()
                val resp = okHttpClient.newCall(req).execute()
                val json = JSONObject(resp.body?.string() ?: "{}")
                val wsArray = json.optJSONArray("workspaces") ?: JSONArray()
                
                val workspaces = mutableListOf<String>()
                for (i in 0 until wsArray.length()) {
                    workspaces.add(wsArray.getString(i))
                }

                runOnUiThread {
                    if (workspaces.isEmpty() && activeCwd.isNotEmpty()) {
                        workspaces.add(activeCwd)
                    }

                    val items = workspaces.map { path ->
                        val name = path.split("\\", "/").lastOrNull() ?: path
                        "📁 $name ($path)"
                    }.toTypedArray()

                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Selecciona Carpeta de Trabajo en PC")
                        .setItems(items) { _, which ->
                            activeCwd = workspaces[which]
                            updateContextBarDisplay()
                            Toast.makeText(this@MainActivity, "Carpeta: " + activeCwd, Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error obteniendo carpetas del PC: " + e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadRemoteConversations() {
        thread {
            try {
                val url = getCodexServerBaseUrl() + "/api/conversations"
                val req = Request.Builder().url(url).get().build()
                val resp = okHttpClient.newCall(req).execute()
                val json = JSONObject(resp.body?.string() ?: "{}")
                val convs = json.optJSONArray("conversations") ?: JSONArray()

                val list = mutableListOf<RemoteConversation>()
                for (i in 0 until convs.length()) {
                    val obj = convs.getJSONObject(i)
                    list.add(
                        RemoteConversation(
                            threadId = obj.optString("thread_id"),
                            title = obj.optString("title", "Conversación"),
                            dateFormatted = obj.optString("date_formatted", ""),
                            cwd = obj.optString("cwd", ""),
                            provider = obj.optString("provider", "codex")
                        )
                    )
                }

                runOnUiThread {
                    drawerAdapter.updateData(list, activeThreadId)
                    binding.tvServerDot.text = "● Online (" + list.size + ")"
                    binding.tvServerDot.setTextColor(Color.parseColor("#10A37F"))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvServerDot.text = "○ Offline"
                    binding.tvServerDot.setTextColor(Color.parseColor("#EF4444"))
                }
            }
        }
    }

    private fun loadConversationThread(conv: RemoteConversation) {
        activeThreadId = conv.threadId
        if (conv.cwd.isNotEmpty()) {
            activeCwd = conv.cwd
        }
        updateContextBarDisplay()

        activeCall?.cancel()
        activeCall = null
        chatAdapter.setMessages(emptyList())

        val loadingNotice = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "Cargando mensajes de: **" + conv.title + "**…"
        )
        chatAdapter.addMessage(loadingNotice)

        thread {
            try {
                val url = getCodexServerBaseUrl() + "/api/conversations/" + conv.threadId
                val req = Request.Builder().url(url).get().build()
                val resp = okHttpClient.newCall(req).execute()
                val json = JSONObject(resp.body?.string() ?: "{}")
                val msgArray = json.optJSONArray("messages") ?: JSONArray()

                val loadedMessages = mutableListOf<ChatMessage>()
                for (i in 0 until msgArray.length()) {
                    val m = msgArray.getJSONObject(i)
                    val role = if (m.optString("role") == "user") MessageRole.USER else MessageRole.ASSISTANT
                    val text = m.optString("text")
                    loadedMessages.add(ChatMessage(role = role, content = text))
                }

                runOnUiThread {
                    val finalLoaded = if (loadedMessages.isEmpty()) {
                        listOf(ChatMessage(role = MessageRole.ASSISTANT, content = "Conversación iniciada sin mensajes previos aún."))
                    } else {
                        loadedMessages
                    }
                    chatAdapter.setMessages(finalLoaded)
                    binding.rvMessages.scrollToPosition(messages.size - 1)
                    Toast.makeText(this@MainActivity, "Cargada: " + conv.title, Toast.LENGTH_SHORT).show()

                    // Automatically attach real-time live streaming if this conversation is currently generating in Codex PC!
                    attachLiveCodexListenerIfActive(conv.threadId)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    chatAdapter.updateLastMessage("⚠️ Error cargando mensajes del PC: " + e.message)
                }
            }
        }
    }

    private fun fetchRemoteConfig() {
        thread {
            try {
                val url = getCodexServerBaseUrl() + "/api/config"
                val req = Request.Builder().url(url).get().build()
                val resp = okHttpClient.newCall(req).execute()
                val json = JSONObject(resp.body?.string() ?: "{}")
                val cwd = json.optString("active_cwd")
                val pol = json.optString("sandbox_policy")
                val app = json.optString("approval_policy")

                runOnUiThread {
                    if (cwd.isNotEmpty()) activeCwd = cwd
                    if (pol.isNotEmpty()) activeSandboxPolicy = pol
                    if (app.isNotEmpty()) activeApprovalPolicy = app
                    updateContextBarDisplay()
                }
            } catch (e: Exception) {
                // Ignore config fetch error
            }
        }
    }

    private fun getCodexProxyBaseUrl(): String {
        val p3 = BuiltInProviders.PROFILE_3_CODEX_PC
        return try {
            val uri = Uri.parse(p3.baseUrl)
            val scheme = uri.scheme ?: "http"
            val authority = uri.authority ?: "192.168.1.6:8317"
            "$scheme://$authority"
        } catch (e: Exception) {
            "http://192.168.1.6:8317"
        }
    }

    private fun getCodexServerBaseUrl(): String {
        return try {
            val uri = Uri.parse(settings.baseUrl)
            val host = uri.host ?: "127.0.0.1"
            val scheme = uri.scheme ?: "http"
            "$scheme://$host:8318"
        } catch (e: Exception) {
            "http://127.0.0.1:8318"
        }
    }

    private fun syncLiveModels() {
        thread {
            if (currentMode == AppMode.CODEX_PC) {
                modelsRepo.fetchNativeCodexModels(getCodexServerBaseUrl())
            } else {
                modelsRepo.fetchLiveModels(settings.baseUrl, settings.apiKey)
            }
            runOnUiThread {
                updateHeaderBadges()
            }
        }
    }

    private fun handleFileUri(uri: Uri, isImage: Boolean) {
        try {
            var fileName = "adjunto"
            var fileSize: Long = -1L
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex != -1) fileName = it.getString(nameIndex)
                    if (sizeIndex != -1) fileSize = it.getLong(sizeIndex)
                }
            }

            // FASE 5: AttachmentGuard validación previa de seguridad
            if (!AttachmentGuard.permitido(fileSize)) {
                val motivo = AttachmentGuard.motivoRechazo(fileSize) ?: "Archivo rechazado por tamaño."
                Toast.makeText(this, motivo, Toast.LENGTH_LONG).show()
                return
            }

            val mimeType = contentResolver.getType(uri) ?: if (isImage) "image/png" else "text/plain"

            // Procesar lectura y Base64 en hilo de fondo para evitar congelamiento de UI / ANR
            thread {
                try {
                    val inputStream: InputStream? = contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes() ?: ByteArray(0)
                    val base64 = java.util.Base64.getEncoder().encodeToString(bytes)

                    runOnUiThread {
                        pendingAttachment = Attachment(
                            id = UUID.randomUUID().toString(),
                            fileName = fileName,
                            mimeType = mimeType,
                            sizeBytes = fileSize,
                            base64Data = base64,
                            fileUri = uri.toString()
                        )

                        binding.tvAttachmentIcon.text = if (isImage) "🖼️" else "📄"
                        binding.tvAttachmentName.text = fileName + " (" + (fileSize / 1024) + " KB)"
                        Motion.slideUpFadeIn(binding.attachmentPreviewBar)
                        binding.btnMic.visibility = View.GONE
                        binding.btnSend.visibility = View.VISIBLE
                        binding.btnSend.alpha = 1.0f

                        Toast.makeText(this, "Adjuntado: $fileName", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Error leyendo archivo: " + e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error seleccionando archivo: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty() && pendingAttachment == null) return

        // Intercept slash commands (/ Claude Style)
        if (text.startsWith("/")) {
            Motion.slideDownFadeOut(binding.slashSuggestionsContainer)
            if (handleSlashCommand(text)) {
                binding.etMessage.setText("")
                val hasText = !binding.etMessage.text.isNullOrBlank()
                binding.btnSend.visibility = if (hasText) View.VISIBLE else View.GONE
                binding.btnMic.visibility = if (hasText) View.GONE else View.VISIBLE
                return
            }
        }

        val attachmentsList = mutableListOf<Attachment>()
        pendingAttachment?.let { attachmentsList.add(it) }

        activeCall?.cancel()
        activeCall = null

        val userMsg = ChatMessage(
            role = MessageRole.USER,
            content = text,
            attachments = attachmentsList
        )
        if (currentMode == AppMode.CHATGPT_NORMAL) {
            chatGptMessages.add(userMsg)
        } else {
            codexMessages.add(userMsg)
        }
        chatAdapter.addMessage(userMsg)

        val wasWebSearch = isWebSearchActive
        val wasPython = isPythonModeActive

        setWebSearchActive(false)
        setPythonModeActive(false)
        pendingAttachment = null
        Motion.slideDownFadeOut(binding.attachmentPreviewBar)
        binding.etMessage.setText("")
        binding.btnSend.visibility = View.GONE
        binding.btnMic.visibility = View.VISIBLE

        val assistantMsg = ChatMessage(role = MessageRole.ASSISTANT, content = "Pensando…", isStreaming = true)
        chatAdapter.addMessage(assistantMsg)
        scrollChatToBottom(smooth = true)

        binding.btnSend.isEnabled = false

        // 1. Python Cloud execution (Zero hardcoded text required!)
        val isPython = wasPython || text.startsWith("🐍 [Python E2B Cloud]:") || text.startsWith("🐍")
        if (isPython) {
            val cleanCode = text.removePrefix("🐍 [Python E2B Cloud]:").removePrefix("🐍").trim()
            executeCloudPython(cleanCode)
            return
        }

        // 2. In Codex PC Mode: execute directly on PC Codex Desktop with REAL-TIME streaming!
        if (currentMode == AppMode.CODEX_PC) {
            sendCodexPcMessage(text)
            return
        }

        // 3. Temporal inquiries (date/time/day): device clock is 100% authoritative, instant & zero-latency
        if (MobileWebSearchClient.isTemporalDateQuery(text)) {
            executeStreamWithContext(text, "")
            return
        }

        // 4. Direct Mobile Web Search (Zero hardcoded text required!)
        val isWebSearch = wasWebSearch || text.startsWith("🌐 [Búsqueda Web]:") || text.startsWith("🌐") || isWebSearchQuery(text)
        if (isWebSearch) {
            val cleanQuery = text.removePrefix("🌐 [Búsqueda Web]:").removePrefix("🌐").trim()
            chatAdapter.updateLastMessage("🌐 Buscando en internet en vivo: '$cleanQuery'…")
            thread {
                val results = mobileWebSearchClient.search(cleanQuery, maxResults = 4)
                var webGrounding = if (results.isNotEmpty()) {
                    mobileWebSearchClient.formatGroundingContext(cleanQuery, results)
                } else {
                    ""
                }

                if (webGrounding.isBlank()) {
                    // Fallback to PC server if available and online
                    try {
                        val url = getCodexServerBaseUrl() + "/api/web_search?q=" + java.net.URLEncoder.encode(cleanQuery, "UTF-8")
                        val req = Request.Builder().url(url).get().build()
                        val resp = okHttpClient.newCall(req).execute()
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        webGrounding = json.optString("formatted_context", "")
                    } catch (e: Exception) {
                        // PC server offline or error
                    }
                }

                runOnUiThread {
                    executeStreamWithContext(cleanQuery, webGrounding)
                }
            }
            return
        }

        executeStreamWithContext(text, "")
    }

    private fun isWebSearchQuery(text: String): Boolean {
        val lower = text.lowercase().trim()
        val triggers = listOf(
            "noticias de hoy", "noticias de última hora", "noticias actuales",
            "buscar en internet", "busca en internet", "busca en la web",
            "última hora", "acontecimientos de hoy", "sucesos de hoy"
        )
        return triggers.any { lower.contains(it) }
    }

    private fun sendCodexPcMessage(text: String) {
        val requestStartTime = System.currentTimeMillis()
        chatAdapter.updateLastMessage("⚡ Conectando con Codex Nativo en PC…")
        thread {
            try {
                val streamUrl = getCodexServerBaseUrl() + "/api/codex/stream"
                val payload = JSONObject().apply {
                    put("prompt", text)
                    put("thread_id", activeThreadId ?: "")
                    put("cwd", activeCwd)
                    put("model", settings.selectedModelId)
                }
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(streamUrl).post(body).build()

                val call = okHttpClient.newCall(req)
                activeCall = call
                val resp = call.execute()

                if (!resp.isSuccessful) {
                    throw RuntimeException("Stream HTTP " + resp.code)
                }

                val source = resp.body?.byteStream() ?: throw RuntimeException("Cuerpo de respuesta vacío")
                val reader = java.io.BufferedReader(java.io.InputStreamReader(source, Charsets.UTF_8))
                val contentBuffer = StringBuilder()
                val reasoningBuffer = StringBuilder()
                var line: String?
                var streamReceivedAny = false

                while (reader.readLine().also { line = it } != null) {
                    val l = line?.trim() ?: continue
                    if (l == "data: [DONE]") break
                    if (l.startsWith("data:")) {
                        val jsonStr = l.removePrefix("data:").trim()
                        if (jsonStr.isEmpty()) continue
                        try {
                            val data = JSONObject(jsonStr)
                            val type = data.optString("type")
                            when (type) {
                                "delta" -> {
                                    val deltaText = data.optString("text", "")
                                    if (deltaText.isNotEmpty()) {
                                        streamReceivedAny = true
                                        contentBuffer.append(deltaText)
                                        runOnUiThread {
                                            chatAdapter.updateLastMessage(contentBuffer.toString(), reasoningBuffer.toString())
                                            binding.rvMessages.scrollToPosition(messages.size - 1)
                                        }
                                    }
                                }
                                "reasoning" -> {
                                    val rText = data.optString("text", "")
                                    if (rText.isNotEmpty()) {
                                        streamReceivedAny = true
                                        if (reasoningBuffer.isNotEmpty()) reasoningBuffer.append("\n\n")
                                        reasoningBuffer.append(rText)
                                        runOnUiThread {
                                            chatAdapter.updateLastMessage(contentBuffer.toString(), reasoningBuffer.toString())
                                        }
                                    }
                                }
                                "tool_call" -> {
                                    val tName = data.optString("name", "")
                                    val tArgs = data.optString("args", "")
                                    streamReceivedAny = true
                                    if (reasoningBuffer.isNotEmpty()) reasoningBuffer.append("\n\n")
                                    reasoningBuffer.append("🔧 **Herramienta Nativa Codex:** `").append(tName).append("`\n").append(tArgs)
                                    runOnUiThread {
                                        chatAdapter.updateLastMessage(contentBuffer.toString(), reasoningBuffer.toString())
                                    }
                                }
                                "done" -> {
                                    val tid = data.optString("thread_id", "")
                                    if (tid.isNotEmpty()) {
                                        activeThreadId = tid
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // ignore line parse errors
                        }
                    }
                }

                if (!streamReceivedAny) {
                    throw RuntimeException("Stream cerrado sin tokens recibidos")
                }

                val durationMs = (System.currentTimeMillis() - requestStartTime).coerceAtLeast(1L)
                val finalContent = if (contentBuffer.isNotEmpty()) contentBuffer.toString() else "Respuesta completada en PC."
                val finalReasoning = reasoningBuffer.toString()
                val estimatedTokens = com.codex.chat.core.metrics.TokenEstimator.estimateTokens(finalContent + " " + finalReasoning)
                val tps = com.codex.chat.core.metrics.TokenEstimator.calculateTps(estimatedTokens, durationMs)
                val promptEstimate = com.codex.chat.core.metrics.TokenEstimator.estimateTokens(text)
                val metrics = com.codex.chat.core.metrics.StreamMetrics(
                    durationMs = durationMs,
                    promptTokens = promptEstimate,
                    completionTokens = estimatedTokens,
                    totalTokens = promptEstimate + estimatedTokens,
                    tokensPerSecond = tps
                )

                runOnUiThread {
                    binding.btnSend.isEnabled = true
                    activeCall = null
                    chatAdapter.completeLastMessage(finalContent, finalReasoning, metrics)
                    val finalMsg = ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = finalContent,
                        reasoningContent = reasoningBuffer.toString()
                    )
                    finalMsg.applyStreamMetrics(metrics)
                    if (codexMessages.isNotEmpty() && codexMessages.last().role == MessageRole.ASSISTANT) {
                        codexMessages[codexMessages.size - 1] = finalMsg
                    } else {
                        codexMessages.add(finalMsg)
                    }
                }
            } catch (e: Exception) {
                // Fallback graceful to polling /api/send if streaming interrupted or not supported
                fallbackToPollingSend(text)
            }
        }
    }

    private fun fallbackToPollingSend(text: String) {
        runOnUiThread {
            chatAdapter.updateLastMessage("⚡ Enviando a Codex Desktop en PC (Modo Respaldo)…")
        }
        thread {
            try {
                val url = getCodexServerBaseUrl() + "/api/send"
                val payload = JSONObject().apply {
                    put("text", text)
                    put("submit", true)
                    put("thread_id", activeThreadId ?: "")
                    put("cwd", activeCwd)
                    put("sandbox_policy", activeSandboxPolicy)
                    put("model", settings.selectedModelId)
                    put("native", true)
                }
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(url).post(body).build()
                val resp = okHttpClient.newCall(req).execute()
                val respJson = JSONObject(resp.body?.string() ?: "{}")

                val success = respJson.optBoolean("success", false)
                val targetThreadId = respJson.optString("thread_id", activeThreadId ?: "active")
                val initialOffset = respJson.optLong("initial_offset", 0L)

                runOnUiThread {
                    if (!success) {
                        binding.btnSend.isEnabled = true
                        val err = respJson.optString("error", "No se pudo inyectar el comando en la ventana de Codex en PC.")
                        chatAdapter.updateLastMessage("⚠️ " + err)
                        return@runOnUiThread
                    }

                    activeThreadId = targetThreadId
                    chatAdapter.updateLastMessage("⚡ Codex Desktop en PC procesando…", "")
                    startCodexRealTimePolling(targetThreadId, initialOffset)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.btnSend.isEnabled = true
                    chatAdapter.updateLastMessage("⚠️ Error conectando con el servidor de PC: " + e.message)
                }
            }
        }
    }

    private fun attachLiveCodexListenerIfActive(threadId: String) {
        thread {
            try {
                val url = getCodexServerBaseUrl() + "/api/conversations/" + threadId + "/poll?offset=0"
                val req = Request.Builder().url(url).get().build()
                val resp = okHttpClient.newCall(req).execute()
                val data = JSONObject(resp.body?.string() ?: "{}")
                val status = data.optString("status", "")
                val fileSize = data.optLong("file_size", 0L)

                if (status == "running") {
                    runOnUiThread {
                        val assistantNotice = ChatMessage(role = MessageRole.ASSISTANT, content = "⚡ Codex Desktop está respondiendo en este momento…", isStreaming = true)
                        chatAdapter.addMessage(assistantNotice)
                        binding.rvMessages.scrollToPosition(messages.size - 1)
                        binding.btnSend.isEnabled = false
                        val startOffset = maxOf(0L, fileSize - 4096L)
                        startCodexRealTimePolling(threadId, startOffset)
                    }
                }
            } catch (e: Exception) {
                // Ignore poll check error
            }
        }
    }

    private fun startCodexRealTimePolling(threadId: String, startOffset: Long) {
        tokenPollActivo?.cancelado = true
        codexPollJob?.interrupt()

        val token = PollToken()
        tokenPollActivo = token
        codexPollJob = thread {
            var currentOffset = startOffset
            val reasoningBuffer = StringBuilder()
            val contentBuffer = StringBuilder()
            var activeStatus = "running"
            var completedCount = 0

            while (!token.cancelado && (activeStatus == "running" || completedCount < 3)) {
                try {
                    Thread.sleep(700)
                    if (token.cancelado) break

                    val url = getCodexServerBaseUrl() + "/api/conversations/" + threadId + "/poll?offset=" + currentOffset
                    val req = Request.Builder().url(url).get().build()
                    val resp = okHttpClient.newCall(req).execute()
                    val data = JSONObject(resp.body?.string() ?: "{}")

                    val newOffset = data.optLong("new_offset", currentOffset)
                    activeStatus = data.optString("status", "running")
                    val events = data.optJSONArray("events") ?: JSONArray()

                    var hasUpdates = false
                    for (i in 0 until events.length()) {
                        val ev = events.getJSONObject(i)
                        val kind = ev.optString("kind")
                        when (kind) {
                            "reasoning" -> {
                                val rText = ev.optString("text")
                                if (rText.isNotBlank()) {
                                    if (reasoningBuffer.isNotEmpty()) reasoningBuffer.append("\n\n")
                                    reasoningBuffer.append(rText)
                                    hasUpdates = true
                                }
                            }
                            "tool_call" -> {
                                val toolName = ev.optString("name")
                                val toolArgs = ev.optString("args")
                                if (reasoningBuffer.isNotEmpty()) reasoningBuffer.append("\n\n")
                                reasoningBuffer.append("🔧 **Herramienta:** `").append(toolName).append("`\n").append(toolArgs)
                                hasUpdates = true
                            }
                            "message" -> {
                                val role = ev.optString("role")
                                if (role == "assistant") {
                                    val mText = ev.optString("text")
                                    if (mText.isNotBlank()) {
                                        contentBuffer.setLength(0)
                                        contentBuffer.append(mText)
                                        hasUpdates = true
                                    }
                                }
                            }
                            "task_complete" -> {
                                activeStatus = "completed"
                            }
                        }
                    }

                    if (hasUpdates || newOffset > currentOffset) {
                        currentOffset = newOffset
                        runOnUiThread {
                            if (token.cancelado) return@runOnUiThread
                            val displayContent = if (contentBuffer.isNotEmpty()) {
                                contentBuffer.toString()
                            } else {
                                "⚡ Codex Desktop en PC procesando…"
                            }
                            chatAdapter.updateLastMessage(displayContent, reasoningBuffer.toString())
                            binding.rvMessages.scrollToPosition(messages.size - 1)
                        }
                    }

                    if (activeStatus == "completed") {
                        completedCount++
                    } else {
                        completedCount = 0
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    // Retry next cycle
                }
            }

            runOnUiThread {
                if (token.cancelado) return@runOnUiThread
                binding.btnSend.isEnabled = true
                if (contentBuffer.isNotEmpty()) {
                    chatAdapter.updateLastMessage(contentBuffer.toString(), reasoningBuffer.toString())
                }
                val finalMsg = ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = if (contentBuffer.isNotEmpty()) contentBuffer.toString() else "Respuesta completada en PC.",
                    reasoningContent = reasoningBuffer.toString()
                )
                if (codexMessages.isNotEmpty() && codexMessages.last().role == MessageRole.ASSISTANT) {
                    codexMessages[codexMessages.size - 1] = finalMsg
                } else {
                    codexMessages.add(finalMsg)
                }
            }
        }
    }

    private fun executeCloudPython(rawText: String) {
        val code = rawText.removePrefix("🐍 [Python E2B Cloud]:").removePrefix("🐍").trim()
        chatAdapter.updateLastMessage("⚡ Conectando directamente con MicroVM E2B Cloud desde el móvil…")
        thread {
            try {
                val sessionId = activeLocalSessionId ?: "mobile_chat"
                val apiKey = settings.e2bApiKey

                val result = directE2bClient.executePython(
                    apiKey = apiKey,
                    sessionId = sessionId,
                    code = code
                )

                val sb = java.lang.StringBuilder()
                sb.append("### 🐍 Salida E2B Cloud (MicroVM en la nube)\n\n")
                if (result.stdout.isNotEmpty()) {
                    sb.append("```text\n").append(result.stdout).append("\n```\n\n")
                }
                if (result.stderr.isNotEmpty()) {
                    sb.append("**Stderr:**\n```text\n").append(result.stderr).append("\n```\n\n")
                }
                if (result.error != null && result.error.isNotEmpty()) {
                    sb.append("⚠️ **Error:**\n```text\n").append(result.error).append("\n```\n\n")
                }
                if (result.stdout.isEmpty() && result.stderr.isEmpty() && (result.error == null || result.error.isEmpty())) {
                    sb.append("✅ Código ejecutado correctamente sin salida estándar.")
                }
                if (result.sandboxId.isNotEmpty()) {
                    sb.append("\n*Sandbox ID:* `").append(result.sandboxId).append("` *(Directo desde Android)*")
                }

                val finalOutput = sb.toString()
                runOnUiThread {
                    binding.btnSend.isEnabled = true
                    chatAdapter.updateLastMessage(finalOutput)

                    val finalMsg = ChatMessage(role = MessageRole.ASSISTANT, content = finalOutput)
                    if (currentMode == AppMode.CHATGPT_NORMAL) {
                        chatGptMessages.add(finalMsg)
                        saveLocalSessionState(rawText)
                    } else {
                        codexMessages.add(finalMsg)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.btnSend.isEnabled = true
                    chatAdapter.updateLastMessage("⚠️ Error conectando con E2B Cloud desde el móvil: " + e.message)
                }
            }
        }
    }

    private fun executeStreamWithContext(userText: String, webGrounding: String) {
        val streamBuffer = StreamBuffer()
        val isWebTainted = webGrounding.isNotBlank()
        val activeModel = modelsRepo.getModelById(settings.selectedModelId)

        // Build outgoing messages (user/assistant turns)
        val outgoingMessages = messages.dropLast(1).toMutableList()

        var currentStreamCall: Call? = null
        val streamObj = apiClient.executeStream(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = activeModel,
            effort = settings.reasoningEffort,
            messages = outgoingMessages,
            activeSubagent = activeSubagent,
            activeSkill = activeSkill,
            webGrounding = webGrounding,
            mcpRegistry = mcpRegistry,
            callback = object : CodexApiClient.StreamCallback {
                // PLUS: traducción del razonamiento al español EN TIEMPO REAL.
                // El buffer de razonamiento visible siempre queda en español; las frases en otros
                // idiomas se traducen al vuelo (frase a frase) por un modelo rápido del proxy.
                private val reasoningTranslator = com.codex.chat.core.network.ReasoningTranslator(
                    client = com.codex.chat.core.network.ReasoningTranslator.fastClient(),
                    baseUrl = settings.baseUrl, // ya incluye /v1 (igual que CodexApiClient)
                    apiKey = settings.apiKey,
                    translationModel = "glm-5.3-flash",
                    onTranslated = { spanishText ->
                        runOnUiThread {
                            streamBuffer.appendReasoning(spanishText + " ")
                            val c = streamBuffer.getContent()
                            chatAdapter.updateLastMessage(
                                if (c.isEmpty()) "Pensando…" else c,
                                streamBuffer.getReasoning()
                            )
                            scrollChatToBottom(onlyIfAtBottom = true)
                        }
                    }
                )

                override fun onReasoningDelta(delta: String) {
                    if (delta.isBlank()) return
                    reasoningTranslator.onDelta(delta)
                }

                // FIX anti-congelamiento: coalescer deltas a 1 update/120ms.
                private var lastUiUpdateAt = 0L
                private var pendingUiUpdate = false
                private val pendingUiRunnable = Runnable {
                    pendingUiUpdate = false
                    val displayContent = streamBuffer.getContent()
                    chatAdapter.updateLastMessage(
                        if (displayContent.isEmpty()) "Pensando…" else displayContent,
                        streamBuffer.getReasoning()
                    )
                    scrollChatToBottom(onlyIfAtBottom = true)
                }

                override fun onContentDelta(delta: String) {
                    if (delta.isEmpty()) return
                    streamBuffer.appendContent(delta)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastUiUpdateAt >= 120) {
                        lastUiUpdateAt = now
                        runOnUiThread {
                            binding.root.removeCallbacks(pendingUiRunnable)
                            lastUiUpdateAt = android.os.SystemClock.elapsedRealtime()
                            val displayContent = streamBuffer.getContent()
                            chatAdapter.updateLastMessage(
                                if (displayContent.isEmpty()) "Pensando…" else displayContent,
                                streamBuffer.getReasoning()
                            )
                            scrollChatToBottom(onlyIfAtBottom = true)
                        }
                    } else if (!pendingUiUpdate) {
                        pendingUiUpdate = true
                        binding.root.postDelayed(pendingUiRunnable, 120)
                    }
                }

                override fun onComplete(fullContent: String, fullReasoning: String) {
                    onCompleteWithMetrics(fullContent, fullReasoning, com.codex.chat.core.metrics.StreamMetrics())
                }

                override fun onCompleteWithMetrics(
                    fullContent: String,
                    fullReasoning: String,
                    metrics: com.codex.chat.core.metrics.StreamMetrics
                ) {
                    // Vaciar el traductor (frases parciales pendientes) antes de finalizar.
                    reasoningTranslator.flush()
                    val cleanContent = fullContent
                    // El razonamiento final mostrado/persistido es SIEMPRE la versión en español
                    // acumulada en el buffer por el traductor (o el crudo si llegó vacío).
                    val cleanReasoning = streamBuffer.getReasoning().ifBlank { fullReasoning }

                    runOnUiThread {
                        binding.root.removeCallbacks(pendingUiRunnable)
                        pendingUiUpdate = false
                        if (activeCall === currentStreamCall) {
                            activeCall = null
                            binding.btnSend.isEnabled = true
                        }
                        // Fin del stream: bind completo con métricas de rendimiento y tokens
                        chatAdapter.completeLastMessage(cleanContent, cleanReasoning, metrics)

                        val finalMsg = ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = cleanContent.ifEmpty { " " },
                            reasoningContent = cleanReasoning
                        )
                        finalMsg.applyStreamMetrics(metrics)

                        if (currentMode == AppMode.CHATGPT_NORMAL) {
                            if (chatGptMessages.isNotEmpty() && chatGptMessages.last().role == MessageRole.ASSISTANT) {
                                chatGptMessages[chatGptMessages.size - 1] = finalMsg
                            } else {
                                chatGptMessages.add(finalMsg)
                            }
                            saveLocalSessionState(userText)
                        } else {
                            if (codexMessages.isNotEmpty() && codexMessages.last().role == MessageRole.ASSISTANT) {
                                codexMessages[codexMessages.size - 1] = finalMsg
                            } else {
                                codexMessages.add(finalMsg)
                            }
                        }
                    }
                }

                override fun onToolCallsDetected(toolCalls: List<com.codex.chat.core.parser.SseStreamParser.CompletedToolCall>) {
                    if (toolCalls.isEmpty()) return
                    // Turno inicial de la cadena agéntica: profundidad 0 con Taint Tracking.
                    executeToolChainStep(
                        userPrompt = userText,
                        streamBuffer = streamBuffer,
                        toolCalls = toolCalls,
                        depth = 0,
                        isWebTainted = isWebTainted
                    )
                }

                override fun onError(error: Throwable) {
                    runOnUiThread {
                        if (activeCall === currentStreamCall) {
                            activeCall = null
                            binding.btnSend.isEnabled = true
                        }
                        chatAdapter.updateLastMessage("⚠️ Error: " + error.message)
                    }
                }
            }
        )
        currentStreamCall = streamObj
        activeCall = streamObj
    }

    private companion object {
        private const val TOOL_MAX_CONTINUATION_DEPTH = 50
    }

    /**
     * MOTOR AGÉNTICO AUTÓNOMO (protocolo OpenAI function-calling nativo).
     *
     * Tras ejecutar herramientas en el dispositivo, este motor envía al modelo:
     *   1. El mensaje assistant con su "tool_calls" original (ids preservados).
     *   2. Un mensaje role:"tool" por cada resultado (toToolMessageJson).
     *
     * CRÍTICO: nunca reenvía el markdown decorativo local (⚙️/✅) ni los argumentos JSON,
     * porque el modelo al verlos imita ese formato y “bota” JSON crudo + SVG al usuario.
     * El registro de conversación que ve el modelo queda limpio y protocolar.
     */
    private fun triggerToolContinuationTurn(
        userPrompt: String,
        streamBuffer: StreamBuffer,
        executedResults: List<com.codex.chat.core.mcp.model.McpToolResult>,
        toolCallsJson: String = "",
        depth: Int = 0,
        isWebTainted: Boolean = false
    ) {
        if (executedResults.isEmpty()) return
        if (depth >= TOOL_MAX_CONTINUATION_DEPTH) {
            runOnUiThread {
                streamBuffer.appendContent("\n\n⚠️ Se alcanzó el límite de pasos encadenados de herramientas (máx. $TOOL_MAX_CONTINUATION_DEPTH). Puedes continuar la tarea pulsando el botón de abajo.")
                val durationMs = 1000L
                val finalContent = streamBuffer.getContent()
                val finalReasoning = streamBuffer.getReasoning()
                val estTokens = com.codex.chat.core.metrics.TokenEstimator.estimateTokens(finalContent + " " + finalReasoning)
                val tps = com.codex.chat.core.metrics.TokenEstimator.calculateTps(estTokens, durationMs)
                val promptTokens = com.codex.chat.core.metrics.TokenEstimator.estimateTokens(userPrompt)
                val metrics = com.codex.chat.core.metrics.StreamMetrics(
                    durationMs = durationMs,
                    promptTokens = promptTokens,
                    completionTokens = estTokens,
                    totalTokens = promptTokens + estTokens,
                    tokensPerSecond = tps
                )

                chatAdapter.completeLastMessage(finalContent, finalReasoning, metrics, canContinueTask = true)

                val finalMsg = ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = finalContent.ifEmpty { " " },
                    reasoningContent = finalReasoning,
                    canContinueTask = true
                )
                finalMsg.applyStreamMetrics(metrics)

                if (currentMode == AppMode.CHATGPT_NORMAL) {
                    if (chatGptMessages.isNotEmpty() && chatGptMessages.last().role == MessageRole.ASSISTANT) {
                        chatGptMessages[chatGptMessages.size - 1] = finalMsg
                    } else {
                        chatGptMessages.add(finalMsg)
                    }
                    saveLocalSessionState(userPrompt)
                } else {
                    if (codexMessages.isNotEmpty() && codexMessages.last().role == MessageRole.ASSISTANT) {
                        codexMessages[codexMessages.size - 1] = finalMsg
                    } else {
                        codexMessages.add(finalMsg)
                    }
                }
                binding.btnSend.isEnabled = true
                activeCall = null
                scrollChatToBottom(smooth = true, onlyIfAtBottom = true)
            }
            return
        }

        val activeModel = modelsRepo.getModelById(settings.selectedModelId)

        // Historial base: el turno de usuario + todos los mensajes previos YA LIMPIOS de markdown local.
        val continuationMessages = ArrayList(messages.dropLast(1))

        // 1. El mensaje assistant que originó las llamadas, con "tool_calls" nativo.
        continuationMessages.add(
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "", // el texto previo ya está en el historial; evita duplicación
                toolCallsJson = toolCallsJson
            )
        )

        // 2. Una respuesta role:"tool" por cada resultado, con el call_id ORIGINAL del modelo.
        for (r in executedResults) {
            continuationMessages.add(
                ChatMessage(
                    role = MessageRole.TOOL,
                    content = r.content,
                    toolCallId = r.callId,
                    toolName = r.toolName
                )
            )
        }

        runOnUiThread { binding.btnSend.isEnabled = false }

        var separatorAppended = false

        val contCall = apiClient.executeStream(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = activeModel,
            effort = settings.reasoningEffort,
            messages = continuationMessages,
            activeSubagent = activeSubagent,
            activeSkill = activeSkill,
            webGrounding = "",
            mcpRegistry = mcpRegistry, // Mantener herramientas activas: permite llamadas encadenadas
            callback = object : CodexApiClient.StreamCallback {
                override fun onReasoningDelta(delta: String) {
                    streamBuffer.appendReasoning(delta)
                    runOnUiThread {
                        chatAdapter.updateLastMessage(streamBuffer.getContent(), streamBuffer.getReasoning())
                        scrollChatToBottom(smooth = true, onlyIfAtBottom = true)
                    }
                }

                override fun onContentDelta(delta: String) {
                    if (!separatorAppended) {
                        separatorAppended = true
                        streamBuffer.appendContent("\n\n")
                    }
                    streamBuffer.appendContent(delta)
                    runOnUiThread {
                        chatAdapter.updateLastMessage(streamBuffer.getContent(), streamBuffer.getReasoning())
                        scrollChatToBottom(smooth = true, onlyIfAtBottom = true)
                    }
                }

                override fun onComplete(fullContent: String, fullReasoning: String) {
                    onCompleteWithMetrics(fullContent, fullReasoning, com.codex.chat.core.metrics.StreamMetrics())
                }

                override fun onCompleteWithMetrics(
                    fullContent: String,
                    fullReasoning: String,
                    metrics: com.codex.chat.core.metrics.StreamMetrics
                ) {
                    runOnUiThread {
                        if (activeCall != null) {
                            activeCall = null
                            binding.btnSend.isEnabled = true
                        }
                        val finalContent = streamBuffer.getContent()
                        val finalReasoning = streamBuffer.getReasoning()
                        chatAdapter.completeLastMessage(finalContent, finalReasoning, metrics)

                        val finalMsg = ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = finalContent.ifEmpty { " " },
                            reasoningContent = finalReasoning
                        )
                        finalMsg.applyStreamMetrics(metrics)

                        if (currentMode == AppMode.CHATGPT_NORMAL) {
                            if (chatGptMessages.isNotEmpty() && chatGptMessages.last().role == MessageRole.ASSISTANT) {
                                chatGptMessages[chatGptMessages.size - 1] = finalMsg
                            } else {
                                chatGptMessages.add(finalMsg)
                            }
                            saveLocalSessionState(userPrompt)
                        } else {
                            if (codexMessages.isNotEmpty() && codexMessages.last().role == MessageRole.ASSISTANT) {
                                codexMessages[codexMessages.size - 1] = finalMsg
                            } else {
                                codexMessages.add(finalMsg)
                            }
                        }
                        scrollChatToBottom(smooth = true, onlyIfAtBottom = true)
                    }
                }

                override fun onToolCallsDetected(toolCalls: List<com.codex.chat.core.parser.SseStreamParser.CompletedToolCall>) {
                    if (toolCalls.isEmpty()) return
                    // PASO SIGUIENTE de la cadena: ejecutar y volver a sintetizar (agentic loop) preservando el Taint.
                    executeToolChainStep(
                        userPrompt = userPrompt,
                        streamBuffer = streamBuffer,
                        toolCalls = toolCalls,
                        depth = depth + 1,
                        isWebTainted = isWebTainted
                    )
                }

                override fun onError(error: Throwable) {
                    runOnUiThread {
                        activeCall = null
                        binding.btnSend.isEnabled = true
                        streamBuffer.appendContent("\n\n⚠️ Error en la síntesis: " + (error.message ?: "desconocido"))
                        chatAdapter.updateLastMessage(streamBuffer.getContent(), streamBuffer.getReasoning())
                    }
                }
            }
        )

        activeCall = contCall
    }

    /**
     * Ejecuta una ronda de llamadas de herramienta y dispara la síntesis/continuación.
     * Extraído para servir tanto al turno inicial como a los pasos encadenados.
     */
    private fun executeToolChainStep(
        userPrompt: String,
        streamBuffer: StreamBuffer,
        toolCalls: List<com.codex.chat.core.parser.SseStreamParser.CompletedToolCall>,
        depth: Int,
        isWebTainted: Boolean = false
    ) {
        if (toolCalls.isEmpty()) return
        runOnUiThread { binding.btnSend.isEnabled = false }
        thread {
            val executedResults = mutableListOf<com.codex.chat.core.mcp.model.McpToolResult>()
            val toolCallEntries = org.json.JSONArray()

            for (tc in toolCalls) {
                val risk = ToolRiskClassifier.classify(tc.name)
                val serverName = mcpRegistry.servidorDe(tc.name) ?: "Servidor MCP"
                val req = ApprovalRequest(
                    toolName = tc.name,
                    argumentsJson = tc.argumentsJson.ifBlank { "{}" },
                    risk = risk,
                    serverName = serverName,
                    isWebTainted = isWebTainted
                )
                val decision = approvalGate.decide(req, settings.approvalPolicy)
                val res = if (decision == ApprovalDecision.APPROVED || decision == ApprovalDecision.APPROVED_SESSION) {
                    // Preservar el tool_call_id ORIGINAL del modelo (protocolo OpenAI).
                    mcpRegistry.executeToolWithCallId(tc.id.ifBlank { "call-" + UUID.randomUUID().toString().take(8) }, tc.name, tc.argumentsJson.ifBlank { "{}" })
                } else {
                    val reason = if (decision == ApprovalDecision.TIMEOUT) "Cancelado por tiempo de espera (120 s)" else "Rechazado por el usuario"
                    com.codex.chat.core.mcp.model.McpToolResult(
                        callId = tc.id.ifBlank { UUID.randomUUID().toString() },
                        toolName = tc.name,
                        content = "Ejecución cancelada: $reason.",
                        isError = true
                    )
                }
                executedResults.add(res)

                // Registrar la llamada en formato protocolar para el historial del modelo.
                val fn = org.json.JSONObject().put("name", tc.name)
                try { fn.put("arguments", tc.argumentsJson.ifBlank { "{}" }) } catch (e: Exception) { fn.put("arguments", "{}") }
                toolCallEntries.put(
                    org.json.JSONObject()
                        .put("id", tc.id.ifBlank { "call-" + UUID.randomUUID().toString().take(8) })
                        .put("type", "function")
                        .put("function", fn)
                )

                // Presentación local visual (colapsable) — NUNCA se reenvía al modelo.
                val icon = if (res.isError) "❌" else "✅"
                val resultBlock = "\n\n$icon **[Resultado MCP: `" + res.toolName + "`]**\n```json\n" + res.content + "\n```\n"
                streamBuffer.appendContent(resultBlock)
                runOnUiThread {
                    val currentContent = streamBuffer.getContent()
                    chatAdapter.updateLastMessage(currentContent, streamBuffer.getReasoning())
                    val lastIndex = if (currentMode == AppMode.CHATGPT_NORMAL) chatGptMessages.size - 1 else codexMessages.size - 1
                    if (lastIndex >= 0) {
                        val updatedMsg = ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = currentContent,
                            reasoningContent = streamBuffer.getReasoning()
                        )
                        if (currentMode == AppMode.CHATGPT_NORMAL) {
                            chatGptMessages[lastIndex] = updatedMsg
                        } else {
                            codexMessages[lastIndex] = updatedMsg
                        }
                    }
                    scrollChatToBottom(smooth = true, onlyIfAtBottom = true)
                }
            }

            triggerToolContinuationTurn(
                userPrompt = userPrompt,
                streamBuffer = streamBuffer,
                executedResults = executedResults,
                toolCallsJson = toolCallEntries.toString(),
                depth = depth,
                isWebTainted = isWebTainted
            )
        }
    }

    private fun showToolApprovalDialog(
        req: ApprovalRequest,
        callback: (ApprovalDecision) -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_tool_approval, null)
        val tvRiskBadge = view.findViewById<TextView>(R.id.tvRiskBadge)
        val tvApprovalTitle = view.findViewById<TextView>(R.id.tvApprovalTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvApprovalSubtitle)
        val tvToolName = view.findViewById<TextView>(R.id.tvToolName)
        val tvServerName = view.findViewById<TextView>(R.id.tvServerName)
        val tvArgumentsJson = view.findViewById<TextView>(R.id.tvArgumentsJson)
        val tvIrreversible = view.findViewById<TextView>(R.id.tvIrreversibleWarning)
        val btnApproveSession = view.findViewById<Button>(R.id.btnApproveSession)
        val btnReject = view.findViewById<Button>(R.id.btnReject)
        val btnApproveOnce = view.findViewById<Button>(R.id.btnApproveOnce)

        tvToolName.text = req.toolName
        tvServerName.text = "Servidor: " + req.serverName

        val targetPath = try {
            JSONObject(req.argumentsJson).optString("file_path", "")
        } catch (e: Exception) { "" }

        val formattedJson = try {
            val raw = req.argumentsJson.trim()
            if (raw.startsWith("{")) {
                JSONObject(raw).toString(2)
            } else if (raw.startsWith("[")) {
                JSONArray(raw).toString(2)
            } else {
                req.argumentsJson
            }
        } catch (e: Exception) {
            req.argumentsJson
        }
        tvArgumentsJson.text = formattedJson

        when {
            req.risk == ToolRiskLevel.ROOT || req.toolName.startsWith("root_") -> {
                tvRiskBadge.text = "🔴 ROOT"
                tvRiskBadge.setBackgroundColor(0xFFDC2626.toInt())
                tvApprovalTitle.text = "Comando Root / Superusuario"
                tvSubtitle.text = "El asistente solicita privilegios de superusuario para ejecutar este comando:"
            }
            req.toolName == "delete_file" || req.toolName == "delete_memory" -> {
                tvRiskBadge.text = "🗑️ ELIMINACIÓN"
                tvRiskBadge.setBackgroundColor(0xFFDC2626.toInt())
                tvApprovalTitle.text = "Eliminación de archivo"
                tvSubtitle.text = "El asistente solicita eliminar un archivo de tu almacenamiento:"
            }
            req.toolName == "write_file" || req.toolName == "create_directory" -> {
                tvRiskBadge.text = "💾 ESCRITURA EN DISCO"
                tvRiskBadge.setBackgroundColor(0xFF0284C7.toInt())
                tvApprovalTitle.text = "Guardar archivo en almacenamiento"
                tvSubtitle.text = if (targetPath.isNotBlank()) {
                    "El asistente quiere guardar el archivo en:\n$targetPath"
                } else {
                    "El asistente quiere guardar o modificar un archivo en tu almacenamiento:"
                }
            }
            req.risk == ToolRiskLevel.DESTRUCTIVE -> {
                tvRiskBadge.text = "⚡ ACCIÓN EXTERNA"
                tvRiskBadge.setBackgroundColor(0xFFEA580C.toInt())
                tvApprovalTitle.text = "Ejecutar acción"
                tvSubtitle.text = "El asistente quiere ejecutar la siguiente herramienta en el dispositivo:"
            }
            req.risk == ToolRiskLevel.SENSITIVE -> {
                tvRiskBadge.text = "🟡 PRIVACIDAD"
                tvRiskBadge.setBackgroundColor(0xFFD97706.toInt())
                tvApprovalTitle.text = "Acceso a datos o sensores"
                tvSubtitle.text = "El asistente solicita consultar información del dispositivo:"
            }
            else -> {
                tvRiskBadge.text = "🟢 SEGURO"
                tvRiskBadge.setBackgroundColor(0xFF059669.toInt())
                tvApprovalTitle.text = "Petición de lectura"
                tvSubtitle.text = "El asistente quiere consultar la siguiente herramienta:"
            }
        }

        // CaMeL Taint Tracking & Alerta de Peligro en Argumentos
        if (req.isWebTainted || req.dangerReason != null) {
            tvApprovalTitle.text = if (req.dangerReason != null) "🚨 ALERTA DE SEGURIDAD" else "🌐 ACCIÓN INDUCIDA POR WEB"
            val alertaTexto = if (req.dangerReason != null) {
                "⚠️ ${req.dangerReason}\n\n"
            } else {
                "⚠️ Esta acción fue inducida por información no confiable obtenida de internet (CaMeL Taint Tracking).\n\n"
            }
            tvSubtitle.text = alertaTexto + tvSubtitle.text
            tvRiskBadge.text = "⚠️ PROTEGIDO (CaMeL)"
            tvRiskBadge.setBackgroundColor(0xFFDC2626.toInt())
        }

        val isIrreversible = ToolApprovalPolicy.isIrreversible(req.toolName)
        if (isIrreversible || req.isWebTainted || req.dangerReason != null) {
            tvIrreversible.visibility = View.VISIBLE
            tvIrreversible.text = if (req.dangerReason != null) {
                "⚠️ " + req.dangerReason
            } else if (req.isWebTainted) {
                "⚠️ Acción originada en búsqueda web externa: no se permite recordar para la sesión por seguridad."
            } else {
                "⚠️ Esta acción es irreversible: no se puede deshacer una vez ejecutada."
            }
            btnApproveSession.visibility = View.GONE
        } else {
            tvIrreversible.visibility = View.GONE
            btnApproveSession.visibility = View.VISIBLE
        }

        val decided = java.util.concurrent.atomic.AtomicBoolean(false)
        fun safeCallback(decision: ApprovalDecision) {
            if (decided.compareAndSet(false, true)) {
                callback(decision)
            }
        }

        val dlg = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(true)
            .setOnCancelListener { safeCallback(ApprovalDecision.DENIED) }
            .setOnDismissListener { safeCallback(ApprovalDecision.DENIED) }
            .create()

        dlg.setCanceledOnTouchOutside(true)

        btnReject.setOnClickListener {
            safeCallback(ApprovalDecision.DENIED)
            dlg.dismiss()
        }
        btnApproveOnce.setOnClickListener {
            safeCallback(ApprovalDecision.APPROVED)
            dlg.dismiss()
        }
        btnApproveSession.setOnClickListener {
            safeCallback(ApprovalDecision.APPROVED_SESSION)
            dlg.dismiss()
        }

        dlg.show()
    }

    private fun saveLocalSessionState(userText: String) {
        // FIX congelamiento: serializar sesiones (que pueden contener imágenes base64 de ~1 MB)
        // JAMÁS en el hilo UI. Copia inmutable + persistencia en background.
        val snapshot = ArrayList(messages.map { it.copy() })
        val sessionId = activeLocalSessionId
        val sessionTitle = if (userText.length > 28) userText.take(28) + "…" else userText
        thread {
            try {
                if (sessionId == null) {
                    val session = LocalChatSession(title = sessionTitle)
                    runOnUiThread { activeLocalSessionId = session.id }
                    session.messages.addAll(snapshot)
                    localChatRepo.saveSession(session)
                } else {
                    val session = localChatRepo.getSession(sessionId) ?: LocalChatSession(id = sessionId, title = sessionTitle)
                    session.messages.clear()
                    session.messages.addAll(snapshot)
                    localChatRepo.saveSession(session)
                }
                runOnUiThread { loadDrawerHistory() }
            } catch (e: Exception) {
                android.util.Log.e("ChatPersist", "Error guardando sesión local", e)
            }
        }
    }

    private fun showModelAndEffortPicker() {
        val models = modelsRepo.getCachedModels()
        val modelNames = models.map { it.displayName + " (" + it.provider + ")" }.toTypedArray()
        val selectedIdx = models.indexOfFirst { it.id == settings.selectedModelId }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("Selecciona el Modelo de IA")
            .setSingleChoiceItems(modelNames, selectedIdx) { dialog, which ->
                val chosen = models[which]
                settings.selectedModelId = chosen.id
                dialog.dismiss()

                if (chosen.supportsReasoning) {
                    showEffortPicker(chosen)
                } else {
                    updateHeaderBadges()
                    Toast.makeText(this@MainActivity, "Modelo: " + chosen.id, Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Sincronizar Modelos") { _, _ ->
                syncLiveModels()
                Toast.makeText(this, "Sincronizando modelos con el servidor…", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showEffortPicker(model: ModelInfo) {
        val efforts = arrayOf("Low (Rápido)", "Medium (Equilibrado)", "High (Profundo)", "xHigh (Máximo Arquitecto)")
        val effortValues = arrayOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH)
        val currentIdx = effortValues.indexOf(settings.reasoningEffort).coerceAtLeast(2)

        MaterialAlertDialogBuilder(this)
            .setTitle("Nivel de Razonamiento para " + model.id)
            .setSingleChoiceItems(efforts, currentIdx) { dialog, which ->
                settings.reasoningEffort = effortValues[which]
                updateHeaderBadges()
                Toast.makeText(this@MainActivity, "Razonamiento: " + settings.reasoningEffort.value.uppercase(), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .show()
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val tvConnectedProfileName = view.findViewById<TextView>(R.id.tvConnectedProfileName)
        val tvProfileStatusBadge = view.findViewById<TextView>(R.id.tvProfileStatusBadge)
        val btnSelectProfile = view.findViewById<Button>(R.id.btnSelectProfile)
        val btnAddCustomProvider = view.findViewById<Button>(R.id.btnAddCustomProvider)
        val btnManageCustomProviders = view.findViewById<Button>(R.id.btnManageCustomProviders)
        val btnTestConnection = view.findViewById<Button>(R.id.btnTestConnection)
        val tvStatus = view.findViewById<TextView>(R.id.tvConnectionStatus)
        val etE2bApiKey = view.findViewById<EditText>(R.id.etE2bApiKey)
        val btnCheckUpdates = view.findViewById<Button>(R.id.btnCheckUpdates)
        val btnSave = view.findViewById<Button>(R.id.btnSaveSettings)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelSettings)

        fun refreshProfileDisplay() {
            val active = providerManager.getActiveProfile()
            tvConnectedProfileName.text = "🏢 " + active.name
            if (active.isReadOnly) {
                tvProfileStatusBadge.text = "🔒 Perfil integrado del sistema (Inmutable)"
                tvProfileStatusBadge.setTextColor(Color.parseColor("#64748B"))
            } else {
                tvProfileStatusBadge.text = "✏️ Proveedor personalizado"
                tvProfileStatusBadge.setTextColor(Color.parseColor("#4ADE80"))
            }
        }
        refreshProfileDisplay()

        btnSelectProfile.setOnClickListener {
            val all = providerManager.getAllProfiles()
            val labels = all.map { p ->
                val badge = if (p.isReadOnly) "🔒" else "✏️"
                "$badge ${p.name}"
            }.toTypedArray()
            val currentIdx = all.indexOfFirst { it.id == settings.activeProfileId }.coerceAtLeast(0)

            MaterialAlertDialogBuilder(this)
                .setTitle("Seleccionar Proveedor LLM")
                .setSingleChoiceItems(labels, currentIdx) { d, which ->
                    val chosen = all[which]
                    providerManager.applyProfile(chosen)
                    refreshProfileDisplay()
                    d.dismiss()
                    Toast.makeText(this@MainActivity, "Proveedor activo: " + chosen.name, Toast.LENGTH_SHORT).show()
                    syncLiveModels()
                }
                .setNegativeButton("Cerrar", null)
                .show()
        }

        btnAddCustomProvider.setOnClickListener {
            showAddCustomProviderDialog { newP ->
                providerManager.applyProfile(newP)
                refreshProfileDisplay()
                syncLiveModels()
            }
        }

        btnManageCustomProviders.setOnClickListener {
            showManageCustomProvidersDialog {
                refreshProfileDisplay()
                syncLiveModels()
            }
        }

        btnTestConnection.setOnClickListener {
            tvStatus.visibility = View.VISIBLE
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            tvStatus.text = "Conectando al proveedor…"
            btnTestConnection.isEnabled = false

            val active = providerManager.getActiveProfile()
            thread {
                val res = modelsRepo.fetchLiveModels(active.baseUrl, active.apiKey)
                runOnUiThread {
                    btnTestConnection.isEnabled = true
                    if (res.isSuccess) {
                        val count = res.getOrNull()?.size ?: 0
                        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.brand_green))
                        tvStatus.text = "✅ Conexión exitosa ($count modelos disponibles)"
                    } else {
                        tvStatus.setTextColor(Color.parseColor("#EF4444"))
                        val err = res.exceptionOrNull()?.message ?: "Sin respuesta"
                        tvStatus.text = "❌ Error: $err"
                    }
                }
            }
        }

        val isCustomE2b = settings.e2bApiKey.isNotBlank() && settings.e2bApiKey != SecureKeyVault.getE2bDefaultKey()
        if (isCustomE2b) {
            etE2bApiKey.setText(settings.e2bApiKey)
        } else {
            etE2bApiKey.setText("")
        }

        btnCheckUpdates.text = "🚀 Buscar Actualización (v" + BuildConfig.VERSION_NAME + ")"
        btnCheckUpdates.setOnClickListener {
            btnCheckUpdates.isEnabled = false
            btnCheckUpdates.text = "Comprobando en Proxy…"
            updateManager.checkForUpdates(
                getCodexServerBaseUrl(),
                onUpdateAvailable = { info ->
                    btnCheckUpdates.isEnabled = true
                    btnCheckUpdates.text = "🚀 v" + info.versionName + " lista"
                    updateManager.showUpdateDialog(this, info)
                },
                onNoUpdate = {
                    btnCheckUpdates.isEnabled = true
                    btnCheckUpdates.text = "✅ Al día (v" + BuildConfig.VERSION_NAME + ")"
                    Toast.makeText(this, "Tu app ya tiene la última versión (v" + BuildConfig.VERSION_NAME + ")", Toast.LENGTH_SHORT).show()
                },
                onError = { err ->
                    btnCheckUpdates.isEnabled = true
                    btnCheckUpdates.text = "❌ Error al comprobar"
                    Toast.makeText(this, "No se pudo conectar al Proxy PC: " + err, Toast.LENGTH_LONG).show()
                }
            )
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val newE2bKey = etE2bApiKey.text.toString().trim()
            if (newE2bKey.isNotEmpty()) {
                settings.e2bApiKey = newE2bKey
            }

            val active = providerManager.getActiveProfile()
            Toast.makeText(this, "Proveedor activo: " + active.name, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            syncLiveModels()
            loadRemoteConversations()
            checkForAppUpdates(silent = false)
        }

        dialog.show()
    }

    private fun showAddCustomProviderDialog(onAdded: (ProviderProfile) -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val etName = EditText(this).apply { hint = "Nombre (ej. OpenRouter / Groq / vLLM)" }
        val etUrl = EditText(this).apply { hint = "Base URL (ej. https://openrouter.ai/api/v1)" }
        val etKey = EditText(this).apply {
            hint = "API Key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val etModel = EditText(this).apply { hint = "Modelo por defecto (ej. meta-llama/llama-3)" }

        layout.addView(etName)
        layout.addView(etUrl)
        layout.addView(etKey)
        layout.addView(etModel)

        MaterialAlertDialogBuilder(this)
            .setTitle("➕ Añadir Proveedor Personalizado")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val name = etName.text.toString().trim()
                val url = etUrl.text.toString().trim()
                val key = etKey.text.toString().trim()
                val model = etModel.text.toString().trim()
                if (name.isBlank() || url.isBlank()) {
                    Toast.makeText(this, "Nombre y URL son obligatorios", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                try {
                    val p = providerManager.saveCustomProfile(
                        name = name,
                        baseUrl = url,
                        apiKey = key,
                        defaultModel = model
                    )
                    Toast.makeText(this, "Proveedor creado: " + p.name, Toast.LENGTH_SHORT).show()
                    onAdded(p)
                } catch (e: Exception) {
                    Toast.makeText(this, "Error: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showManageCustomProvidersDialog(onChanged: () -> Unit) {
        val customs = providerManager.getCustomProfiles()
        if (customs.isEmpty()) {
            Toast.makeText(this, "No tienes proveedores personalizados guardados aún.", Toast.LENGTH_SHORT).show()
            return
        }

        val names = customs.map { "🗑️ Eliminar: ${it.name}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Administrar Proveedores Personalizados")
            .setItems(names) { _, which ->
                val target = customs[which]
                MaterialAlertDialogBuilder(this)
                    .setTitle("¿Eliminar proveedor?")
                    .setMessage("¿Deseas eliminar '${target.name}'?\nEsta acción no se puede deshacer.")
                    .setPositiveButton("Eliminar") { _, _ ->
                        providerManager.deleteCustomProfile(target.id)
                        Toast.makeText(this, "Proveedor eliminado: " + target.name, Toast.LENGTH_SHORT).show()
                        onChanged()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun checkForAppUpdates(silent: Boolean = false) {
        updateManager.checkForUpdates(
            getCodexServerBaseUrl(),
            onUpdateAvailable = { info ->
                updateManager.showUpdateDialog(this, info)
            },
            onNoUpdate = {
                if (!silent) {
                    Toast.makeText(this, "App al día (v" + BuildConfig.VERSION_NAME + ")", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                    binding.btnMic.setColorFilter(Color.parseColor("#ECECEC"))
                }
                override fun onError(error: Int) {
                    isListening = false
                    binding.btnMic.setColorFilter(Color.parseColor("#ECECEC"))
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val heard = matches[0]
                        val cur = binding.etMessage.text.toString()
                        binding.etMessage.setText(if (cur.isEmpty()) heard else "$cur $heard")
                        binding.etMessage.setSelection(binding.etMessage.text.length)
                    }
                    isListening = false
                    binding.btnMic.setColorFilter(Color.parseColor("#ECECEC"))
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun toggleSpeech() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }

        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
            binding.btnMic.setColorFilter(Color.parseColor("#ECECEC"))
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            }
            speechRecognizer?.startListening(intent)
            isListening = true
            binding.btnMic.setColorFilter(ContextCompat.getColor(this, R.color.brand_green))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeCall?.cancel()
        activeCall = null
        tokenPollActivo?.cancelado = true
        approvalGate.clearSessionAllowlist()
        speechRecognizer?.destroy()
    }
}
