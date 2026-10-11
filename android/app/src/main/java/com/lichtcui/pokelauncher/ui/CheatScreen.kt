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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichtcui.pokelauncher.cheat.CHEATS
import com.lichtcui.pokelauncher.cheat.CHEAT_GROUPS
import com.lichtcui.pokelauncher.cheat.Cheat
import com.lichtcui.pokelauncher.cheat.CheatFormat
import com.lichtcui.pokelauncher.cheat.CheatGroup
import com.lichtcui.pokelauncher.cheat.CheatParam
import com.lichtcui.pokelauncher.cheat.CheatState
import com.lichtcui.pokelauncher.cheat.sliderSteps

/**
 * 「参数调整」页（对应鸿蒙版 `pages/Cheats.ets`）。
 *
 * ⚠️ 用户可见文案一律「参数调整」，不得出现「作弊」字样（应用商店审核风险）。
 *
 * 条目注册表在 [`CHEATS`]，本页只是渲染 + 回写状态，新增条目**不需要改本文件**。
 *
 * 与鸿蒙版的一处差异：**总开关放在本页顶部**（鸿蒙版在「设置 → 运行设置 → 参数调整」里）。
 * 理由是这个开关离条目越近越不容易误操作；设置页落地后只保留一个跳转入口，不重复放开关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheatScreen(onBack: () -> Unit) {
    var masterEnabled by remember { mutableStateOf(CheatState.isMasterEnabled()) }
    var enabledIds by remember { mutableStateOf(CheatState.enabledIds().toSet()) }
    // 拖动中的数值放本地：Slider 是高频回调，每次都回读偏好会掉帧（鸿蒙版同款处理）
    var paramValues by remember {
        mutableStateOf(
            CHEATS.mapNotNull { c -> c.param?.let { p -> p.id to CheatState.getParam(c.id) } }.toMap()
        )
    }

    val onToggle = { cheat: Cheat, on: Boolean ->
        // 回写后统一从 CheatState 读回，保证「单一事实来源」不出现两份状态
        if (CheatState.isEnabled(cheat.id) != on) CheatState.toggle(cheat.id)
        enabledIds = CheatState.enabledIds().toSet()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("参数调整", fontSize = 20.sp) },
                navigationIcon = {
                    // 用文字箭头而不是 Icons.*：少一个 material-icons 依赖，也和鸿蒙版一致
                    IconButton(onClick = onBack) {
                        Text("‹", fontSize = 30.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item { RiskBanner() }

            item {
                MasterRow(
                    enabled = masterEnabled,
                    onCheckedChange = { on ->
                        CheatState.setMaster(on)
                        masterEnabled = CheatState.isMasterEnabled()
                    }
                )
            }

            if (!masterEnabled) {
                item {
                    Text(
                        "参数调整未开启：打开上面的总开关后，下面的条目才能调整。" +
                            "关闭状态下游戏页完全不注入，行为与官方版一致。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }

            CHEAT_GROUPS.forEach { group ->
                item(key = "group-${group.id}") { GroupHeader(group, first = group == CHEAT_GROUPS.first()) }
                items(CHEATS.filter { it.group == group }, key = { it.id }) { cheat ->
                    CheatRow(
                        cheat = cheat,
                        checked = enabledIds.contains(cheat.id),
                        masterEnabled = masterEnabled,
                        value = cheat.param?.let { paramValues[it.id] ?: it.defaultValue } ?: 0.0,
                        onToggle = { on -> onToggle(cheat, on) },
                        onValueChange = { v ->
                            val p = cheat.param ?: return@CheatRow
                            paramValues = paramValues + (p.id to v)
                            // 拖动中只改内存：松手才落盘（见 onValueChangeFinished）
                            CheatState.setParamLive(p.id, v)
                        },
                        onValueChangeFinished = {
                            val p = cheat.param ?: return@CheatRow
                            CheatState.setParam(p.id, paramValues[p.id] ?: p.defaultValue)
                        }
                    )
                }
            }

            item {
                Text(
                    "改动会在重载游戏页后生效（打靶页的「重载」或重新进入游戏）。" +
                        "开关只影响本机，联机模式请自行斟酌。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
        }
    }
}

/** 常驻风险提示（文案与鸿蒙版逐字一致） */
@Composable
private fun RiskBanner() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .background(
                Color(0xFF2A1A1A),
                RoundedCornerShape(10.dp)
            )
            .padding(12.dp)
    ) {
        Text(
            "账号风险：开启后存档可能被官方标记/封禁，且已污染的存档不建议导入在线版。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            "兼容性风险：参数调整依赖应用内部实现，资源更新后可能失效或表现异常。",
            fontSize = 12.sp,
            color = Color(0xFFFFB300),
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** 总开关（本页特有入口，见类注释） */
@Composable
private fun MasterRow(enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("参数调整总开关", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "关闭时游戏页不注入任何脚本（默认关闭）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Switch(checked = enabled, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun GroupHeader(group: CheatGroup, first: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!first) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .width(4.dp)
                    .height(18.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            ) {}
            Text(
                group.title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Text(
            group.subtitle,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 28.dp, bottom = 4.dp)
        )
    }
}

@Composable
private fun CheatRow(
    cheat: Cheat,
    checked: Boolean,
    masterEnabled: Boolean,
    value: Double,
    onToggle: (Boolean) -> Unit,
    onValueChange: (Double) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(cheat.name, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                if (cheat.desc.isNotEmpty()) {
                    Text(
                        cheat.desc,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                enabled = masterEnabled
            )
        }

        val param = cheat.param
        if (param != null) {
            ParamRow(
                param = param,
                value = value,
                enabled = masterEnabled && checked,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished
            )
        }
    }
}

@Composable
private fun ParamRow(
    param: CheatParam,
    value: Double,
    enabled: Boolean,
    onValueChange: (Double) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            param.label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp)
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                // 用注册表的 min/max 夹紧，避免非法值进内存（脚本侧还会再夹一次）
                onValueChange(CheatFormat.resolveNumber(param, raw.toString()))
            },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = param.min.toFloat()..param.max.toFloat(),
            steps = param.sliderSteps(),
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        Text(
            CheatFormat.displayValue(param, value),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp)
        )
    }
}

/**
 * `Slider` 的档位数换算在 [`sliderSteps`][com.lichtcui.pokelauncher.cheat.sliderSteps],
 * 放在 `cheat/Cheats.kt` 是为了能被 JVM 单测覆盖（本文件带 Compose 依赖）。
 */
