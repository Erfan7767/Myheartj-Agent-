package com.arenaai.duagents.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.arenaai.duagents.core.AgentPersona
import com.arenaai.duagents.core.AgentRegistry
import com.arenaai.duagents.data.AgentMode
import com.arenaai.duagents.data.Artifact
import com.arenaai.duagents.data.ConversationPhase
import com.arenaai.duagents.data.UiMessage
import com.arenaai.duagents.ui.theme.ElenaColor
import com.arenaai.duagents.ui.theme.IrfanColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.ui.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var openArtifact by remember { mutableStateOf<Artifact?>(null) }

    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onMicPermissionResult(granted) }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.onMicPermissionResult(true)
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.phase) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResourceFor("app_name"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = if (state.mode == AgentMode.COLLAB) {
                                stringResourceFor("tagline")
                            } else {
                                state.activeAgent.displayName + " • " +
                                        (state.emotion?.emoji ?: "")
                            },
                            fontSize = 11.sp,
                            color = agentColor(state.activeAgent).copy(alpha = 0.9f)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showTools = true }) {
                        Icon(Icons.Filled.Build, contentDescription = "Tools",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            ModeSelector(
                selected = state.mode,
                onSelect = viewModel::onModeChanged,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )

            if (!state.hasApiKey) {
                ApiKeyBanner(
                    onAddKey = { showSettings = true },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp, vertical = 8.dp
                )
            ) {
                if (state.messages.isEmpty()) {
                    item {
                        Text(
                            text = stringResourceFor("empty_chat"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 32.dp)
                        )
                    }
                }
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(message = message, onArtifactClick = { openArtifact = it })
                }
                if (state.phase == ConversationPhase.THINKING) {
                    item { TypingBubble(state) }
                }
            }

            StatusStrip(state = state, onStopVoice = viewModel::stopVoice)

            InputBar(
                state = state,
                onTextChanged = viewModel::onInputChanged,
                onSend = viewModel::onSend,
                onMicToggle = viewModel::onMicToggled
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
            hasKey = state.hasApiKey,
            keyFromBuild = state.apiKeyFromBuildConfig,
            currentModel = state.resolvedModel,
            onSaveKey = viewModel::saveApiKey,
            onSaveModel = viewModel::saveModel,
            onClearConversation = viewModel::clearConversation,
            onDismiss = { showSettings = false }
        )
    }
    if (showTools) {
        ToolsDialog(
            tools = state.tools,
            onDelete = viewModel::deleteTool,
            onDismiss = { showTools = false }
        )
    }
    openArtifact?.let { artifact ->
        ArtifactDialog(artifact = artifact, onDismiss = { openArtifact = null })
    }
}

// ── Mode selector ───────────────────────────────────────────────────────────

@Composable
private fun ModeSelector(
    selected: AgentMode,
    onSelect: (AgentMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeChip(stringResourceFor("mode_auto"), selected == AgentMode.AUTO, Color(0xFF8FB8DE)) {
            onSelect(AgentMode.AUTO)
        }
        ModeChip(stringResourceFor("mode_irfan"), selected == AgentMode.IRFAN, IrfanColor) {
            onSelect(AgentMode.IRFAN)
        }
        ModeChip(stringResourceFor("mode_elena"), selected == AgentMode.ELENA, ElenaColor) {
            onSelect(AgentMode.ELENA)
        }
        ModeChip(stringResourceFor("mode_both"), selected == AgentMode.COLLAB, Color(0xFFB58AE0)) {
            onSelect(AgentMode.COLLAB)
        }
    }
}

@Composable
private fun ModeChip(label: String, isSelected: Boolean, accent: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) accent.copy(alpha = 0.22f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        ),
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            color = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun ApiKeyBanner(onAddKey: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF3A2E14)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResourceFor("api_key_banner"),
                color = Color(0xFFE8C36A),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onAddKey) {
                Text(text = stringResourceFor("add_key"), color = Color(0xFFE8C36A))
            }
        }
    }
}

// ── Input bar ───────────────────────────────────────────────────────────────

