package com.lichtcui.pokelauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 法律与免责声明页（对应鸿蒙版 `pages/Legal.ets`）。
 * 正文在 [`LEGAL_SECTIONS`][com.lichtcui.pokelauncher.ui.LEGAL_SECTIONS]（纯数据文件，可单测）。
 *
 * 页面本身没有逻辑：不联网、不读写偏好、不需要返回结果。
 */
@Composable
fun LegalScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = { LauncherTopBar(title = "法律与免责声明", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 常驻提示条（对应鸿蒙版的 #FFF8E8 条）
            Text(
                LEGAL_BANNER,
                fontSize = 12.sp,
                color = THEME.warning,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(THEME.warningBg)
                    .padding(12.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 40.dp)
            ) {
                itemsIndexed(LEGAL_SECTIONS, key = { _, section -> section.id }) { index, section ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    LegalSectionBlock(section)
                }
                item {
                    Text(
                        LEGAL_UPDATED_AT,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun LegalSectionBlock(section: LegalSection) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 标题前的红色竖条（与鸿蒙版一致）
            Row(
                modifier = Modifier
                    .width(4.dp)
                    .height(18.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            ) {}
            Text(
                section.title,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        section.body.forEachIndexed { index, paragraph ->
            Text(
                paragraph,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = if (index == 0) 10.dp else 8.dp)
            )
        }
    }
}

