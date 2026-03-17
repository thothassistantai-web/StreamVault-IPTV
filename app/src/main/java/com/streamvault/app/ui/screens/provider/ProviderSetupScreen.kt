package com.streamvault.app.ui.screens.provider

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.streamvault.data.util.ProviderInputSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.tv.material3.*
import com.streamvault.app.ui.components.shell.AppSectionHeader
import com.streamvault.app.ui.components.shell.StatusPill
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.theme.*
import androidx.compose.ui.res.stringResource
import com.streamvault.app.R

@Composable
fun ProviderSetupScreen(
    onProviderAdded: () -> Unit,
    editProviderId: Long? = null,
    viewModel: ProviderSetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val knownLocalM3uUrls by viewModel.knownLocalM3uUrls.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var name by rememberSaveable { mutableStateOf("") }
    var m3uUrl by rememberSaveable { mutableStateOf("") }
    var fileImportError by rememberSaveable { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        var fileName = "Local_Playlist"
                        val cursor = context.contentResolver.query(uri, null, null, null, null)
                        cursor?.use {
                            if (it.moveToFirst()) {
                                val displayNameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (displayNameIndex != -1) {
                                    val displayName = it.getString(displayNameIndex)
                                    if (displayName.contains(".")) {
                                        fileName = displayName.substringBeforeLast(".")
                                    } else {
                                        fileName = displayName
                                    }
                                }
                            }
                        }

                        val outFile = java.io.File(context.filesDir, "m3u_${System.currentTimeMillis()}.m3u")
                        outFile.outputStream().use { out ->
                            inputStream.copyTo(out)
                        }
                        cleanupOldImportedM3uFiles(
                            filesDir = context.filesDir,
                            protectedFileUris = knownLocalM3uUrls + "file://${outFile.absolutePath}",
                            keepLatest = 20
                        )

                        withContext(Dispatchers.Main) {
                            m3uUrl = "file://${outFile.absolutePath}"
                            if (name.isEmpty()) {
                                name = ProviderInputSanitizer.sanitizeProviderNameForEditing(fileName)
                            }
                            fileImportError = null
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            fileImportError = context.getString(R.string.setup_file_import_failed)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        fileImportError = resolveFileImportError(context, e)
                    }
                }
            }
        }
    }

    LaunchedEffect(knownLocalM3uUrls) {
        cleanupOldImportedM3uFiles(
            filesDir = context.filesDir,
            protectedFileUris = knownLocalM3uUrls,
            keepLatest = 20
        )
    }

    // Navigate on success
    LaunchedEffect(uiState.loginSuccess) {
        if (uiState.loginSuccess) {
            val previousLocal = uiState.m3uUrl.takeIf { it.startsWith("file://") }
            val selectedLocal = m3uUrl.takeIf { it.startsWith("file://") }
            val protectedUris = buildSet {
                knownLocalM3uUrls.forEach { knownUri ->
                    if (knownUri != previousLocal || previousLocal == selectedLocal) {
                        add(knownUri)
                    }
                }
                selectedLocal?.let(::add)
            }

            cleanupOldImportedM3uFiles(
                filesDir = context.filesDir,
                protectedFileUris = protectedUris,
                keepLatest = 20
            )
            onProviderAdded()
        }
    }

    // Auto-skip logic moved to MainActivity/Splash - preventing redirect loop here
    // LaunchedEffect(uiState.hasExistingProvider) {
    //    if (uiState.hasExistingProvider) onProviderAdded()
    // }

    var selectedTab by rememberSaveable { mutableStateOf(0) } // 0 = Xtream, 1 = M3U
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val providerNameFocusRequester = remember { FocusRequester() }
    val m3uUrlTabFocusRequester = remember { FocusRequester() }
    val m3uFileTabFocusRequester = remember { FocusRequester() }
    val m3uValueFocusRequester = remember { FocusRequester() }
    val m3uSubmitFocusRequester = remember { FocusRequester() }

    // Initialize state from ViewModel if editing
    LaunchedEffect(editProviderId) {
        if (editProviderId != null) {
            viewModel.loadProvider(editProviderId)
        }
    }

    // Reflect ViewModel state in local state once loaded
    LaunchedEffect(uiState.isEditing, uiState.existingProviderId) {
        if (uiState.isEditing) {
            selectedTab = uiState.selectedTab
            name = uiState.name
            serverUrl = uiState.serverUrl
            username = uiState.username
            password = uiState.password
            m3uUrl = uiState.m3uUrl
        }
    }

    // Keep local state for editing
    // var selectedTab by remember { mutableStateOf(0) } - moved below to use state driven init? No, we need local state for editing.
    // We already have the vars defined above. 

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(BackgroundDeep, Background, Surface)
                )
            )
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                modifier = Modifier.width(240.dp),
                shape = RoundedCornerShape(22.dp),
                colors = SurfaceDefaults.colors(containerColor = Surface.copy(alpha = 0.92f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (uiState.isEditing) {
                            stringResource(R.string.setup_edit_provider)
                        } else {
                            stringResource(R.string.setup_provider_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )

                    Text(
                        text = stringResource(R.string.setup_shell_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(
                            label = stringResource(R.string.setup_xtream),
                            containerColor = PrimaryGlow
                        )
                        StatusPill(
                            label = stringResource(R.string.setup_m3u),
                            containerColor = SurfaceHighlight
                        )
                    }

                    SetupInfoLine(
                        title = stringResource(R.string.setup_info_xtream_title),
                        body = stringResource(R.string.setup_info_xtream_body)
                    )
                    SetupInfoLine(
                        title = stringResource(R.string.setup_info_m3u_title),
                        body = stringResource(R.string.setup_info_m3u_body)
                    )
                    SetupInfoLine(
                        title = stringResource(R.string.setup_info_manage_title),
                        body = stringResource(R.string.setup_info_manage_body)
                    )
                }
            }

            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(22.dp),
                border = Border(
                    border = BorderStroke(1.dp, SurfaceHighlight),
                    shape = RoundedCornerShape(22.dp)
                ),
                colors = SurfaceDefaults.colors(containerColor = SurfaceElevated.copy(alpha = 0.95f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AppSectionHeader(
                        title = if (uiState.isEditing) {
                            stringResource(R.string.setup_edit_provider)
                        } else {
                            stringResource(R.string.setup_add_desc)
                        },
                        subtitle = if (uiState.isEditing) {
                            stringResource(R.string.setup_update_desc)
                        } else {
                            stringResource(R.string.setup_form_subtitle)
                        }
                    )

                    Text(
                        text = stringResource(R.string.setup_source_type_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextTertiary
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!uiState.isEditing || uiState.selectedTab == 0) {
                            TabButton(
                                text = stringResource(R.string.setup_xtream),
                                isSelected = selectedTab == 0,
                                onClick = { if (!uiState.isEditing) selectedTab = 0 }
                            )
                        }
                        if (!uiState.isEditing || uiState.selectedTab == 1) {
                            TabButton(
                                text = stringResource(R.string.setup_m3u),
                                isSelected = selectedTab == 1,
                                onClick = { if (!uiState.isEditing) selectedTab = 1 }
                            )
                        }
                    }

                    TvTextField(
                        value = name,
                        onValueChange = { name = ProviderInputSanitizer.sanitizeProviderNameForEditing(it) },
                        label = stringResource(R.string.setup_name_hint),
                        focusRequester = providerNameFocusRequester,
                        onMoveUp = { false },
                        onMoveDown = {
                            if (selectedTab == 1) {
                                if (uiState.m3uTab == 0) {
                                    m3uUrlTabFocusRequester.requestFocus()
                                } else {
                                    m3uFileTabFocusRequester.requestFocus()
                                }
                                true
                            } else {
                                false // Let system find next item (Server URL)
                            }
                        }
                    )

                    when (selectedTab) {
                        0 -> {
                            TvTextField(
                                value = serverUrl,
                                onValueChange = { serverUrl = ProviderInputSanitizer.sanitizeUrlForEditing(it) },
                                label = stringResource(R.string.setup_server_hint)
                            )
                            TvTextField(
                                value = username,
                                onValueChange = { username = ProviderInputSanitizer.sanitizeUsernameForEditing(it) },
                                label = stringResource(R.string.setup_user_hint)
                            )
                            TvTextField(
                                value = password,
                                onValueChange = { password = ProviderInputSanitizer.sanitizePasswordForEditing(it) },
                                label = stringResource(R.string.setup_pass_hint),
                                isPassword = true
                            )

                            ActionButton(
                                text = if (uiState.isLoading) {
                                    stringResource(R.string.setup_connecting)
                                } else if (uiState.isEditing) {
                                    stringResource(R.string.setup_save)
                                } else {
                                    stringResource(R.string.setup_login)
                                },
                                enabled = !uiState.isLoading,
                                onClick = {
                                    viewModel.loginXtream(serverUrl, username, password, name)
                                }
                            )
                        }

                        1 -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TabButton(
                                    text = stringResource(R.string.setup_tab_url),
                                    isSelected = uiState.m3uTab == 0,
                                    onClick = { viewModel.updateM3uTab(0) },
                                    onFocused = { viewModel.updateM3uTab(0) },
                                    focusRequester = m3uUrlTabFocusRequester,
                                    onMoveUp = { 
                                        providerNameFocusRequester.requestFocus()
                                        true
                                    },
                                    onMoveDown = { 
                                        m3uValueFocusRequester.requestFocus()
                                        true
                                    }
                                )
                                TabButton(
                                    text = stringResource(R.string.setup_tab_file),
                                    isSelected = uiState.m3uTab == 1,
                                    onClick = { viewModel.updateM3uTab(1) },
                                    onFocused = { viewModel.updateM3uTab(1) },
                                    focusRequester = m3uFileTabFocusRequester,
                                    onMoveUp = { 
                                        providerNameFocusRequester.requestFocus()
                                        true
                                    },
                                    onMoveDown = { 
                                        m3uValueFocusRequester.requestFocus()
                                        true
                                    }
                                )
                            }

                            if (uiState.m3uTab == 0) {
                                TvTextField(
                                    value = m3uUrl,
                                    onValueChange = { m3uUrl = ProviderInputSanitizer.sanitizeUrlForEditing(it) },
                                    label = stringResource(R.string.setup_m3u_hint),
                                    focusRequester = m3uValueFocusRequester,
                                    onMoveUp = { 
                                        m3uUrlTabFocusRequester.requestFocus()
                                        true
                                    },
                                    onMoveDown = { 
                                        m3uSubmitFocusRequester.requestFocus()
                                        true
                                    }
                                )
                            } else {
                                FileSelectorCard(
                                    fileName = if (m3uUrl.startsWith("file://")) {
                                        m3uUrl.substringAfterLast("/")
                                    } else {
                                        null
                                    },
                                    fileSelectedHint = stringResource(R.string.setup_file_replace_hint),
                                    emptySelectionTitle = stringResource(R.string.setup_file_select_title),
                                    emptySelectionHint = stringResource(R.string.setup_file_browse_hint),
                                    onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                                    focusRequester = m3uValueFocusRequester,
                                    onMoveUp = { 
                                        m3uFileTabFocusRequester.requestFocus()
                                        true
                                    },
                                    onMoveDown = { 
                                        m3uSubmitFocusRequester.requestFocus()
                                        true
                                    }
                                )

                                fileImportError?.let { importError ->
                                    Text(
                                        text = importError,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ErrorColor
                                    )
                                }
                            }

                            ActionButton(
                                text = if (uiState.isLoading) {
                                    stringResource(R.string.setup_validating)
                                } else if (uiState.isEditing) {
                                    stringResource(R.string.setup_save)
                                } else {
                                    stringResource(R.string.setup_add)
                                },
                                enabled = !uiState.isLoading,
                                onClick = { viewModel.addM3u(m3uUrl, name) },
                                focusRequester = m3uSubmitFocusRequester,
                                onMoveUp = { 
                                    m3uValueFocusRequester.requestFocus()
                                    true
                                }
                            )
                        }
                    }

                    uiState.validationError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ErrorColor
                        )
                    }

                    uiState.error?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ErrorColor
                        )
                    }
                }
            }
        }
    }

    if (uiState.syncProgress != null) {
        SyncProgressDialog(message = uiState.syncProgress!!)
    }
}

