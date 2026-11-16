//
//  LoginView.kt
//  app
//
//  Port of Amphora/UI/LoginView.swift — the M1/A2 login screen: email +
//  password → `AmphoraStore.Intent.Login`. Errors from the typed
//  PandoraCore layer are rendered verbatim from the store; no networking
//  or session logic lives here. Brand look: violet-lifted gradient
//  floor, gold sign-in button — the same tokens the iOS version uses,
//  ported one-for-one from Theme.swift.
//

package dev.personal.amphora.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.amphora.store.AmphoraStore
import dev.personal.amphora.store.AuthPhase

@Composable
fun LoginView(store: AmphoraStore) {
    val theme = Theme.tokens()
    val focusManager = LocalFocusManager.current

    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var emailFocused by rememberSaveable { mutableStateOf(false) }
    var passwordFocused by rememberSaveable { mutableStateOf(false) }

    val isSigningIn = store.authPhase == AuthPhase.signingIn

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.backgroundGradient),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            BrandHeader()

            store.lastErrorMessage?.let { message ->
                Text(
                    text = message,
                    color = Color(0xFFFF5A5A),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FieldCard(theme) {
                    LoginTextField(
                        value = email,
                        onValueChange = { email = it },
                        placeholder = "Email",
                        isPassword = false,
                        focused = emailFocused,
                        onFocusChange = { emailFocused = it },
                        onImeAction = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) },
                        enabled = !isSigningIn,
                    )
                }
                FieldCard(theme) {
                    LoginTextField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = "Password",
                        isPassword = true,
                        focused = passwordFocused,
                        onFocusChange = { passwordFocused = it },
                        onImeAction = {
                            focusManager.clearFocus()
                            store.send(AmphoraStore.Intent.Login(email, password))
                        },
                        enabled = !isSigningIn,
                    )
                }
            }

            SignInButton(
                isSigningIn = isSigningIn,
                onClick = {
                    focusManager.clearFocus()
                    store.send(AmphoraStore.Intent.Login(email, password))
                },
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/** The waveform badge, the wordmark, and the one-line prompt. */
@Composable
private fun BrandHeader() {
    val theme = Theme.tokens()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // iOS: `waveform.circle.fill` in Theme.brandGradient.
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(brush = theme.brandGradient, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = theme.accentGlyph,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            text = "Amphora",
            fontSize = 38.sp,
            fontWeight = FontWeight.Black,
            color = if (theme.isLight) Color(0xFF151021) else Color(0xFFF2EFFB),
        )
        Text(
            text = "Sign in with your Pandora account",
            fontSize = 15.sp,
            color = if (theme.isLight) Color(0xFF5B5570) else Color(0xFF9B94B4),
        )
    }
}

/** One rounded surface card holding a text field (iOS `fieldCard`). */
@Composable
private fun FieldCard(theme: Theme.Tokens, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.surface, RoundedCornerShape(14.dp))
            .border(1.dp, theme.stroke, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        content()
    }
}

/** The gold sign-in button — Theme.accent fill, accentGlyph label, the
 *  PressableButtonStyle dip (opacity + scale, spring). */
@Composable
private fun SignInButton(isSigningIn: Boolean, onClick: () -> Unit) {
    val theme = Theme.tokens()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) Pressable.pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Pressable.springStiffness),
        label = "press-scale",
    )
    val pressOpacity by animateFloatAsState(
        targetValue = if (isPressed) Pressable.pressedOpacity else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Pressable.springStiffness),
        label = "press-opacity",
    )

    Button(
        onClick = onClick,
        enabled = !isSigningIn,
        shape = RoundedCornerShape(Theme.radiusM),
        colors = ButtonDefaults.buttonColors(
            containerColor = theme.accent,
            contentColor = theme.accentGlyph,
        ),
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                alpha = pressOpacity
            }
            .semantics {
                contentDescription = if (isSigningIn) "Signing in" else "Sign In"
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isSigningIn) {
                CircularProgressIndicator(
                    color = theme.accentGlyph.copy(alpha = 0.85f),
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Text(
                    text = "Sign In",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** The text field itself — transparent on its card, iOS-matching metrics. */
@Composable
private fun LoginTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean,
    focused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onImeAction: () -> Unit,
    enabled: Boolean,
) {
    val theme = Theme.tokens()
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                placeholder,
                color = if (theme.isLight) Color(0xFF8A849E) else Color(0xFF6F6890),
            )
        },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Email,
        ),
        textStyle = TextStyle(
            fontSize = 17.sp,
            color = if (theme.isLight) Color(0xFF151021) else Color(0xFFF2EFFB),
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = theme.accent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = placeholder },
    )
}
