package com.arenaai.duagents.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arenaai.duagents.data.Artifact
import com.arenaai.duagents.ui.theme.ElenaColor

@Composable
fun SettingsDialog(
    hasKey: Boolean,
    keyFromBuild: Boolean,
    currentModel: String,
    onSaveKey: (String) -> Unit,
    onSaveModel: (String) -> Unit,
    onClearConversation: () -> Unit,
    onDismiss: () -> Unit
) {
    var keyInput by remember { mutableStateOf("") }
    var modelInput by remember { mutableStateOf(currentModel) }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResourceFor("settings_title"), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResourceFor("api_key_label") +
                            if (hasKey) {
                                if (keyFromBuild) "  ✓ (build-time key active)"
                                else "  ✓ (saved on this device)"
                            } else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    singleLine = true,
                    visualTransformation =
                        if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "hide" else "show", fontSize = 11.sp)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResourceFor("api_key_helper"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResourceFor("model_label"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = modelInput,
                    onValueChange = { modelInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResourceFor("model_helper"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(14.dp))
                TextButton(onClick = {
                    onClearConversation()
                }) {
                    Text(stringResourceFor("clear_conversation"), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (keyInput.isNotBlank()) onSaveKey(keyInput)
                if (modelInput.isNotBlank() && modelInput != currentModel) onSaveModel(modelInput)
                onDismiss()
            }) { Text(stringResourceFor("save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResourceFor("close")) }
        }
    )
}

@Composable
fun ToolsDialog(
    tools: List<ToolSummary>,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResourceFor("tools_title"), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResourceFor("tools_hint"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                tools.forEach { tool ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (tool.forged) ElenaColor.copy(alpha = 0.10f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        tool.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (tool.forged) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            stringResourceFor("forged_badge"),
                                            fontSize = 10.sp,
                                            color = ElenaColor,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Text(
                                    tool.description,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (tool.forged) {
                                IconButton(onClick = { onDelete(tool.name) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResourceFor("delete"),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.height(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResourceFor("close")) }
        }
    )
}

@Composable
fun ArtifactDialog(artifact: Artifact, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${artifact.title}  (${artifact.language})", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0A0D11),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = artifact.code,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFFD7E2EC),
                        modifier = Modifier
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                    )
                }
                if (copied) {
                    Text(
                        stringResourceFor("copy") + " ✓",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(artifact.code))
                copied = true
            }) { Text(stringResourceFor("copy")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResourceFor("close")) }
        }
    )
}
