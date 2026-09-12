package com.codex.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
    private var activeCwd: String = "C:\\Users\\Admin\\Desktop"
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
    private var codexPollActive = false
    private var codexPollJob: Thread? = null

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
        skillsRepo = SkillsRepository(this)
        mcpRegistry = com.codex.chat.core.mcp.McpRegistry(this)
        updateManager = AppUpdateManager(this)
        localChatRepo = LocalChatRepository(this)

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

        slashAdapter = SlashCommandsAdapter(emptyList()) { cmd ->
            onSlashCommandSelected(cmd)
        }
        binding.rvSlashSuggestions.layoutManager = LinearLayoutManager(this)
        binding.rvSlashSuggestions.adapter = slashAdapter
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

        binding.btnWebSearchToggle.setOnClickListener {
            setWebSearchActive(!isWebSearchActive)
        }

        binding.btnRemoveAttachment.setOnClickListener {
            pendingAttachment = null
            setWebSearchActive(false)
            setPythonModeActive(false)
            binding.attachmentPreviewBar.visibility = View.GONE
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
                    binding.btnMic.visibility = View.GONE
                    binding.btnSend.visibility = View.VISIBLE
                    binding.btnSend.alpha = 1.0f
                } else {
                    binding.btnMic.visibility = View.VISIBLE
                    binding.btnSend.visibility = View.GONE
                    binding.btnSend.alpha = 0.5f
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
            binding.attachmentPreviewBar.visibility = View.VISIBLE
            binding.etMessage.hint = "Pregunta lo que sea en internet..."
            Toast.makeText(this, "🌐 Búsqueda Web activada: escribe tu pregunta normalmente", Toast.LENGTH_SHORT).show()
        } else {
            binding.btnWebSearchToggle.setColorFilter(Color.parseColor("#8E8E8E"))
            if (pendingAttachment == null && !isPythonModeActive) {
                binding.attachmentPreviewBar.visibility = View.GONE
                binding.etMessage.hint = if (currentMode == AppMode.CHATGPT_NORMAL) "Mensaje a ChatGPT..." else "Mensaje a Codex Desktop..."
            }
        }
    }

    private fun setPythonModeActive(active: Boolean) {
        isPythonModeActive = active
        if (active) {
            setWebSearchActive(false)
            binding.tvAttachmentIcon.text = "🐍"
            binding.tvAttachmentName.text = "Modo Python E2B Cloud (MicroVM en la nube)"
            binding.attachmentPreviewBar.visibility = View.VISIBLE
            binding.etMessage.hint = "Escribe código Python para ejecutar en la nube..."
            Toast.makeText(this, "🐍 Modo Python Cloud activado: escribe tu código directamente", Toast.LENGTH_SHORT).show()
        } else {
            if (pendingAttachment == null && !isWebSearchActive) {
                binding.attachmentPreviewBar.visibility = View.GONE
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
            binding.activeSkillBar.visibility = View.VISIBLE
            binding.tvActiveSkillIcon.text = activeSkill?.iconEmoji ?: "⚡"
            binding.tvActiveSkillName.text = "Skill: " + activeSkill?.name
        } else {
            binding.activeSkillBar.visibility = View.GONE
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
        binding.slashSuggestionsContainer.visibility = View.GONE
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
                binding.slashSuggestionsContainer.visibility = View.GONE
            }
        }
    }

    private fun updateSlashSuggestions(input: String) {
        if (!input.startsWith("/") || input.contains(" ")) {
            binding.slashSuggestionsContainer.visibility = View.GONE
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
            binding.slashSuggestionsContainer.visibility = View.VISIBLE
        } else {
            binding.slashSuggestionsContainer.visibility = View.GONE
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
        val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val sb = StringBuilder("### 🛡️ Estado de Permisos del APK (Nivel sin Root):\n\n")
        sb.append(if (hasAllFiles) "✅" else "❌").append(" **Acceso a Todos los Archivos (`MANAGE_EXTERNAL_STORAGE`):** ").append(if (hasAllFiles) "Concedido (Moverse y crear en Download/Documents)" else "Pendiente de activación").append("\n")
        sb.append(if (hasAudio) "✅" else "❌").append(" **Micrófono y Audio (`RECORD_AUDIO`):** ").append(if (hasAudio) "Concedido" else "Pendiente").append("\n")
        sb.append(if (hasCamera) "✅" else "❌").append(" **Cámara y Linterna (`CAMERA` / `FLASHLIGHT`):** ").append(if (hasCamera) "Concedido" else "Pendiente").append("\n")
        sb.append(if (hasFineLoc) "✅" else "❌").append(" **Ubicación GPS (`ACCESS_FINE_LOCATION`):** ").append(if (hasFineLoc) "Concedido" else "Pendiente").append("\n")
        sb.append(if (hasNotifications) "✅" else "❌").append(" **Notificaciones (`POST_NOTIFICATIONS`):** ").append(if (hasNotifications) "Concedido" else "Pendiente").append("\n\n")

        val allGranted = hasAllFiles && hasAudio && hasCamera && hasFineLoc && hasNotifications
        if (allGranted) {
            sb.append("✨ **¡Todos los permisos del sistema están concedidos!** El APK tiene acceso pleno a hardware y almacenamiento.")
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            neededPerms.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            neededPerms.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            neededPerms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            neededPerms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                neededPerms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                neededPerms.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                neededPerms.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                neededPerms.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                neededPerms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                neededPerms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
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
            binding.btnMic.visibility = View.GONE
            binding.btnSend.visibility = View.VISIBLE
            binding.btnSend.alpha = 1.0f

            Toast.makeText(this, "Adjuntado: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error leyendo archivo: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty() && pendingAttachment == null) return

        // Intercept slash commands (/ Claude Style)
        if (text.startsWith("/")) {
            binding.slashSuggestionsContainer.visibility = View.GONE
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
        binding.attachmentPreviewBar.visibility = View.GONE
        binding.etMessage.setText("")
        binding.btnSend.visibility = View.GONE
        binding.btnMic.visibility = View.VISIBLE
        binding.rvMessages.scrollToPosition(messages.size - 1)

        val assistantMsg = ChatMessage(role = MessageRole.ASSISTANT, content = "Pensando…", isStreaming = true)
        chatAdapter.addMessage(assistantMsg)
        binding.rvMessages.scrollToPosition(messages.size - 1)

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
        chatAdapter.updateLastMessage("⚡ Enviando a Codex Desktop en PC…")
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
        codexPollActive = false
        codexPollJob?.interrupt()

        codexPollActive = true
        codexPollJob = thread {
            var currentOffset = startOffset
            val reasoningBuffer = StringBuilder()
            val contentBuffer = StringBuilder()
            var activeStatus = "running"
            var completedCount = 0

            while (codexPollActive && (activeStatus == "running" || completedCount < 3)) {
                try {
                    Thread.sleep(700)
                    if (!codexPollActive) break

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

            codexPollActive = false
            runOnUiThread {
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
        val streamContentBuffer = StringBuilder()
        val streamReasoningBuffer = StringBuilder()
        val activeModel = modelsRepo.getModelById(settings.selectedModelId)

        // Build outgoing messages (user/assistant turns)
        val outgoingMessages = messages.dropLast(1).toMutableList()

        activeCall = apiClient.executeStream(
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
                override fun onReasoningDelta(delta: String) {
                    if (delta.isBlank() || delta == "null") return
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
                    if (delta.isEmpty() || delta == "null") return
                    runOnUiThread {
                        streamContentBuffer.append(delta)
                        var displayContent = streamContentBuffer.toString()
                        while (displayContent.startsWith("null")) {
                            displayContent = displayContent.substring(4).trimStart()
                        }
                        chatAdapter.updateLastMessage(
                            if (displayContent.isEmpty()) "Pensando…" else displayContent,
                            streamReasoningBuffer.toString()
                        )
                        binding.rvMessages.scrollToPosition(messages.size - 1)
                    }
                }

                override fun onComplete(fullContent: String, fullReasoning: String) {
                    var cleanContent = fullContent
                    while (cleanContent.startsWith("null")) {
                        cleanContent = cleanContent.substring(4).trimStart()
                    }
                    var cleanReasoning = fullReasoning
                    while (cleanReasoning.startsWith("null")) {
                        cleanReasoning = cleanReasoning.substring(4).trimStart()
                    }

                    runOnUiThread {
                        activeCall = null
                        binding.btnSend.isEnabled = true
                        chatAdapter.updateLastMessage(cleanContent, cleanReasoning)

                        val finalMsg = ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = cleanContent.ifEmpty { " " },
                            reasoningContent = cleanReasoning
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
        val etE2bApiKey = view.findViewById<EditText>(R.id.etE2bApiKey)
        val btnPresetPC = view.findViewById<Button>(R.id.btnPresetPC)
        val btnPresetEmulator = view.findViewById<Button>(R.id.btnPresetEmulator)
        val btnTestConnection = view.findViewById<Button>(R.id.btnTestConnection)
        val tvStatus = view.findViewById<TextView>(R.id.tvConnectionStatus)
        val btnSave = view.findViewById<Button>(R.id.btnSaveSettings)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelSettings)

        etBaseUrl.setText(settings.baseUrl)
        etApiKey.setText(settings.apiKey)
        etE2bApiKey.setText(settings.e2bApiKey)

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
            val newE2bKey = etE2bApiKey.text.toString().trim()
            settings.baseUrl = newUrl
            settings.apiKey = newKey
            if (newE2bKey.isNotEmpty()) {
                settings.e2bApiKey = newE2bKey
            }
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