@Composable
private fun SetupInfoLine(
    title: String,
    body: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
    }
}

@Composable
fun SyncProgressDialog(message: String) {
    PremiumDialog(
        title = stringResource(R.string.settings_syncing_title),
        subtitle = stringResource(R.string.settings_syncing_subtitle),
        onDismissRequest = {},
        widthFraction = 0.32f,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(color = Primary)

                StatusPill(
                    label = stringResource(R.string.settings_syncing_btn),
                    containerColor = PrimaryGlow
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnBackground,
                    textAlign = TextAlign.Center
                )
            }
        }
    )
}

@Composable
private fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isPassword: Boolean = false,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onMoveUp: (() -> Boolean)? = null,
    onMoveDown: (() -> Boolean)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val internalFocusRequester = focusRequester ?: remember { FocusRequester() }
    val textFieldFocusRequester = remember { FocusRequester() }

    val borderColor = if (isFocused) Primary else SurfaceHighlight
    val bgColor = if (isFocused) Surface else SurfaceElevated
    val borderWidth = if (isFocused) 2.dp else 1.dp

    // Use Box as the focusable container (Mode 1: Highlighted)
    // BasicTextField will be Mode 2 (Edit Mode)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(bgColor, RoundedCornerShape(8.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
            .focusRequester(internalFocusRequester)
            .focusable() // Make the container focusable
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                    when (event.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            onMoveUp?.invoke() ?: false
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            onMoveDown?.invoke() ?: false
                        }
                        android.view.KeyEvent.KEYCODE_ENTER,
                        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER -> {
                            // Click to enter edit mode (Mode 2)
                            textFieldFocusRequester.requestFocus()
                            true 
                        }
                        else -> false
                    }
                } else false
            }
            .clickable { 
                internalFocusRequester.requestFocus()
                textFieldFocusRequester.requestFocus()
            }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        // Placeholder text
        if (value.isEmpty() && !isFocused) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
        }

        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(textFieldFocusRequester),
            textStyle = MaterialTheme.typography.bodySmall.copy(color = OnBackground),
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Primary)
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onMoveUp: (() -> Boolean)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val internalFocusRequester = focusRequester ?: remember { FocusRequester() }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = tween(150),
        label = "btnScale"
    )

    Surface(
        onClick = { if (enabled) onClick() },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .scale(scale)
            .focusRequester(internalFocusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                    onMoveUp != null
                ) {
                    onMoveUp()
                } else false
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (enabled) Primary else SurfaceHighlight,
            focusedContainerColor = if (enabled) PrimaryLight else SurfaceHighlight
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (enabled) PrimaryLight else SurfaceHighlight)),
            focusedBorder = Border(BorderStroke(2.dp, FocusBorder))
        )
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) Color.White else OnSurfaceDim
            )
        }
    }
}

