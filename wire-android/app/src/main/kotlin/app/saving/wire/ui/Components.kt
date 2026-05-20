package app.saving.wire.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun WireTextField(
    value:         String,
    onValueChange: (String) -> Unit,
    placeholder:   String,
    modifier:      Modifier = Modifier,
    keyboardType:  KeyboardType = KeyboardType.Text
) {
    TextField(
        value          = value,
        onValueChange  = onValueChange,
        placeholder    = { Text(placeholder, color = Color.Gray) },
        modifier       = modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
        colors         = TextFieldDefaults.colors(
            focusedContainerColor   = Color.White.copy(alpha = 0.08f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
            focusedTextColor        = Color.White,
            unfocusedTextColor      = Color.White,
            focusedIndicatorColor   = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine      = true
    )
}

@Composable
fun WirePasswordField(
    value:         String,
    onValueChange: (String) -> Unit,
    placeholder:   String,
    modifier:      Modifier = Modifier
) {
    TextField(
        value                  = value,
        onValueChange          = onValueChange,
        placeholder            = { Text(placeholder, color = Color.Gray) },
        modifier               = modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
        colors                 = TextFieldDefaults.colors(
            focusedContainerColor   = Color.White.copy(alpha = 0.08f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
            focusedTextColor        = Color.White,
            unfocusedTextColor      = Color.White,
            focusedIndicatorColor   = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        visualTransformation   = PasswordVisualTransformation(),
        singleLine             = true
    )
}

@Composable
fun WirePrimaryButton(
    title:   String,
    loading: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick  = onClick,
        enabled  = enabled && !loading,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors   = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
        shape    = RoundedCornerShape(12.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier    = Modifier.size(20.dp),
                color       = Color.Black,
                strokeWidth = 2.dp
            )
        } else {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}
