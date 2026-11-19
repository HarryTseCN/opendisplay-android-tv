package io.github.mohithdas.opendisplay.tv.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mohithdas.opendisplay.tv.R
import io.github.mohithdas.opendisplay.tv.net.ListenerPhase
import io.github.mohithdas.opendisplay.tv.net.NsdPhase
import io.github.mohithdas.opendisplay.tv.net.PhoneReceiver
import io.github.mohithdas.opendisplay.tv.settings.DisplaySelector
import io.github.mohithdas.opendisplay.tv.settings.FitMode
import io.github.mohithdas.opendisplay.tv.settings.KeepAwakePolicy
import io.github.mohithdas.opendisplay.tv.settings.PixelSize
import io.github.mohithdas.opendisplay.tv.settings.ResolutionChoice
import io.github.mohithdas.opendisplay.tv.settings.UiScaleChoice
import io.github.mohithdas.opendisplay.tv.update.UpdateManager

@Composable
fun SettingsDialog(
    receiver: PhoneReceiver,
    updateManager: UpdateManager,
    activity: Activity,
    onDismiss: () -> Unit,
) {
    val currentName by receiver.serviceName.collectAsState()
    val settings by receiver.settings.collectAsState()
    val listener by receiver.listenerState.collectAsState()
    val nsd by receiver.nsdState.collectAsState()
    val connected by receiver.connected.collectAsState()
    val selection by receiver.displaySelection.collectAsState()
    val decoder by receiver.decoderResolution.collectAsState()
    val supportedResolutions by receiver.supportedResolutions.collectAsState()
    val detected by receiver.detectedProfile.collectAsState()
    var draftName by remember(currentName) { mutableStateOf(currentName) }
    var editingName by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val saveNameFocusRequester = remember { FocusRequester() }
    val editNameFocusRequester = remember { FocusRequester() }
    val manualAddressLabel = stringResource(R.string.manual_address)
    val addressCopiedMessage = stringResource(R.string.address_copied)

    BackHandler(enabled = editingName) {
        draftName = currentName
        editingName = false
    }
    LaunchedEffect(editingName) {
        if (editingName) saveNameFocusRequester.requestFocus()
        else editNameFocusRequester.requestFocus()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxSize(),
            color = Color(0xFF09110E),
            contentColor = Color.White,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 40.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_and_diagnostics), style = MaterialTheme.typography.headlineMedium)
                    TvActionButton(stringResource(R.string.close), onDismiss)
                }
                Text(stringResource(R.string.settings_hint), color = Color.LightGray)

                if (editingName) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvActionButton(
                            stringResource(R.string.save),
                            onClick = {
                                receiver.setServiceName(draftName)
                                editingName = false
                            },
                            modifier = Modifier.focusRequester(saveNameFocusRequester),
                        )
                        TvActionButton(
                            stringResource(R.string.cancel),
                            onClick = {
                                draftName = currentName
                                editingName = false
                            },
                        )
                    }
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it.take(63) },
                        label = { Text(stringResource(R.string.receiver_name)) },
                        supportingText = { Text(stringResource(R.string.receiver_name_description)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
                    )
                } else {
                    DiagnosticRow(stringResource(R.string.receiver_name), currentName)
                    TvActionButton(
                        stringResource(R.string.edit_receiver_name),
                        onClick = { editingName = true },
                        modifier = Modifier.focusRequester(editNameFocusRequester),
                    )
                }

                SettingChoice(
                    stringResource(R.string.resolution),
                    resolutionLabel(settings.resolution),
                ) {
                    receiver.updateSettings(
                        settings.copy(resolution = nextValue(settings.resolution, supportedResolutions)),
                    )
                }
                SettingChoice(
                    stringResource(R.string.force_uhd),
                    stringResource(if (settings.forceUhdResolution) R.string.enabled else R.string.disabled),
                ) {
                    val nextForce = !settings.forceUhdResolution
                    // Un-forcing has to hand the choice back to detection: on a panel Android really
                    // reports as 4K the UHD selection stays, on one it only ever saw 1080p there is
                    // nothing to announce, so fall back to Auto rather than stream into a black box.
                    val detectedChoices = DisplaySelector.supportedChoices(detected)
                    val detectionStillOffersUhd = ResolutionChoice.UHD in detectedChoices
                    val nextResolution = when {
                        nextForce && settings.resolution == ResolutionChoice.AUTO -> ResolutionChoice.UHD
                        !nextForce && settings.resolution == ResolutionChoice.UHD && !detectionStillOffersUhd ->
                            ResolutionChoice.AUTO
                        else -> settings.resolution
                    }
                    receiver.updateSettings(settings.copy(forceUhdResolution = nextForce, resolution = nextResolution))
                }
                if (settings.forceUhdResolution) {
                    Text(stringResource(R.string.force_uhd_warning), color = Color(0xFFFFC857))
                }
                SettingChoice(stringResource(R.string.ui_scale), uiScaleLabel(settings.uiScale)) {
                    receiver.updateSettings(settings.copy(uiScale = nextValue(settings.uiScale, UiScaleChoice.entries)))
                }
                SettingChoice(stringResource(R.string.fit_mode), fitModeLabel(settings.fitMode)) {
                    receiver.updateSettings(settings.copy(fitMode = nextValue(settings.fitMode, FitMode.entries)))
                }
                if (settings.fitMode == FitMode.STRETCH) {
                    Text(stringResource(R.string.stretch_warning), color = Color(0xFFFFC857))
                }
                SettingChoice(
                    stringResource(R.string.keep_screen_awake),
                    awakeLabel(settings.keepAwakePolicy),
                ) {
                    receiver.updateSettings(
                        settings.copy(
                            keepAwakePolicy = nextValue(settings.keepAwakePolicy, KeepAwakePolicy.entries),
                        ),
                    )
                }
                SettingChoice(
                    stringResource(R.string.performance_overlay),
                    stringResource(
                        if (settings.performanceOverlay) R.string.performance_overlay_on
                        else R.string.performance_overlay_off,
                    ),
                ) {
                    receiver.updateSettings(settings.copy(performanceOverlay = !settings.performanceOverlay))
                }

                UpdatesSection(updateManager, activity)

                Text(
                    stringResource(R.string.diagnostics),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
                DiagnosticRow(stringResource(R.string.receiver_name), currentName)
                DiagnosticRow(
                    stringResource(R.string.manual_address),
                    listener.addressAndPort ?: stringResource(R.string.address_unavailable),
                )
                DiagnosticRow(stringResource(R.string.ip_address), listener.address ?: stringResource(R.string.not_available))
                DiagnosticRow(
                    stringResource(R.string.listener_port),
                    listener.port?.toString() ?: stringResource(R.string.not_available),
                )
                DiagnosticRow(stringResource(R.string.tcp_listener), listenerDiagnostic(listener.phase, listener.retrySeconds))
                DiagnosticRow(stringResource(R.string.bonjour_status), nsdDiagnostic(nsd.phase, nsd.registeredName, nsd.retrySeconds))
                DiagnosticRow(
                    stringResource(R.string.connection_status),
                    stringResource(if (connected) R.string.connection_connected else R.string.connection_waiting),
                )
                DiagnosticRow(
                    stringResource(R.string.resolution),
                    stringResource(R.string.dimension_format, selection.pixels.width, selection.pixels.height),
                )
                DiagnosticRow(stringResource(R.string.ui_scale), stringResource(R.string.scale_format, selection.scale))
                DiagnosticRow(stringResource(R.string.fit_mode), fitModeLabel(settings.fitMode))
                DiagnosticRow(stringResource(R.string.keep_screen_awake), awakeLabel(settings.keepAwakePolicy))
                DiagnosticRow(
                    stringResource(R.string.decoder_resolution),
                    decoder?.let { stringResource(R.string.dimension_format, it.width, it.height) }
                        ?: stringResource(R.string.not_available),
                )
                DiagnosticRow(stringResource(R.string.detected_panel_mode), dimensionText(detected.physicalMode))
                DiagnosticRow(stringResource(R.string.detected_window_bounds), dimensionText(detected.windowBounds))
                DiagnosticRow(
                    stringResource(R.string.detected_avc_maximum),
                    detected.avcHardMaximum?.let { dimensionText(it) } ?: stringResource(R.string.not_available),
                )
                DiagnosticRow(
                    stringResource(R.string.detected_avc_uhd_rate),
                    uhdRateText(detected.avcUhdFrameRate, detected.avcHardMaximum),
                )
                DiagnosticRow(
                    stringResource(R.string.override_active),
                    stringResource(if (settings.forceUhdResolution) R.string.enabled else R.string.disabled),
                )
                listener.addressAndPort?.let { address ->
                    TvActionButton(stringResource(R.string.copy_address), onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(manualAddressLabel, address))
                        Toast.makeText(context, addressCopiedMessage, Toast.LENGTH_SHORT).show()
                    })
                }
            }
        }
    }
}