@Composable
private fun FileSelectorCard(
    fileName: String?,
    fileSelectedHint: String,
    emptySelectionTitle: String,
    emptySelectionHint: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onMoveUp: (() -> Boolean)? = null,
    onMoveDown: (() -> Boolean)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val internalFocusRequester = focusRequester ?: remember { FocusRequester() }

    val borderColor = if (isFocused) Primary else SurfaceHighlight
    val bgColor = if (isFocused) Surface else SurfaceElevated
    val borderWidth = if (isFocused) 2.dp else 1.dp

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .focusRequester(internalFocusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                    when (event.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            onMoveUp?.invoke() ?: false
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            onMoveDown?.invoke() ?: false
                        }
                        else -> false
                    }
                } else false
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(borderWidth, borderColor)),
            focusedBorder = Border(BorderStroke(borderWidth, borderColor))
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = bgColor,
            focusedContainerColor = bgColor
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (fileName != null) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnBackground,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = fileSelectedHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            } else {
                Text(
                    text = emptySelectionTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnBackground
                )
                Text(
                    text = emptySelectionHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
        }
    }
}

@Composable
private fun TabButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onFocused: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onMoveUp: (() -> Boolean)? = null,
    onMoveDown: (() -> Boolean)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val internalFocusRequester = focusRequester ?: remember { FocusRequester() }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(internalFocusRequester)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                    when (event.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            onMoveUp?.invoke() ?: false
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            onMoveDown?.invoke() ?: false
                        }
                        else -> false
                    }
                } else false
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.2f) else Surface,
            focusedContainerColor = SurfaceHighlight
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (isSelected) Primary.copy(alpha = 0.4f) else SurfaceHighlight)),
            focusedBorder = Border(BorderStroke(2.dp, FocusBorder))
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isSelected) Primary else if (isFocused) TextPrimary else OnSurface,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
        )
    }
}

private fun cleanupOldImportedM3uFiles(
    filesDir: java.io.File,
    protectedFileUris: Set<String>,
    keepLatest: Int
) {
    val protectedPaths = protectedFileUris
        .mapNotNull { uri ->
            uri.removePrefix("file://").takeIf { it.isNotBlank() }
        }
        .toSet()

    val importedFiles = filesDir
        .listFiles { file -> file.isFile && file.name.startsWith("m3u_") && file.name.endsWith(".m3u") }
        ?.sortedByDescending { it.lastModified() }
        ?: return

    importedFiles
        .drop(keepLatest)
        .forEach { staleFile ->
            if (staleFile.absolutePath !in protectedPaths) {
                runCatching { staleFile.delete() }
            }
        }
}

private fun resolveFileImportError(
    context: android.content.Context,
    error: Throwable
): String {
    val message = error.message.orEmpty()
    val isStorageFull = message.contains("ENOSPC", ignoreCase = true) ||
        message.contains("no space", ignoreCase = true) ||
        message.contains("space left", ignoreCase = true)

    return if (isStorageFull) {
        context.getString(R.string.setup_file_import_storage_full)
    } else {
        context.getString(R.string.setup_file_import_failed)
    }
}
