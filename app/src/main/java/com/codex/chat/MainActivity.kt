package com.codex.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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
import com.codex.chat.core.network.CodexApiClient
import com.codex.chat.core.repository.DynamicModelsRepository
import com.codex.chat.core.repository.DynamicSubagentsRepository
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
    private var activeCwd: String = "C:\\Users\\Admin\\Desktop"
    private var activeSandboxPolicy: String = "danger-full-access"
    private var activeApprovalPolicy: String = "never"
    private var activeSubagent: SubagentInfo? = null
    private var pendingAttachment: Attachment? = null
    private var isWebSearchActive = false
    private var activeCall: Call? = null

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
        apiClient = CodexApiClient()
        modelsRepo = DynamicModelsRepository()
        subagentsRepo = DynamicSubagentsRepository()
        updateManager = AppUpdateManager(this)
        localChatRepo = LocalChatRepository(this)

        setupRecyclerView()
        setupDrawer()
        setupHeader()
        setupContextBar()
        setupModeSwitcher()
        setupInputListeners()
        setupKeyboardInsets()
        setupSpeechRecognizer()

        // Sync live models, load PC conversations from SQLite, and check for OTA updates
        syncLiveModels()
        loadRemoteConversations()
        fetchRemoteConfig()
        checkForAppUpdates(silent = true)
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
        currentMode = mode
        if (mode == AppMode.CHATGPT_NORMAL) {
            // Tab 1 Active (ChatGPT Normal)
            binding.tabModeChatGpt.setBackgroundResource(R.drawable.bg_tab_selected)
            binding.tabModeChatGpt.setTextColor(Color.parseColor("#ECECEC"))
            binding.tabModeCodex.background = null
            binding.tabModeCodex.setTextColor(Color.parseColor("#8E8E8E"))

            binding.activeContextBar.visibility = View.GONE
            binding.etMessage.hint = "Mensaje a ChatGPT..."
            binding.tvDrawerSectionTitle.text = "HISTORIAL (ChatGPT Móvil)"

            // Completely blank if no prior conversation! ZERO hardcoded greeting text!
            messages.clear()
            messages.addAll(chatGptMessages)
            chatAdapter.notifyDataSetChanged()

            loadDrawerHistory()
        } else {
            // Tab 2 Active (Codex PC)
            binding.tabModeCodex.setBackgroundResource(R.drawable.bg_tab_selected_codex)
            binding.tabModeCodex.setTextColor(Color.parseColor("#6EE7B7"))
            binding.tabModeChatGpt.background = null
            binding.tabModeChatGpt.setTextColor(Color.parseColor("#8E8E8E"))

            binding.activeContextBar.visibility = View.VISIBLE
            binding.etMessage.hint = "Mensaje a Codex Desktop..."
            binding.tvDrawerSectionTitle.text = "HISTORIAL (Codex PC codex-dev.db)"

            // Completely blank if no prior conversation! ZERO hardcoded greeting text!
            messages.clear()
            messages.addAll(codexMessages)
            chatAdapter.notifyDataSetChanged()

            loadDrawerHistory()
        }
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(messages)
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        binding.rvMessages.layoutManager = layoutManager
        binding.rvMessages.adapter = chatAdapter
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
    }

    private fun loadDrawerHistory() {
        if (currentMode == AppMode.CHATGPT_NORMAL) {
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
            drawerAdapter.updateData(list, activeLocalSessionId)
            binding.tvServerDot.text = "${list.size} chats locales"
            binding.tvServerDot.setTextColor(Color.parseColor("#10A37F"))
        } else {
            loadRemoteConversations()
        }
    }

    private fun loadLocalSession(sessionId: String) {
        val session = localChatRepo.getSession(sessionId) ?: return
        activeLocalSessionId = session.id
        chatGptMessages.clear()
        chatGptMessages.addAll(session.messages)
        messages.clear()
        messages.addAll(chatGptMessages)
        chatAdapter.notifyDataSetChanged()
        if (messages.isNotEmpty()) {
            binding.rvMessages.scrollToPosition(messages.size - 1)
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

        messages.clear()
        pendingAttachment = null
        binding.attachmentPreviewBar.visibility = View.GONE
        chatAdapter.notifyDataSetChanged()

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

        binding.btnRemoveAttachment.setOnClickListener {
            pendingAttachment = null
            isWebSearchActive = false
            binding.attachmentPreviewBar.visibility = View.GONE
            binding.etMessage.hint = if (currentMode == AppMode.CHATGPT_NORMAL) "Mensaje a ChatGPT..." else "Mensaje a Codex Desktop..."
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

        // Toggle send button appearance based on input length
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrBlank() || pendingAttachment != null
                binding.btnSend.alpha = if (hasText) 1.0f else 0.5f
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
            isWebSearchActive = true
            binding.tvAttachmentIcon.text = "🌐"
            binding.tvAttachmentName.text = "Búsqueda Web en Vivo (Activa)"
            binding.attachmentPreviewBar.visibility = View.VISIBLE
            binding.etMessage.hint = "¿Qué deseas consultar en internet en vivo?"
            Toast.makeText(this, "Búsqueda Web activada: escribe tu consulta", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<View>(R.id.actionNormalCloudPython).setOnClickListener {
            dialog.dismiss()
            val cur = binding.etMessage.text.toString()
            if (!cur.startsWith("🐍 [Python E2B Cloud]:")) {
                binding.etMessage.setText("🐍 [Python E2B Cloud]: " + cur)
                binding.etMessage.setSelection(binding.etMessage.text.length)
            }
            binding.etMessage.hint = "Escribe código Python para ejecutar en E2B Cloud..."
            Toast.makeText(this, "E2B Cloud: ejecución en la nube sin Docker en PC", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<View>(R.id.actionNormalSubagents).setOnClickListener {
            dialog.dismiss()
            showSubagentsPicker()
        }

        dialog.show()
    }

    private fun showSubagentsPicker() {
        val agents = subagentsRepo.getAllSubagents()
        val items = agents.map { it.iconEmoji + " " + it.name + " (" + it.defaultModel + ")" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Seleccionar Subagente de Ingeniería")
            .setItems(items) { _, which ->
                val chosen = agents[which]
                activeSubagent = chosen
                settings.activeSubagentId = chosen.id
                settings.selectedModelId = chosen.defaultModel
                settings.reasoningEffort = chosen.reasoningEffort
                updateHeaderBadges()
                Toast.makeText(this, "Subagente: " + chosen.name, Toast.LENGTH_SHORT).show()
                val msg = "🤖 Subagente activo: **" + chosen.name + "**\n" + chosen.description
                val assistantMsg = ChatMessage(role = MessageRole.ASSISTANT, content = msg)
                if (currentMode == AppMode.CHATGPT_NORMAL) chatGptMessages.add(assistantMsg) else codexMessages.add(assistantMsg)
                chatAdapter.addMessage(assistantMsg)
                binding.rvMessages.scrollToPosition(messages.size - 1)
            }
            .setNegativeButton("Quitar subagente") { _, _ ->
                activeSubagent = null
                settings.activeSubagentId = null
                updateHeaderBadges()
                Toast.makeText(this, "Subagente desactivado", Toast.LENGTH_SHORT).show()
            }
            .show()
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
            isWebSearchActive = true
            binding.tvAttachmentIcon.text = "🌐"
            binding.tvAttachmentName.text = "Búsqueda Web en Vivo (Activa)"
            binding.attachmentPreviewBar.visibility = View.VISIBLE
            binding.etMessage.hint = "¿Qué deseas consultar en internet en vivo?"
            Toast.makeText(this, "Búsqueda Web activada: escribe tu consulta", Toast.LENGTH_SHORT).show()
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
                    if (workspaces.isEmpty()) {
                        workspaces.add("C:\\Users\\Admin\\Desktop")
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
        messages.clear()
        chatAdapter.notifyDataSetChanged()

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
                    messages.clear()
                    if (loadedMessages.isEmpty()) {
                        messages.add(ChatMessage(role = MessageRole.ASSISTANT, content = "Conversación iniciada sin mensajes previos aún."))
                    } else {
                        messages.addAll(loadedMessages)
                    }
                    chatAdapter.notifyDataSetChanged()
                    binding.rvMessages.scrollToPosition(messages.size - 1)
                    Toast.makeText(this@MainActivity, "Cargada: " + conv.title, Toast.LENGTH_SHORT).show()
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

    private fun getCodexServerBaseUrl(): String {
        return try {
            val uri = Uri.parse(settings.baseUrl)
            val host = uri.host ?: "192.168.1.6"
            val scheme = uri.scheme ?: "http"
            "$scheme://$host:8318"
        } catch (e: Exception) {
            "http://192.168.1.6:8318"
        }
    }

    private fun syncLiveModels() {
        thread {
            modelsRepo.fetchLiveModels(settings.baseUrl, settings.apiKey)
            runOnUiThread {
                updateHeaderBadges()
            }
        }
    }

    private fun handleFileUri(uri: Uri, isImage: Boolean) {
        try {
            var fileName = "adjunto"
            var fileSize: Long = 0
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex != -1) fileName = it.getString(nameIndex)
                    if (sizeIndex != -1) fileSize = it.getLong(sizeIndex)
                }
            }

            val mimeType = contentResolver.getType(uri) ?: if (isImage) "image/png" else "text/plain"
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes() ?: ByteArray(0)
            val base64 = java.util.Base64.getEncoder().encodeToString(bytes)

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
            binding.attachmentPreviewBar.visibility = View.VISIBLE

            Toast.makeText(this, "Adjuntado: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error leyendo archivo: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty() && pendingAttachment == null) return

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

        binding.etMessage.setText("")
        pendingAttachment = null
        val wasWebSearch = isWebSearchActive
        isWebSearchActive = false
        binding.attachmentPreviewBar.visibility = View.GONE
        binding.rvMessages.scrollToPosition(messages.size - 1)

        val assistantMsg = ChatMessage(role = MessageRole.ASSISTANT, content = "Pensando…", isStreaming = true)
        chatAdapter.addMessage(assistantMsg)
        binding.rvMessages.scrollToPosition(messages.size - 1)

        binding.btnSend.isEnabled = false

        // 1. Check if Python E2B Cloud execution is requested (Zero local Docker)
        if (text.startsWith("🐍 [Python E2B Cloud]:") || text.startsWith("🐍")) {
            executeCloudPython(text)
            return
        }

        // 2. Also notify PC Codex Desktop in parallel when in Codex PC Mode
        if (currentMode == AppMode.CODEX_PC) {
            thread {
                try {
                    val url = getCodexServerBaseUrl() + "/api/send"
                    val payload = JSONObject().apply {
                        put("text", text)
                        put("submit", true)
                        put("thread_id", activeThreadId ?: "")
                        put("cwd", activeCwd)
                        put("sandbox_policy", activeSandboxPolicy)
                    }
                    val body = payload.toString().toRequestBody("application/json".toMediaType())
                    val req = Request.Builder().url(url).post(body).build()
                    okHttpClient.newCall(req).execute()
                } catch (e: Exception) {
                    // Non-fatal if PC bridge is busy
                }
            }
        }

        // 3. Check if live web search is requested
        val isWebSearch = wasWebSearch || text.startsWith("🌐 [Búsqueda Web]:") || text.startsWith("🌐")
        if (isWebSearch) {
            val cleanQuery = text.removePrefix("🌐 [Búsqueda Web]:").removePrefix("🌐").trim()
            chatAdapter.updateLastMessage("🔍 Consultando internet en vivo: '$cleanQuery'…")
            thread {
                var webGrounding = ""
                try {
                    val url = getCodexServerBaseUrl() + "/api/web_search?q=" + java.net.URLEncoder.encode(cleanQuery, "UTF-8")
                    val req = Request.Builder().url(url).get().build()
                    val resp = okHttpClient.newCall(req).execute()
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    webGrounding = json.optString("formatted_context", "")
                } catch (e: Exception) {
                    // Fallback directly to Wikipedia API from mobile device
                    try {
                        val wikiUrl = "https://es.wikipedia.org/w/api.php?action=query&list=search&format=json&srsearch=" + java.net.URLEncoder.encode(cleanQuery, "UTF-8")
                        val wReq = Request.Builder().url(wikiUrl).addHeader("User-Agent", "CodexChatGPT/1.0").get().build()
                        val wResp = okHttpClient.newCall(wReq).execute()
                        val wJson = JSONObject(wResp.body?.string() ?: "{}")
                        val searchItems = wJson.optJSONObject("query")?.optJSONArray("search")
                        if (searchItems != null && searchItems.length() > 0) {
                            val sb = StringBuilder("=== RESULTADOS WEB EN VIVO PARA: '$cleanQuery' ===\n")
                            for (i in 0 until minOf(searchItems.length(), 4)) {
                                val item = searchItems.getJSONObject(i)
                                val title = item.optString("title")
                                val rawSnippet = item.optString("snippet")
                                val cleanSnippet = rawSnippet.replace(Regex("<[^>]+>"), "")
                                sb.append("• ").append(title).append(": ").append(cleanSnippet).append("\n")
                            }
                            sb.append("=== FIN DATOS WEB (Responde con base en estos datos actuales citando fuentes) ===\n")
                            webGrounding = sb.toString()
                        }
                    } catch (e2: Exception) {
                        // ignore fallback errors
                    }
                }

                runOnUiThread {
                    executeStreamWithContext(text, webGrounding)
                }
            }
            return
        }

        executeStreamWithContext(text, "")
    }

    private fun executeCloudPython(rawText: String) {
        val code = rawText.removePrefix("🐍 [Python E2B Cloud]:").removePrefix("🐍").trim()
        chatAdapter.updateLastMessage("⚡ Conectando con MicroVM E2B Cloud (sin Docker local)…")
        thread {
            try {
                val url = getCodexServerBaseUrl() + "/api/execute"
                val json = JSONObject().apply {
                    put("language", "python")
                    put("code", code)
                    put("session_id", activeLocalSessionId ?: "mobile_chat")
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(url).post(body).build()
                val resp = okHttpClient.newCall(req).execute()
                val resJson = JSONObject(resp.body?.string() ?: "{}")
                val stdout = resJson.optString("stdout", "").trim()
                val stderr = resJson.optString("stderr", "").trim()
                val error = resJson.optString("error", "").trim()

                val sb = java.lang.StringBuilder()
                sb.append("### 🐍 Salida E2B Cloud (MicroVM en la nube)\n\n")
                if (stdout.isNotEmpty()) {
                    sb.append("```text\n").append(stdout).append("\n```\n\n")
                }
                if (stderr.isNotEmpty()) {
                    sb.append("**Stderr:**\n```text\n").append(stderr).append("\n```\n\n")
                }
                if (error.isNotEmpty()) {
                    sb.append("⚠️ **Error:**\n```text\n").append(error).append("\n```\n\n")
                }
                if (stdout.isEmpty() && stderr.isEmpty() && error.isEmpty()) {
                    sb.append("✅ Código ejecutado correctamente sin salida estándar.")
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
                    chatAdapter.updateLastMessage("⚠️ Error conectando con el servidor E2B Cloud: " + e.message)
                }
            }
        }
    }

    private fun executeStreamWithContext(userText: String, webGrounding: String) {
        val streamContentBuffer = StringBuilder()
        val streamReasoningBuffer = StringBuilder()
        val activeModel = modelsRepo.getModelById(settings.selectedModelId)

        // Build outgoing messages; if webGrounding is present, inject grounding context
        val outgoingMessages = messages.dropLast(1).toMutableList()
        if (webGrounding.isNotBlank()) {
            outgoingMessages.add(
                outgoingMessages.size,
                ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = webGrounding
                )
            )
        }

        activeCall = apiClient.executeStream(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = activeModel,
            effort = settings.reasoningEffort,
            messages = outgoingMessages,
            activeSubagent = activeSubagent,
            callback = object : CodexApiClient.StreamCallback {
                override fun onReasoningDelta(delta: String) {
                    runOnUiThread {
                        streamReasoningBuffer.append(delta)
                        chatAdapter.updateLastMessage(
                            if (streamContentBuffer.isEmpty()) "Pensando…" else streamContentBuffer.toString(),
                            streamReasoningBuffer.toString()
                        )
                        binding.rvMessages.scrollToPosition(messages.size - 1)
                    }
                }

                override fun onContentDelta(delta: String) {
                    runOnUiThread {
                        streamContentBuffer.append(delta)
                        chatAdapter.updateLastMessage(
                            streamContentBuffer.toString(),
                            streamReasoningBuffer.toString()
                        )
                        binding.rvMessages.scrollToPosition(messages.size - 1)
                    }
                }

                override fun onComplete(fullContent: String, fullReasoning: String) {
                    runOnUiThread {
                        activeCall = null
                        binding.btnSend.isEnabled = true
                        chatAdapter.updateLastMessage(fullContent, fullReasoning)

                        val finalMsg = ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = fullContent.ifEmpty { " " },
                            reasoningContent = fullReasoning
                        )

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

                override fun onError(error: Throwable) {
                    runOnUiThread {
                        activeCall = null
                        binding.btnSend.isEnabled = true
                        chatAdapter.updateLastMessage("⚠️ Error: " + error.message)
                    }
                }
            }
        )
    }

    private fun saveLocalSessionState(userText: String) {
        if (activeLocalSessionId == null) {
            val title = if (userText.length > 28) userText.take(28) + "…" else userText
            val session = LocalChatSession(title = title)
            activeLocalSessionId = session.id
            session.messages.addAll(messages)
            localChatRepo.saveSession(session)
        } else {
            val session = localChatRepo.getSession(activeLocalSessionId!!)
            if (session != null) {
                session.messages.clear()
                session.messages.addAll(messages)
                localChatRepo.saveSession(session)
            }
        }
        loadDrawerHistory()
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
        val etBaseUrl = view.findViewById<EditText>(R.id.etBaseUrl)
        val etApiKey = view.findViewById<EditText>(R.id.etApiKey)
        val btnPresetPC = view.findViewById<Button>(R.id.btnPresetPC)
        val btnPresetEmulator = view.findViewById<Button>(R.id.btnPresetEmulator)
        val btnTestConnection = view.findViewById<Button>(R.id.btnTestConnection)
        val tvStatus = view.findViewById<TextView>(R.id.tvConnectionStatus)
        val btnSave = view.findViewById<Button>(R.id.btnSaveSettings)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelSettings)

        etBaseUrl.setText(settings.baseUrl)
        etApiKey.setText(settings.apiKey)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .create()

        btnPresetPC.setOnClickListener {
            etBaseUrl.setText("http://192.168.1.6:8317/v1")
        }

        btnPresetEmulator.setOnClickListener {
            etBaseUrl.setText("http://10.0.2.2:8317/v1")
        }

        btnTestConnection.setOnClickListener {
            tvStatus.visibility = View.VISIBLE
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            tvStatus.text = "Conectando al servidor…"
            btnTestConnection.isEnabled = false

            val url = etBaseUrl.text.toString().trim()
            val key = etApiKey.text.toString().trim()

            thread {
                val res = modelsRepo.fetchLiveModels(url, key)
                runOnUiThread {
                    btnTestConnection.isEnabled = true
                    if (res.isSuccess) {
                        val count = res.getOrNull()?.size ?: 0
                        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.brand_green))
                        tvStatus.text = "✅ Conexión exitosa ($count modelos detectados)"
                    } else {
                        tvStatus.setTextColor(Color.parseColor("#EF4444"))
                        val err = res.exceptionOrNull()?.message ?: "Sin respuesta"
                        tvStatus.text = "❌ Error: $err"
                    }
                }
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        val btnCheckUpdates = view.findViewById<Button>(R.id.btnCheckUpdates)
        btnCheckUpdates.text = "🚀 Buscar Actualización (v" + BuildConfig.VERSION_NAME + ")"
        btnCheckUpdates.setOnClickListener {
            btnCheckUpdates.isEnabled = false
            btnCheckUpdates.text = "Comprobando en PC…"
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
                }
            )
        }

        btnSave.setOnClickListener {
            val newUrl = etBaseUrl.text.toString().trim()
            val newKey = etApiKey.text.toString().trim()
            settings.baseUrl = newUrl
            settings.apiKey = newKey
            Toast.makeText(this, "Ajustes guardados: $newUrl", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            syncLiveModels()
            loadRemoteConversations()
            checkForAppUpdates(silent = false)
        }

        dialog.show()
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

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeCall?.cancel()
        activeCall = null
        speechRecognizer?.destroy()
    }
}