@Composable
private fun SettingChoice(label: String, value: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) Color.White else Color(0xFF365248),
                RoundedCornerShape(12.dp),
            ),
        color = if (focused) Color(0xFF235B46) else Color(0xFF13251F),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(value, color = Color(0xFF8BE0B4))
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.LightGray)
        Text(value, modifier = Modifier.padding(start = 24.dp))
    }
}

@Composable
private fun dimensionText(size: PixelSize): String =
    stringResource(R.string.dimension_format, size.width, size.height)

/**
 * Whether the H.264 decoder itself claims 3840x2160, independent of the display mode. A rate here
 * means the 4K override is worth testing; `not_available` means the vendor query is unavailable and
 * `unsupported` means the panel can be lit by nothing at 4K no matter what it reports.
 */
@Composable
private fun uhdRateText(frameRate: Double?, hardMaximum: PixelSize?): String {
    val fps = frameRate?.toInt()
    return when {
        fps != null -> stringResource(R.string.detected_avc_uhd_format, fps)
        hardMaximum == null -> stringResource(R.string.not_available)
        else -> stringResource(R.string.detected_avc_uhd_none)
    }
}

private fun <T> nextValue(current: T, values: List<T>): T {
    if (values.isEmpty()) return current
    val index = values.indexOf(current)
    return values[(index + 1).mod(values.size)]
}

