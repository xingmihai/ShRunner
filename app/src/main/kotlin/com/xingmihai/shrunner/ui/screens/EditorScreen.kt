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

        // 超大文件保护：这类脚本多半内嵌编码块，编辑没有意义，
        // 而且塞进 TextField 会卡死界面（渲染与光标计算都是字符级的）
        val tooLarge = state.text.length > EDITABLE_LIMIT
        if (tooLarge) {
            Text(
                text = "文件过大（${state.text.length} 字符），已切换为只读预览。" +
                    "这类脚本通常内嵌了编码数据，建议直接运行而不是编辑。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(8.dp)
            )
            Text(
                text = state.text.take(PREVIEW_LIMIT),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            )
            return@Column
        }

        val transformation = remember(state.text) { ShellHighlightTransformation() }
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

/**
 * 只在显示层做关键字着色。
 *
 * 注意：这里必须用 OffsetMapping.Identity，前提是 highlightShell 返回的文本
 * 与原文本长度严格一致（它内部已保证，超长时直接原样返回）。
 * 一旦长度对不上，光标/选区偏移就会越界并导致崩溃。
 */
/** 可编辑的字符上限与只读预览的字符上限。 */
private const val EDITABLE_LIMIT = 1_000_000
private const val PREVIEW_LIMIT = 60_000

private class ShellHighlightTransformation : VisualTransformation {
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
