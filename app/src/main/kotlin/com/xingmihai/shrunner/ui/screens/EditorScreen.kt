package com.xingmihai.shrunner.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xingmihai.shrunner.ui.EditorViewModel
import com.xingmihai.shrunner.util.highlightShell

@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    scriptId: String?,
    onSaved: (String) -> Unit,
    onRun: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(scriptId) { viewModel.load(scriptId) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::onNameChange,
            label = { Text("脚本名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onSaved(viewModel.save()) }) { Text("保存") }
            OutlinedButton(
                onClick = { state.id?.let(onRun) },
                enabled = state.id != null
            ) { Text("保存并运行") }
        }
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        val transformation = remember(state.text) { ShellHighlightTransformation(state.text) }
        val scroll = rememberScrollState()
        BasicTextField(
            value = state.text,
            onValueChange = viewModel::onTextChange,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(8.dp),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = transformation
        )
    }
}

/** 只在显示层做关键字着色，不改字符，因此偏移量一一对应。 */
private class ShellHighlightTransformation(private val source: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlightShell(text.text), OffsetMapping.Identity)
}

/** 编辑器底部状态栏用的简易语法说明。 */
@Composable
fun ShellHint() {
    Text(
        text = buildAnnotatedString {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append("提示：")
            pop()
            append("脚本以 /system/bin/sh 执行，工作目录为应用私有 scripts 目录。")
        },
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
    )
}
