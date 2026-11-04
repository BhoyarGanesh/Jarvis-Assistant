package com.jarvis.assistant.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.domain.wakeword.WakePhrase
import com.jarvis.assistant.ui.theme.JarvisColors

@Composable
fun SettingSwitch(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = JarvisColors.TextSecondary) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = JarvisColors.Violet,
                uncheckedTrackColor = Color.White.copy(alpha = 0.08f),
                uncheckedBorderColor = JarvisColors.GlassBorder,
            ),
        )
    }
}

@Composable
fun SettingSlider(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    var local by rememberSaveable(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = JarvisColors.Cyan)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = JarvisColors.Violet,
                inactiveTrackColor = Color.White.copy(alpha = 0.1f),
            ),
        )
    }
}

@Composable
fun Divider() = HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Color.White.copy(alpha = 0.06f))

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = JarvisColors.Violet,
    unfocusedBorderColor = JarvisColors.GlassBorder,
    focusedContainerColor = Color.White.copy(alpha = 0.04f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.02f),
)

/** Backend URL + shared secret + connection test, shared by onboarding and settings. */
@Composable
fun BackendFields(
    initialUrl: String,
    initialSecret: String,
    connection: ConnectionTest,
    onUrlChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    onTest: (String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var secret by rememberSaveable { mutableStateOf(initialSecret) }
    var showSecret by rememberSaveable { mutableStateOf(false) }

    OutlinedTextField(
        value = url,
        onValueChange = {
            url = it
            onUrlChange(it)
        },
        label = { Text("Backend URL") },
        placeholder = { Text("http://192.168.1.20:8787") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        colors = fieldColors(),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = secret,
        onValueChange = {
            secret = it
            onSecretChange(it)
        },
        label = { Text("Shared secret") },
        singleLine = true,
        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { showSecret = !showSecret }) {
                Icon(
                    if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (showSecret) "Hide secret" else "Show secret",
                )
            }
        },
        colors = fieldColors(),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onTest(url) }, enabled = url.isNotBlank() && connection != ConnectionTest.Running) {
            Text("Test connection")
        }
        Spacer(Modifier.width(12.dp))
        when (connection) {
            ConnectionTest.Idle -> Unit
            ConnectionTest.Running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            is ConnectionTest.Done -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    if (connection.ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = if (connection.ok) JarvisColors.Success else JarvisColors.Error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(connection.message, style = MaterialTheme.typography.bodySmall, color = JarvisColors.TextSecondary)
            }
        }
    }
}

/** Type a new wake word; it is checked against the offline model before it is saved. */
@Composable
fun WakePhraseField(current: String, check: WakePhrase.Check?, enabled: Boolean, onSave: (String) -> Unit) {
    var text by rememberSaveable(current) { mutableStateOf(WakePhrase.display(current)) }
    val changed = WakePhrase.normalize(text) != current
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(WakePhrase.MAX_CHARS + 10) },
        label = { Text("Wake word") },
        placeholder = { Text("Jarvis, Friday, Hey Maya…") },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (changed) onSave(text) }),
        colors = fieldColors(),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onSave(text) }, enabled = enabled && changed) { Text("Use this word") }
        Spacer(Modifier.width(10.dp))
        if (current != WakePhrase.DEFAULT) {
            TextButton(onClick = { onSave(WakePhrase.DEFAULT) }, enabled = enabled) { Text("Back to Jarvis") }
        }
    }
    val (message, color) = when (check) {
        is WakePhrase.Check.Invalid -> check.reason to JarvisColors.Error
        is WakePhrase.Check.Ok -> (check.warning ?: "Now listening for “${WakePhrase.display(check.phrase)}”.") to
            (if (check.warning != null) JarvisColors.Warning else JarvisColors.Success)
        null -> "One to three words, offline and English only. Names with two or three syllables work best." to JarvisColors.TextMuted
    }
    Text(message, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(top = 4.dp))
}