@Composable
private fun resolutionLabel(value: ResolutionChoice): String = stringResource(
    when (value) {
        ResolutionChoice.AUTO -> R.string.resolution_auto
        ResolutionChoice.UHD -> R.string.resolution_uhd
        ResolutionChoice.FULL_HD -> R.string.resolution_full_hd
        ResolutionChoice.HD -> R.string.resolution_hd
    },
)

@Composable
private fun uiScaleLabel(value: UiScaleChoice): String = stringResource(
    when (value) {
        UiScaleChoice.AUTO -> R.string.ui_scale_auto
        UiScaleChoice.ONE -> R.string.ui_scale_one
        UiScaleChoice.TWO -> R.string.ui_scale_two
    },
)

@Composable
private fun fitModeLabel(value: FitMode): String = stringResource(
    when (value) {
        FitMode.FIT -> R.string.fit_mode_fit
        FitMode.FILL -> R.string.fit_mode_fill
        FitMode.STRETCH -> R.string.fit_mode_stretch
        FitMode.NATIVE -> R.string.fit_mode_native
    },
)

@Composable
private fun awakeLabel(value: KeepAwakePolicy): String = stringResource(
    when (value) {
        KeepAwakePolicy.WHILE_CONNECTED -> R.string.awake_connected
        KeepAwakePolicy.WHILE_OPEN -> R.string.awake_open
        KeepAwakePolicy.NEVER -> R.string.awake_never
    },
)

@Composable
private fun listenerDiagnostic(phase: ListenerPhase, retrySeconds: Int): String = when (phase) {
    ListenerPhase.STARTING -> stringResource(R.string.listener_starting)
    ListenerPhase.WAITING_FOR_NETWORK -> stringResource(R.string.listener_waiting_network)
    ListenerPhase.LISTENING -> stringResource(R.string.listener_ready)
    ListenerPhase.RETRYING -> pluralStringResource(R.plurals.listener_retrying, retrySeconds, retrySeconds)
    ListenerPhase.PORTS_UNAVAILABLE -> stringResource(R.string.listener_failed)
    ListenerPhase.STOPPED -> stringResource(R.string.listener_stopped)
}

@Composable
private fun nsdDiagnostic(phase: NsdPhase, name: String?, retrySeconds: Int): String = when (phase) {
    NsdPhase.IDLE -> stringResource(R.string.nsd_idle)
    NsdPhase.REGISTERING -> stringResource(R.string.nsd_registering)
    NsdPhase.REGISTERED -> stringResource(R.string.nsd_registered, name ?: stringResource(R.string.app_name))
    NsdPhase.RETRYING -> pluralStringResource(R.plurals.nsd_retrying, retrySeconds, retrySeconds)
    NsdPhase.UNAVAILABLE -> stringResource(R.string.nsd_failed)
}
