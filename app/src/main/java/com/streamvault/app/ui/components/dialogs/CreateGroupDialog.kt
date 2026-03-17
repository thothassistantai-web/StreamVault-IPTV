package com.streamvault.app.ui.components.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated

@Composable
fun CreateGroupDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by rememberSaveable { mutableStateOf("") }
    var canInteract by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        keyboardController?.show()
        kotlinx.coroutines.delay(500)
        canInteract = true
    }

    Dialog(
        onDismissRequest = {
            keyboardController?.hide()
            onDismissRequest()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.42f),
            shape = RoundedCornerShape(24.dp),
            colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)
        ) {
            Column(
                modifier = Modifier
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Primary.copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.add_group_create_new_btn),
                    style = MaterialTheme.typography.headlineMedium,
                    color = OnSurface
                )
                Text(
                    text = stringResource(R.string.library_saved_manage_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    label = { Text(stringResource(R.string.add_group_name_hint)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface,
                        cursorColor = Primary,
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Primary.copy(alpha = 0.55f),
                        focusedLabelColor = Primary,
                        unfocusedLabelColor = OnSurfaceDim,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val normalized = value.trim()
                            if (normalized.isNotEmpty()) {
                                keyboardController?.hide()
                                onConfirm(normalized)
                            }
                        }
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            if (canInteract) {
                                keyboardController?.hide()
                                onDismissRequest()
                            }
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.08f),
                            contentColor = OnSurface
                        )
                    ) {
                        Text(stringResource(R.string.add_group_cancel))
                    }
                    Button(
                        onClick = {
                            val normalized = value.trim()
                            if (canInteract && normalized.isNotEmpty()) {
                                keyboardController?.hide()
                                onConfirm(normalized)
                            }
                        },
                        enabled = canInteract && value.trim().isNotEmpty(),
                        colors = ButtonDefaults.colors(
                            containerColor = Primary,
                            contentColor = Color.White
                        )
                    ) {
                        Text(stringResource(R.string.add_group_create))
                    }
                }
            }
        }
    }
}