@Composable
private fun InputBar(
    state: UiState,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onMicToggle: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = state.inputText,
                onValueChange = onTextChanged,
                placeholder = {
                    Text(stringResourceFor("hint_message"), fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                shape = RoundedCornerShape(22.dp),
                textStyle = MaterialTheme.typography.bodyMedium
            )
            IconButton(
                onClick = onSend,
                enabled = state.inputText.isNotBlank() &&
                        state.phase != ConversationPhase.THINKING
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResourceFor("send"),
                    tint = if (state.inputText.isNotBlank()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
            MicButton(listening = state.listening, agent = state.activeAgent, onClick = onMicToggle)
        }
    }
}

@Composable
private fun MicButton(listening: Boolean, agent: AgentPersona, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "micPulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "micScale"
    )
    val accent = agentColor(agent)
    Box(
        modifier = Modifier
            .size(52.dp)
            .scale(if (listening) pulse else 1f)
            .clip(CircleShape)
            .background(if (listening) accent else MaterialTheme.colorScheme.surfaceVariant)
            .border(
                1.dp,
                if (listening) accent.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = "Microphone",
            tint = if (listening) Color(0xFF06201C) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Status strip ────────────────────────────────────────────────────────────

@Composable
private fun StatusStrip(state: UiState, onStopVoice: () -> Unit) {
    if (state.phase == ConversationPhase.IDLE) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val text = when (state.phase) {
            ConversationPhase.LISTENING -> stringResourceFor("status_listening")
            ConversationPhase.THINKING ->
                if (state.mode == AgentMode.COLLAB) stringResourceFor("status_thinking_both")
                else stringResourceFor("status_thinking").format(
                    (state.phaseAgent ?: state.activeAgent).displayName
                )
            ConversationPhase.SPEAKING ->
                stringResourceFor("status_speaking").format(
                    (state.phaseAgent ?: state.activeAgent).displayName
                )
            else -> ""
        }
        Text(
            text = text,
            color = agentColor(state.phaseAgent ?: state.activeAgent).copy(alpha = 0.85f),
            fontSize = 12.sp,
            fontStyle = FontStyle.Italic,
            modifier = Modifier.weight(1f)
        )
        if (state.phase == ConversationPhase.SPEAKING) {
            TextButton(onClick = onStopVoice) {
                Icon(Icons.Filled.Stop, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(4.dp))
                Text(stringResourceFor("stop_voice"), fontSize = 12.sp)
            }
        }
    }
}

// ── Shared helpers ──────────────────────────────────────────────────────────

@Composable
internal fun agentColor(agent: AgentPersona): Color =
    if (agent.id == AgentRegistry.ELENA.id) ElenaColor else IrfanColor

/**
 * Small indirection so both values/strings.xml and values-ar/strings.xml are honored
 * through the normal resource pipeline.
 */
@Composable
internal fun stringResourceFor(key: String): String = when (key) {
    "app_name" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.app_name)
    "tagline" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.tagline)
    "hint_message" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.hint_message)
    "status_listening" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.status_listening)
    "status_thinking" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.status_thinking)
    "status_thinking_both" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.status_thinking_both)
    "status_speaking" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.status_speaking)
    "stop_voice" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.stop_voice)
    "send" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.send)
    "mode_auto" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.mode_auto)
    "mode_irfan" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.mode_irfan)
    "mode_elena" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.mode_elena)
    "mode_both" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.mode_both)
    "api_key_banner" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.api_key_banner)
    "add_key" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.add_key)
    "empty_chat" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.empty_chat)
    "close" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.close)
    "copy" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.copy)
    "forged_badge" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.forged_badge)
    "delete" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.delete)
    "tools_hint" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.tools_hint)
    "settings_title" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.settings_title)
    "tools_title" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.tools_title)
    "api_key_label" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.api_key_label)
    "api_key_helper" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.api_key_helper)
    "model_label" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.model_label)
    "model_helper" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.model_helper)
    "save" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.save)
    "clear_conversation" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.clear_conversation)
    "artifact_chip" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.artifact_chip)
    "mic_permission_rationale" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.mic_permission_rationale)
    "grant_permission" -> androidx.compose.ui.res.stringResource(com.arenaai.duagents.R.string.grant_permission)
    else -> key
}
